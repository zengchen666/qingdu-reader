"""共享测试夹具。"""

from __future__ import annotations

import pytest

from qingdu_ai.config import Settings
from qingdu_ai.schemas import LightChunk


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
