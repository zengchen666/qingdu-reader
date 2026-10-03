"""共享测试夹具。"""

from __future__ import annotations

import os

import pytest

from qingdu_ai import config
from qingdu_ai.config import Settings
from qingdu_ai.schemas import LightChunk


@pytest.fixture(autouse=True)
def _isolated_env(monkeypatch, tmp_path):
    """把测试和"开发者本机配置"隔开。

    🔴 这条 fixture 是**必须**的：``load_settings()`` 会读 ``qingdu-ai/.env``，
    而开发机上这个文件带着真实 API key。没有隔离的话，
    任何不显式传 ``settings`` 的 ``create_app()`` 调用都会读到它，
    于是测试结果随"谁在跑"而变 —— 最糟的那种不确定性
    （本机全绿、CI 或同事机器上红，且看不出原因）。

    做法：清掉所有 ``QINGDU_*`` 环境变量，并把默认配置路径指向临时目录。
    需要测配置读取的用例**显式传 ``env_file=``**，不依赖这里的默认值。
    """
    for name in list(os.environ):
        if name.startswith("QINGDU_"):
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(config, "env_file_path", lambda: tmp_path / ".env")


def make_settings(**overrides) -> Settings:
    """构造一个"配置完整"的 Settings，测试里按需覆盖某一两项。"""
    base = {
        "base_url": "https://api.example.com/v1",
        "api_key": "sk-test",
        "model": "test-model",
    }
    base.update(overrides)
    return Settings(**base)


@pytest.fixture
def chunks() -> list[LightChunk]:
    """三个片段：同章两段 + 另一章一段。用于验证章节打散。"""
    return [
        LightChunk(
            book_id="b1",
            book_title="武动乾坤",
            chapter_index=929,
            chapter_title="第九百三十章",
            paragraph_index=0,
            text="林动双眸微闭，呼吸渐渐平复。他知道云岚宗的追杀还没有结束。",
        ),
        LightChunk(
            book_id="b1",
            book_title="武动乾坤",
            chapter_index=929,
            chapter_title="第九百三十章",
            paragraph_index=1,
            text="药老的身影浮现在半空，声音沙哑：'你身上的封印，老夫替你解了。'",
        ),
        LightChunk(
            book_id="b2",
            book_title="斗破苍穹",
            chapter_index=12,
            chapter_title="第十三章 陨落的天才",
            paragraph_index=0,
            text="萧炎缓缓抬起手，掌心之上，青色火焰悄然升腾。",
        ),
    ]
