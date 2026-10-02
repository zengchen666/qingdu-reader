"""LLM 调用与引用校验 —— 防幻觉的第二道闸。

## 为什么服务端必须校验引用编号

提示词里要求模型"每个结论必须带 [n]"，但**提示词是请求，不是保证**。
模型完全可能：

* 引用了不存在的编号（``[7]`` 而实际只给了 6 个片段）—— 最常见的幻觉形态：
  它"记不清"是哪一段，凭印象编了个序号
* 把编号写错格式（``（1）`` ``[1,2]`` ``第1段``）
* 引用了有效编号，但那句话的内容其实来自别的片段（这个**无法靠编号校验发现**，
  只能靠用户核对原文 —— 这是引用机制的价值所在：让错误可被发现）

前两种能机器拦，**必须拦**。如果原样把 ``[7]`` 透传给界面，
用户点进去会发现"第 7 章"根本不存在 —— 这是**工程事故**，不是模型能力问题，
在面试里也说不清楚。所以：**一个有效引用都没有 ⇒ 判为无法回答**。

## 为什么不自己实现重试

模型偶发格式漂移时，正确做法是让调用方（轻读）决定要不要重试。
服务端静默重试会掩盖问题，也会让一次问答的耗时变得不可预测。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

import httpx

from .config import Settings
from .prompts import SYSTEM_PROMPT
from .schemas import Citation, LightChunk

#: 匹配引用编号。**只认半角方括号 + 纯数字**：
#: 宁可漏掉 ``（1）`` 这种格式（模型会因此被判为无引用 → 无法回答，
#: 这是安全的失败方向），也不要误把正文里的 ``[1]`` 式的文字当引用。
_CITE_RE = re.compile(r"\[(\d+)\]")

#: 引用编号个数上限。防的是"模型把 1..8 全列一遍"这种无信息量的输出。
_MAX_CITES = 12

#: 引用片段节选的最大字符数。够用户核对，又不至于把界面撑爆。
_EXCERPT_CHARS = 120


class LlmError(RuntimeError):
    """模型调用失败 —— 由 server 层翻译成 HTTP 502。"""


@dataclass(frozen=True)
class ValidatedAnswer:
    """校验通过的答案。

    ``answer`` 为空字符串 ⇔ 没有任何有效引用（判为无法回答）。
    两种情况**必须区分**："模型说了话但全是假引用"和"模型说了话且引用有效"
    在用户面前是不同的反馈。
    """

    answer: str
    citations: list[Citation]
    invalid_refs: list[int]


def extract_refs(text: str) -> list[int]:
    """从答案里抽出引用编号，**按首次出现排序并去重**。

    去重是有意的：模型同一句里写 ``[1][1]`` 应当只算一个引用。
    顺序也重要 —— 引用列表按答案里首次提及的顺序展示，
    用户的阅读顺序和界面的展示顺序一致。
    """
    out: list[int] = []
    seen: set[int] = set()
    for m in _CITE_RE.finditer(text):
        n = int(m.group(1))
        if n not in seen:
            seen.add(n)
            out.append(n)
        if len(out) >= _MAX_CITES:
            break
    return out


def strip_invalid_citations(text: str, valid: set[int]) -> tuple[str, list[int]]:
    """把不在合法集合里的 ``[n]`` 从答案里删掉。

    :return: (清洗后的答案, 被删掉的编号列表)

    **为什么要"删掉"而不是"留着不管"？**
    留着的话界面上会出现一个点不动的 ``[7]``，用户点了才发现那章不存在。
    删掉之后，模型那句无出处的话就失去了它的引用标记 ——
    而没有引用的句子**本来就不该被展示**（见下方 ``validate``）。
    """
    invalid: list[int] = []

    def _sub(m: re.Match[str]) -> str:
        n = int(m.group(1))
        if n in valid:
            return m.group(0)
        if n not in invalid:
            invalid.append(n)
        return ""

    return _CITE_RE.sub(_sub, text), invalid


def _build_citations(refs: list[int], chunks: list[LightChunk]) -> list[Citation]:
    """把编号映射回片段，生成引用列表。"""
    by_index = {i: c for i, c in enumerate(chunks, start=1)}
    out: list[Citation] = []
    for n in refs:
        c = by_index.get(n)
        if c is None:
            continue
        excerpt = c.text.strip()
        if len(excerpt) > _EXCERPT_CHARS:
            excerpt = excerpt[:_EXCERPT_CHARS] + "…"
        out.append(
            Citation(
                index=n,
                book_id=c.book_id,
                book_title=c.book_title,
                chapter_index=c.chapter_index,
                chapter_title=c.chapter_title,
                paragraph_index=c.paragraph_index,
                ref=c.short_ref(),
                excerpt=excerpt,
            )
        )
    return out


def validate(answer_text: str, chunks: list[LightChunk]) -> ValidatedAnswer:
    """校验模型答案里的引用编号。

    这是整个 RAG 链路的**正确性闸门**，所以它的判定必须明确、无歧义：

    * 引用编号全部非法 → ``answer=""``、``citations=[]``，调用方判为无法回答
    * 引用编号部分合法 → 保留合法部分，**并把无引用的句子删掉**

    为什么要删无引用的句子？一段"模型用自己知识补出来"的文字混在有出处的答案里，
    危害比整段拒答大得多 —— 用户会以为整段都有出处。
    """
    valid_set = {i for i in range(1, len(chunks) + 1)}
    if not valid_set:
        return ValidatedAnswer(answer="", citations=[], invalid_refs=[])

    cleaned, invalid = strip_invalid_citations(answer_text or "", valid_set)
    refs = extract_refs(cleaned)
    citations = _build_citations(refs, chunks)

    if not citations:
        # 一个有效引用都没有 —— 无论它说了什么，都判为无法回答。
        return ValidatedAnswer(answer="", citations=[], invalid_refs=invalid)

    # 删掉不带任何有效引用的句子。
    kept = _drop_uncited_sentences(cleaned, set(refs))

    return ValidatedAnswer(answer=kept, citations=citations, invalid_refs=invalid)


def _drop_uncited_sentences(text: str, valid_refs: set[int]) -> str:
    """丢掉不含有效引用的句子。

    **按句号类标点断句**，不按 ``。！？`` 之外的符号 ——
    小说正文里逗号、分号出现频率极高，按它们断会碎成无意义的短句。
    """
    parts = re.split(r"(?<=[。！？!?])", text)
    kept: list[str] = []
    for p in parts:
        if not p.strip():
            continue
        if any(f"[{n}]" in p for n in valid_refs):
            kept.append(p)
    result = "".join(kept).strip()
    return result or text.strip()


async def call_llm(
    settings: Settings,
    question: str,
    context: str,
    *,
    client: httpx.AsyncClient | None = None,
) -> str:
    """调用 OpenAI 兼容接口，返回答案原文。

    走 ``/chat/completions`` 而不用 SDK：三项配置（base_url / api_key / model）
    就能接 DeepSeek、通义、本地 Ollama，不需要为每家引入一个依赖。
    """
    if not settings.configured:
        raise LlmError("未配置模型 API")

    url = settings.base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": settings.model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": context},
        ],
        "temperature": 0.2,
    }
    headers = {
        "Authorization": f"Bearer {settings.api_key}",
        "Content-Type": "application/json",
    }

    try:
        if client is not None:
            resp = await client.post(url, json=payload, headers=headers, timeout=settings.timeout_seconds)
        else:
            async with httpx.AsyncClient() as c:
                resp = await c.post(url, json=payload, headers=headers, timeout=settings.timeout_seconds)
    except httpx.HTTPError as exc:
        raise LlmError(f"模型服务不可达: {exc}") from exc

    if resp.status_code >= 400:
        # 只截取响应体前 200 字：错误信息可能含 key 或内部地址，不该整段进日志。
        detail = resp.text[:200]
        raise LlmError(f"模型服务返回 {resp.status_code}: {detail}")

    try:
        data = resp.json()
        return str(data["choices"][0]["message"]["content"])
    except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
        raise LlmError(f"模型响应格式异常: {exc}") from exc
