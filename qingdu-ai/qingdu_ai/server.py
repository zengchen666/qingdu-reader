"""FastAPI 端点。

## 错误分类（设计文档 5.3）

界面要能对不同失败给不同提示，所以**错误必须分类而不是统一 500**：

| 情况                     | HTTP | reason              |
|--------------------------|------|---------------------|
| 没配 API key             | 503  | not-configured      |
| 轻读没召回到片段          | 200  | no-chunks           |
| 模型调用失败             | 502  | llm-error           |
| 引用编号全部无效          | 200  | invalid-citations   |
| 正常                     | 200  | ok                  |

**为什么"召不回"和"引用无效"是 200 而不是 4xx？**
它们不是调用失败，而是**有效结论：库里没有能回答这个问题的原文**。
用 200 + ``answer=null`` 表达，界面才有机会给"换个说法试试，用书名/人名更准"
这种有用的提示；用 4xx 会让它落进通用错误分支。

**为什么"没配 key"是 503 而不是 500？**
503 明确定位为"依赖服务不可用"，前端可以据此只提示配置问题、不做重试。
500 会让人以为是服务崩了。
"""

from __future__ import annotations

import os
import time

from fastapi import FastAPI, HTTPException

from . import __version__
from .config import Settings, load_settings
from .llm import LlmError, call_llm, validate
from .prompts import NO_CONTEXT_USER_PROMPT, build_user_prompt
from .retrieval import render_context, select
from .schemas import AskRequest, AskResponse, Coverage, HealthResponse


def create_app(settings: Settings | None = None) -> FastAPI:
    """构造应用。

    刻意支持注入 settings：测试要构造一个"有 key""没 key"两种状态，
    靠环境变量切换在并行测试下不可靠。
    """
    app = FastAPI(
        title="轻读 AI 服务",
        version=__version__,
        description="为轻读阅读器提供基于原文的问答。只读，不改写原文。",
    )

    def current_settings() -> Settings:
        # 每次请求都重读，这样改了环境变量后无需重启即可生效（便于调试）。
        return settings if settings is not None else load_settings()

    @app.get("/api/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        """健康检查 —— 轻读靠它决定 AI 入口是否置灰。

        注意 ``ok`` 恒为 True：服务本身活着。没配 key 是**配置问题**不是服务问题，
        ``llm`` 字段已经把这件事说清楚了；把 ok 置 False 会让轻读把入口整个禁用，
        而实际上用户只需要填个 key。
        """
        s = current_settings()
        return HealthResponse(ok=True, llm=s.llm_state, version=__version__)

    @app.post("/api/ask", response_model=AskResponse)
    async def ask(req: AskRequest) -> AskResponse:
        started = time.monotonic()
        s = current_settings()

        if not s.configured:
            raise HTTPException(
                status_code=503,
                detail="未配置模型 API（需要 QINGDU_LLM_BASE_URL / QINGDU_LLM_API_KEY / QINGDU_LLM_MODEL）",
            )

        # 轻读已负责召回，这里只做关键词重排、去重、打散、截断。
        # 🔴 question 必须传：轻读给的是"按章排列的该章全部段落"，
        # 不按问题重排的话，含答案的段落可能排在第 40 位、根本进不了上下文。
        chunks = select(req.chunks, req.top_k, req.question)

        coverage = Coverage(
            searched_books=len(req.book_ids),
            indexed_books=len({c.book_id for c in req.chunks}),
            hits=len(req.chunks),
        )

        if not chunks:
            # 召不回 —— 200 + answer=null，不调模型（省一次延迟与 token）。
            return AskResponse(
                answer=None,
                citations=[],
                coverage=coverage,
                elapsed_ms=int((time.monotonic() - started) * 1000),
                reason="no-chunks",
            )

        context = render_context(chunks)
        user_prompt = build_user_prompt(req.question, context)

        try:
            raw = await call_llm(s, req.question, user_prompt)
        except LlmError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc

        result = validate(raw, chunks)
        elapsed = int((time.monotonic() - started) * 1000)

        if not result.answer:
            return AskResponse(
                answer=None,
                citations=[],
                coverage=coverage,
                elapsed_ms=elapsed,
                reason="invalid-citations",
            )

        return AskResponse(
            answer=result.answer,
            citations=result.citations,
            coverage=coverage,
            elapsed_ms=elapsed,
            reason="ok",
        )

    return app


#: 模块级 app，供 ``uvicorn qingdu_ai.server:app`` 使用。
#: 刻意在**导入时**就建好 —— uvicorn 的工厂模式要求这里有个现成对象。
app = create_app()


def main() -> None:
    """命令行入口（同时支持 ``python -m qingdu_ai`` 与 ``qingdu-ai``）。

    **为什么启动时打印提示而不是静默？**
    用户是手动敲命令起这个服务的，看到"缺 key，服务会起来但不能作答"
    比启动完什么反馈都没有要好。
    """
    import uvicorn

    from .config import DEFAULT_HOST, DEFAULT_PORT

    host = os.environ.get("QINGDU_HOST", DEFAULT_HOST)
    port = int(os.environ.get("QINGDU_PORT", str(DEFAULT_PORT)))

    s = load_settings()
    print(f"轻读 AI 服务 v{__version__}  http://{host}:{port}")
    if s.llm_state == "ready":
        print(f"  模型：{s.model}  ({s.base_url})")
    elif s.llm_state == "no-api-key":
        print("  ⚠ 未设置 QINGDU_LLM_API_KEY —— 服务会启动，但问答会返回 503")
    else:
        print("  ⚠ 未设置 QINGDU_LLM_BASE_URL / QINGDU_LLM_MODEL —— 服务会启动，但问答会返回 503")
    print("  轻读需要看到本服务才会启用 AI 入口。按 Ctrl+C 停止。")

    uvicorn.run(app, host=host, port=port, log_level="info")
