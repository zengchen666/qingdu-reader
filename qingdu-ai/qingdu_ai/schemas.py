"""进程间数据契约（Pydantic 模型）。

**⚠️ 与 v0.4 设计文档的一处修正（2026-10-03）**

设计文档 5.2 原本把请求写成 ``{"question", "bookIds", "topK"}``，同时 1.1 又写
「检索层在轻读（FTS5）」。这两条**不能同时成立**：检索在轻读侧，就必须轻读先检索完
才能发请求，那么请求体里必须带原文片段 —— 否则 Python 拿不到任何原文，无从作答。

所以实际契约改为「轻读检索 + 切分 → 传片段 → Python 重排并生成」：

* 仍然满足「一次问答 = 一次请求」（轻读在发请求前完成检索与切分）
* 仍然满足「Python 不碰文件系统」（片段由轻读喂进来）
* 仍然满足「检索层只有一处」（FTS5 在轻读，Python 只做片段级重排与截断）

**为什么用 Pydantic 而不是裸 dict？**
跨进程边界的输入一律不可信。轻读是本机程序，但它可能是个旧版本、可能字段拼错了。
在 Python 侧用模型校验一次，错误会在**服务日志里说清楚是哪个字段**，
而不是在生成到一半时抛一个 `KeyError: 'chunks'`。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

#: 所有模型统一用 camelCase 对外。
#:
#: 🔴 **这不是风格选择，是踩过的坑**：Pydantic 默认把 ``book_ids`` 和请求里的
#: ``bookIds`` 当成两个不同的名字，而**不匹配的多余字段默认被静默丢弃** ——
#: 请求会成功返回 200，只是 ``searched_books`` 悄悄变成 0。
#: 轻读侧用 Jackson，默认就是 camelCase，所以 Python 侧必须对齐，
#: 否则跨进程契约看起来"通了"但数据全丢，这种 bug 极难查。
#:
#: ``populate_by_name=True`` 保留按 Python 字段名（snake_case）访问的能力，
#: 让本模块内部代码读起来仍然是 ``chunk.book_id``。
_CAMEL = ConfigDict(alias_generator=to_camel, populate_by_name=True)

#: 默认送进上下文的片段数。8 是权衡结果：太少覆盖不全，
#: 太多会稀释注意力且 token 成本线性上升。
DEFAULT_TOP_K = 8

#: 单次请求允许携带的片段数上限。**这是防御性上限**——
#: 轻读可能一次切出几百个段落，全塞进来会让请求体膨胀到几十 MB。
#: 超了直接截断并保持原顺序（顺序即相关性顺序）。
MAX_CHUNKS = 200


class LightChunk(BaseModel):
    """一个原文片段 —— 由轻读切好，Python 只读不造。

    字段与 Java 侧 ``ChapterBlock.Paragraph`` / ``LibraryHit`` 对齐：

    * ``book_id``：由文件路径派生的稳定 ID，跨进程一致
    * ``chapter_index``：0 起
    * ``paragraph_index``：章内第几段

    **刻意没有 ``offset``**：字节偏移是轻读进程内部的东西，
    跨进程传它既无意义，也会让 Python 侧产生"我也能读文件"的错觉。
    """

    model_config = _CAMEL

    book_id: str = Field(min_length=1)
    book_title: str = ""
    chapter_index: int = Field(ge=0)
    chapter_title: str = ""
    paragraph_index: int = Field(ge=0)
    text: str = Field(min_length=1)

    @field_validator("book_title", "chapter_title", mode="before")
    @classmethod
    def _blank_to_empty(cls, v: object) -> str:
        """null / None 一律归一成空串。

        Java 侧 ``LibraryHit`` 的构造器已经会把空书名替换成"（未知书名）"，
        但那是从数据库读的；这里的片段可能来自别的路径，不能假设它一定填了。
        """
        return "" if v is None else str(v)

    def short_ref(self) -> str:
        """人类可读的出处，例如 ``《武动乾坤》第 930 章第 3 段``。

        章节序号在界面上要显示成 1 起（数据库里是 0 起），
        这个 ``+1`` 是**显示约定**，不要拿去当索引用。
        """
        parts = [f"《{self.book_title}》"] if self.book_title else []
        parts.append(f"第 {self.chapter_index + 1} 章")
        if self.paragraph_index >= 0:
            parts.append(f"第 {self.paragraph_index + 1} 段")
        return "".join(parts)


class AskRequest(BaseModel):
    """一次问答请求。"""

    model_config = _CAMEL

    question: str = Field(min_length=1, max_length=500)
    #: 轻读检索到的候选片段。**由轻读负责召回**，Python 只做重排与截断。
    chunks: list[LightChunk] = Field(default_factory=list)
    #: 本次提问的范围：书库里参与检索的书（用于回报覆盖度）。
    book_ids: list[str] = Field(default_factory=list)
    top_k: int = Field(default=DEFAULT_TOP_K, ge=1, le=MAX_CHUNKS)

    @field_validator("question", mode="before")
    @classmethod
    def _question_not_blank(cls, v: object) -> str:
        if v is None:
            raise ValueError("question 不能为空")
        s = str(v).strip()
        if not s:
            raise ValueError("question 不能是空白")
        return s

    @field_validator("chunks", mode="before")
    @classmethod
    def _chunks_not_none(cls, v: object) -> list:
        return [] if v is None else v


class Citation(BaseModel):
    """一条引用 —— 编号与出处，用于在轻读里做成可点击跳转。

    ``index`` 是**片段在本次请求中的编号**（1 起），与模型在答案里写的 ``[n]`` 对应。
    """

    model_config = _CAMEL

    index: int = Field(ge=1)
    book_id: str
    book_title: str
    chapter_index: int = Field(ge=0)
    chapter_title: str = ""
    paragraph_index: int = Field(ge=0)
    #: 出处的人话版本，直接显示在引用列表里。
    ref: str = ""
    #: 片段原文节选（截断后），让用户不跳转也能核对。
    excerpt: str = ""


class Coverage(BaseModel):
    """覆盖度 —— 界面必须显示，否则就是"沉默的错误答案"。

    沿用 v0.3 全库检索的三个数字：

    * ``searchedBooks``：本次实际检索了几本书
    * ``indexedBooks``：其中几本已有全文索引
    * ``hits``：召回了多少个片段

    用户问"为什么搜不到"，九成是"那本书还没建索引"。
    没有这三个数字，用户只能猜。
    """

    model_config = _CAMEL

    searched_books: int = 0
    indexed_books: int = 0
    hits: int = 0


class AskResponse(BaseModel):
    """一次问答的响应。

    ⚠️ **``answer`` 允许为 null**，这不是偷懒，是刻意的：
    召不回原文、或模型给的引用编号全部无效时，**宁可承认答不出**，
    也不返回一段没有出处的话。这与"防幻觉"是同一件事的两面。
    """

    model_config = _CAMEL

    answer: str | None = None
    citations: list[Citation] = Field(default_factory=list)
    coverage: Coverage = Field(default_factory=Coverage)
    elapsed_ms: int = 0
    #: 机器可读的原因码，界面据此给不同提示。
    #:
    #: - ``ok``：正常作答
    #: - ``no-chunks``：轻读没召回到任何片段
    #: - ``invalid-citations``：模型返回的引用编号一个都不合法
    #: - ``llm-error``：模型调用失败（HTTP 502）
    #: - ``not-configured``：服务端没配 key / base_url（HTTP 503）
    reason: Literal["ok", "no-chunks", "invalid-citations", "llm-error", "not-configured"] = "ok"


class HealthResponse(BaseModel):
    """`GET /api/health` 的响应 —— 轻读靠它判断 AI 入口该不该置灰。"""

    ok: bool
    llm: Literal["ready", "no-api-key", "not-configured"]
    version: str
