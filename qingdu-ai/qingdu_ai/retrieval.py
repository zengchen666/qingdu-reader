"""片段重排 —— 在轻读给的候选里挑出送进上下文的那些。

**先说清楚一件事：这一层不是"再做一次检索"。**

轻读的 FTS5 已经完成了真正的召回（分词 → MATCH → 原文后过滤消假阳性），
那是唯一有检索语义的地方，Python 不重复它。那这一层干什么？

三件轻读**不方便做**的事：

1. **去重**：轻读可能对同一段落重复命中（不同查询词命中同一段）
2. **章节打散**：同一章的相邻段落如果都进上下文，既浪费 token，
   又会让模型过度关注某一个场景 —— 这在 RAG 里是明确的负面效应
3. **截断到 top_k**：控制 token 预算

**为什么打散是"同一章最多取 N 段"而不是"完全禁止同章"？**
完全禁止会把"某一章里反复提到某个词"这种真实的强信号也丢掉。
折中办法是每章设上限（``MAX_PER_CHAPTER``），超出的按分数排进备胎，
等别的章用完预算后再回来补 —— 叫**轮转填充**（round-robin fill）。

**为什么不用向量相似度重排？**
v0.4 明确只做关键词召回（设计文档 3.1）。在没有向量的情况下，
这里的"重排"就是可解释的结构性打散 —— 每一步都能说清为什么，
面试时讲得出理由；v0.5 接上向量后再叠加真正的语义重排。
"""

from __future__ import annotations

import re
from collections import OrderedDict

from .schemas import DEFAULT_TOP_K, MAX_CHUNKS, LightChunk

#: 同一章最多取几段。2 的理由：一章往往有多个场景，
#: 给 2 段能覆盖"起-承"或"承-转"，再多就挤掉别的章了。
MAX_PER_CHAPTER = 2

#: 片段编号分隔符。**必须是不可见或全角字符**：
#: 片段正文里如果恰好出现 "[1]"，模型可能把它当成引用编号，
#: 进而引用到一个不存在的位置。用全角方括号 + 显式"片段N"标签来降低这个风险。
_CHUNK_HEADER = "【片段{n}｜{ref}】"

_WS_RE = re.compile(r"\s+")


def _chapter_key(chunk: LightChunk) -> str:
    """章内去重的键。

    用 ``book_id + chapter_index`` 而不是标题 —— 标题可能重复（多本书都有"第一章"），
    而 ``book_id`` 是路径派生的，跨进程稳定。
    """
    return f"{chunk.book_id}#{chunk.chapter_index}"


def _dedupe(chunks: list[LightChunk]) -> list[LightChunk]:
    """按 (书, 章, 段) 去重，保留首次出现的顺序。

    用 ``OrderedDict`` 而不是 ``set`` 是因为**顺序必须稳定**：
    轻读给的顺序就是相关性顺序，重排只能在它之上做"取舍"，
    不能打乱 —— 同一份输入必须得到同一份输出，否则问题无法复现。
    """
    seen: "OrderedDict[tuple[str, int, int], LightChunk]" = OrderedDict()
    for c in chunks:
        key = (c.book_id, c.chapter_index, c.paragraph_index)
        if key in seen:
            continue
        seen[key] = c
    return list(seen.values())


def _normalize_text(text: str) -> str:
    """折叠空白，让"两段其实是一段被空行切开"的情况能被判为重复。"""
    return _WS_RE.sub("", text)


