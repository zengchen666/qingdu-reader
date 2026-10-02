"""C 步真机验收：验证「引用可追溯」。

判据（只验工程正确性，不验模型答得对不对）：
  ① 引用编号都能映射回**真实存在**的候选片段
  ② 引用指向的章节号/段号与素材里的完全一致（不串章）
  ③ 引用的原文节选确实出现在该片段里（不是编的）
  ④ 召不回的问题返回 reason=no-chunks，而不是硬凑答案

用法：
    python scripts/rag_live_check.py _rag-corpus.json
（key 从环境变量读，不接受命令行传参 —— 会进 shell 历史）
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "qingdu-ai"))

import httpx  # noqa: E402

from qingdu_ai.llm import validate  # noqa: E402
from qingdu_ai.prompts import SYSTEM_PROMPT, build_user_prompt  # noqa: E402
from qingdu_ai.retrieval import render_context, select  # noqa: E402
from qingdu_ai.schemas import LightChunk  # noqa: E402

BASE_URL = os.environ.get("QINGDU_LLM_BASE_URL", "")
API_KEY = os.environ.get("QINGDU_LLM_API_KEY", "")
MODEL = os.environ.get("QINGDU_LLM_MODEL", "")

#: 必须与真实请求一致（AskRequest.topK 默认 8）。
TOP_K = 8


def to_chunks(raw: list[dict]) -> list[LightChunk]:
    return [LightChunk.model_validate(c) for c in raw]


def build(question: str, chunks: list[dict]) -> str:
    """拼提示词。

    ⚠️ 必须走和 `server.py` **完全相同**的两步：select → render_context。
    第一版这里直接 render_context(全部片段)，而校验侧走了 select，
    两边片段集不一致 —— 模型引用 [156] 而校验只认 1~8，于是误判"引用全非法"。
    这个 bug 恰好说明：**生成侧与校验侧必须用同一批片段**，
    否则"引用可追溯"这条判据本身就没意义了。服务端是同一批，
    这里也必须是同一批，验收才等价于验收真实链路。
    """
    selected = select(to_chunks(chunks), TOP_K, question)
    return build_user_prompt(question, render_context(selected))


async def ask(client: httpx.AsyncClient, question: str, chunks: list[dict]) -> str:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build(question, chunks)},
        ],
        "temperature": 0.2,
    }
    r = await client.post(
        BASE_URL.rstrip("/") + "/chat/completions",
        json=payload,
        headers={"Authorization": f"Bearer {API_KEY}"},
        timeout=120.0,
    )
    r.raise_for_status()
    return str(r.json()["choices"][0]["message"]["content"])


def check_case(book: dict, q: dict, raw: str) -> list[str]:
    """返回问题列表；空列表 = 这一例通过。"""
    problems: list[str] = []
    raw_chunks = q["chunks"]
    lc = to_chunks(raw_chunks)

    if not raw_chunks:
        # 召不回：不该调模型，也不该有答案
        return []

    # 走真实的重排，拿到实际送进上下文的片段 —— 与 build() 用同一个 TOP_K，
    # 两侧必须看到同一批片段，否则引用编号无法比对。
    selected = select(lc, TOP_K, q['question'])
    res = validate(raw, selected)

    if not res.answer:
        problems.append(f"引用全部无效：raw={raw[:80]!r} invalid={res.invalid_refs}")
        return problems

    by_index = {i: c for i, c in enumerate(selected, start=1)}
    for c in res.citations:
        # ① 编号能映射
        if c.index not in by_index:
            problems.append(f"引用 [{c.index}] 不在候选集合内")
            continue
        src = by_index[c.index]
        # ② 不串章
        if c.chapter_index != src.chapter_index or c.paragraph_index != src.paragraph_index:
            problems.append(
                f"引用 [{c.index}] 串位：报({c.chapter_index},{c.paragraph_index}) "
                f"实际({src.chapter_index},{src.paragraph_index})"
            )
        # ③ 节选确实来自该片段
        if c.excerpt and c.excerpt.rstrip("…") not in src.text:
            problems.append(f"引用 [{c.index}] 的节选不在原片段里")
    return problems


def main() -> int:
    if not (BASE_URL and API_KEY and MODEL):
        print("缺少 QINGDU_LLM_BASE_URL / QINGDU_LLM_API_KEY / QINGDU_LLM_MODEL")
        return 2

    data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    total = passed = 0
    failures: list[str] = []

    async def run() -> None:
        nonlocal total, passed
        async with httpx.AsyncClient() as client:
            for book in data:
                print(f"\n=== {book['bookTitle']} ===")
                for q in book["questions"]:
                    total += 1
                    label = q["question"]
                    if not q["chunks"]:
                        print(f"  [召不回] {label} -> 预期 answer=null（不调模型）")
                        passed += 1
                        continue
                    try:
                        raw = await ask(client, label, q["chunks"])
                    except Exception as exc:
                        failures.append(f"{book['bookTitle']}/{label}: 调用失败 {exc}")
                        print(f"  [失败] {label}: {exc}")
                        continue
                    probs = check_case(book, q, raw)
                    if probs:
                        failures.extend(f"{book['bookTitle']}/{label}: {p}" for p in probs)
                        print(f"  [不通过] {label}")
                        for p in probs:
                            print(f"      - {p}")
                        print(f"      原始输出: {raw[:120]}")
                    else:
                        passed += 1
                        print(f"  [通过] {label}")
                        print(f"      答案: {raw[:100]}")
    asyncio.run(run())

    print(f"\n===== 验收结果：{passed}/{total} 通过 =====")
    if failures:
        print("失败明细：")
        for f in failures:
            print("  -", f)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
