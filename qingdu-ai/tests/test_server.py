"""端点行为测试 —— 固化"服务没起 / 没 key / 召不回"三种状态。

设计文档 7.1 要求：无 API key 返回 503（不是 500）、召不回返回 answer=null。
这两条是界面提示的分叉点，**不能靠人工点界面验证**。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from qingdu_ai.server import create_app

from conftest import make_settings


@pytest.fixture
def configured_client():
    return TestClient(create_app(make_settings()))


@pytest.fixture
def no_key_client():
    return TestClient(create_app(make_settings(api_key="")))


class TestHealth:
    def test_ready(self, configured_client):
        r = configured_client.get("/api/health")
        assert r.status_code == 200
        body = r.json()
        assert body["ok"] is True
        assert body["llm"] == "ready"
        assert body["version"] == "0.4.0"

    def test_no_api_key(self, no_key_client):
        r = no_key_client.get("/api/health")
        assert r.status_code == 200
        assert r.json()["llm"] == "no-api-key"

    def test_not_configured(self):
        client = TestClient(create_app(make_settings(base_url="", model="")))
        assert client.get("/api/health").json()["llm"] == "not-configured"

    def test_ok_stays_true_even_without_key(self, no_key_client):
        """🔴 没配 key 时 ok 仍是 True。

        服务本身活着，"缺 key"是配置问题。置 False 会让轻读把 AI 入口整个禁用，
        而用户实际只需要填个 key —— 过早禁用比提示更糟。
        """
        assert no_key_client.get("/api/health").json()["ok"] is True


class TestAskNoKey:
    def test_returns_503_not_500(self, no_key_client):
        """设计文档 7.1 明确要求 503 —— 前端据此只提示配置、不重试。"""
        r = no_key_client.post("/api/ask", json={"question": "药老是谁", "chunks": []})
        assert r.status_code == 503
        assert "QINGDU_LLM_API_KEY" in r.json()["detail"]


class TestAskValidation:
    def test_blank_question_rejected(self, configured_client):
        r = configured_client.post("/api/ask", json={"question": "   ", "chunks": []})
        assert r.status_code == 422

    def test_missing_chunks_defaults_to_empty(self, configured_client):
        """轻读还没接上时也不能崩 —— 应走"召不回"分支。"""
        r = configured_client.post("/api/ask", json={"question": "药老是谁"})
        assert r.status_code == 200
        assert r.json()["answer"] is None
        assert r.json()["reason"] == "no-chunks"


class TestAskNoChunks:
    def test_answer_is_null_not_fabricated(self, configured_client):
        """🔴 召不回时 answer 必须是 null，不能是编的一段话。

        返回 200 而不是 4xx：这是**有效结论**（库里没有能回答的原文），
        界面要据此给"换个说法试试"这种有用提示。
        """
        r = configured_client.post(
            "/api/ask",
            json={
                "question": "他怎么变强的",
                "chunks": [],
                "bookIds": ["b1", "b2"],
            },
        )
        assert r.status_code == 200
        body = r.json()
        assert body["answer"] is None
        assert body["citations"] == []
        assert body["reason"] == "no-chunks"
        # 响应按 camelCase 输出（轻读用 Jackson 解析），
        # 且覆盖度数字必须真的带上 —— 它是"为什么搜不到"的唯一线索。
        assert body["coverage"]["searchedBooks"] == 2
        assert body["elapsedMs"] >= 0