def _drop_empty_and_oversized(chunks: list[LightChunk]) -> list[LightChunk]:
    """丢掉空片段，并对超长片段做截断。

    **为什么要在 Python 侧截断，而不在轻读侧？**
    因为轻读切的是"一个段落"，而 TXT 里的段落理论上可以极长
    （有些书的整章就是一行）。轻读不该为了迎合 LLM 去改自己的切分策略；
    Python 侧截断只影响"送进上下文的那一份"，原始数据保持完整。

    截断到 ``MAX_CHARS`` 而不是按句子边界切，是为了保留一个简单可预测的性质：
    **片段编号与片段内容永远一一对应**。切碎了还要维护子编号，
    引用校验的复杂度会翻倍，不值得。
    ⚠️ **这里不做数量截断。** 第一版写了 ``out[:max(top_k*4, top_k)]``，
    结果它跑在关键词重排**之前** —— 实测「云岚宗」那例里含答案的片段排在第 11 位，
    直接被这个"保护性"截断丢掉，后面再怎么重排也救不回来。
    数量控制统一放在两处，且都在重排之后：
    入口 ``MAX_CHUNKS`` 限流（防请求体膨胀）+ ``select`` 末尾 ``[:top_k]``。
    """
    out: list[LightChunk] = []
    for c in chunks:
        text = c.text.strip()
        if not text:
            continue
        if len(text) > MAX_CHARS:
            text = text[:MAX_CHARS] + "…"
        out.append(
            c.model_copy(update={"text": text, "book_title": c.book_title, "chapter_title": c.chapter_title})
        )
    return out


#: 单个片段的字符上限。1200 字 ≈ 1800 token，
#: top_k=8 满打满算约 1.4 万 token，对绝大多数模型都在上下文内，
#: 也让"整段进上下文"不至于稀释注意力。
MAX_CHARS = 1200


def _query_terms(question: str) -> list[str]:
    """从问题里抽出用于打分的关键片段。

    🔴 **第一版这里只做"删标点"，是个真 bug。**
    「叶修是怎么退役的」删掉标点后**仍是一个整串**，
    于是 ``text.count("叶修是怎么退役的")`` 恒为 0 —— 打分对长句完全失效。
    真机验收时这条路径静默退化，没有任何报错。

    修法与 `qingdu-common` 的 `CjkTokenizer` 同一个思路：**中文按 n-gram 滑窗**。
    取 bi~quad-gram：
    * bi-gram（「叶修」「修是」「是怎」…）保证任何中文串都能被切出可匹配的片段
    * 长的那几段（「叶修是怎么退役的」）也保留，让"整句命中"仍能加权
    * ASCII 词（人名拼音、章节号）整体保留，不该被切碎

    **为什么不用 jieba / 分词库？**
    这个打分只用来"挑出更可能含答案的片段"，不是精确切分。
    引入分词库会多一个依赖，而收益几乎为零 ——
    真正判断"这段有没有答案"的是模型，不是这里的分数。
    n-gram 的好处是**不依赖词典**，对专有名词和生僻词都稳。
    """
    terms: set[str] = set()

    # 连续 ASCII（字母数字）作为一个词
    for m in re.finditer(r"[A-Za-z0-9]+", question):
        w = m.group(0)
        if len(w) >= 2:
            terms.add(w)

    # 连续中文：同时保留整串与 n-gram
    for run in re.findall(r"[一-鿿]+", question):
        if len(run) <= 4:
            terms.add(run)
        for n in (2, 3, 4):
            for i in range(len(run) - n + 1):
                terms.add(run[i:i + n])

    return sorted(terms)


def _score(chunk: LightChunk, terms: list[str]) -> int:
    """片段的关键词命中数。

    **计数而不是布尔**：一个专有名词在一段里出现 5 次，比出现 1 次更可能是
    这段的主题句。但要注意"出现次数多"也可能是列表/重复，
    所以下面 ``_rerank_by_terms`` 用的是**先按分数降序、再按原顺序**，
    避免纯数量把同一段的重复内容顶上来。
    """
    if not terms:
        return 0
    text = chunk.text
    return sum(text.count(t) for t in terms)


def _rerank_by_terms(chunks: list[LightChunk], question: str) -> list[LightChunk]:
    """按关键词命中数重排（稳定排序）。

    🔴 **这一段是 v0.4 真机验收逼出来的。**
    第一版没有重排，完全依赖轻读传进来的顺序。而轻读传的是
    **「按章排列的该章全部段落」**（FTS5 命中章 → 整章切段），
    不是「按段落命中次数」排列。于是在《全职高手》/叶修 这类问题上：

      * 轻读召回 156 段，其中 **66 段含「叶修」**
      * 章节打散后取「每章前 2 段」→ 送进上下文的 8 段里**含词 0 段**
      * 模型于是诚实回答「原文里没有提到叶修」—— 它说的是真话，
        但答案毫无价值

    也就是说**问题不在防幻觉，在召回排序**：候选里明明有答案，却没被选进去。
    这与 v0.2 时期"零假阳性"的结论不冲突 —— 那验证的是"命中的都准"，
    这里发现的是"该命的没全被选上"，是另一个方向的问题。

    **稳定排序（分数相同时保持原顺序）**是必须的：同分的片段多数来自同一段
    上下文，顺序不该乱，否则同一份输入会得到不同输出，问题无法复现。
    """
    terms = _query_terms(question)
    if not terms:
        return chunks
    # n-gram 会切出「修是」「是怎」这类无意义组合。它们几乎不会出现在正文里，
    # 一旦出现也只是噪声，所以**按片段集合整体过滤一次**：
    # 只保留在至少一个片段里出现过的项。这比维护停用词表省事，
    # 且效果更好——停用词表永远列不全。
    corpus_joined = "\n".join(c.text for c in chunks)
    terms = [t for t in terms if t in corpus_joined]
    if not terms:
        return chunks
    indexed = list(enumerate(chunks))
    indexed.sort(key=lambda pair: (-_score(pair[1], terms), pair[0]))
    return [c for _, c in indexed]


def select(chunks: list[LightChunk], top_k: int = DEFAULT_TOP_K,
           question: str = "") -> list[LightChunk]:
    """选出送进上下文的片段。

    :param chunks:轻读传来的候选（按章排列）
    :param top_k:最终返回的片段数上限
    :param question:用户问题，**必须传** —— 靠它把含答案的片段排到前面
    :return: 重新编号前的片段列表（编号在 :func:`render_context` 里加）
    """
    if top_k <= 0:
        return []
    pool = _dedupe(chunks[:MAX_CHUNKS])
    pool = _drop_empty_and_oversized(pool)
    if not pool:
        return []

    # 🔴 先按问题重排，再分组打散。顺序不能反：
    # 打散取的是「每组的前 N 段」，只有先按相关性排好，取出来的才是相关的段。
    if question:
        pool = _rerank_by_terms(pool, question)

    # 按章分组，保持首次出现顺序（此时已是相关性顺序）。
    by_chapter: "OrderedDict[str, list[LightChunk]]" = OrderedDict()
    for c in pool:
        by_chapter.setdefault(_chapter_key(c), []).append(c)

    # 第一轮：每章取前 MAX_PER_CHAPTER 段。
    selected: list[LightChunk] = []
    leftovers: list[LightChunk] = []
    for group in by_chapter.values():
        selected.extend(group[:MAX_PER_CHAPTER])
        leftovers.extend(group[MAX_PER_CHAPTER:])

    # 第二轮：若还没凑够 top_k，用各章剩余的段轮转补齐。
    if len(selected) < top_k and leftovers:
        selected.extend(leftovers[: max(0, top_k - len(selected))])

    return selected[:top_k]


def render_context(chunks: list[LightChunk]) -> str:
    """把片段渲染成带编号的上下文文本。

    **编号从 1 起**，与答案里的 ``[n]`` 一一对应。
    每段前面显式写"第几本书第几章第几段"，让模型**在生成时就带着出处意识**，
    而不是等生成完再靠后处理补 —— 后处理只能过滤掉假引用，
    补不出真引用（模型若没记录出处，事后无从得知它说的是哪一段）。
    """
    if not chunks:
        return ""
    parts: list[str] = []
    for n, c in enumerate(chunks, start=1):
        header = _CHUNK_HEADER.format(n=n, ref=c.short_ref())
        parts.append(f"{header}\n{c.text}")
    return "\n\n".join(parts)
