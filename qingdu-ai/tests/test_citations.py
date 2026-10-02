"""引用编号校验的测试 —— v0.4 正确性闸门。

设计文档 7.1 列的第一条必测项。这里每条用例都对应一种**真实的模型失败形态**，
不是构造出来的假想。
"""

from __future__ import annotations

from qingdu_ai.llm import extract_refs, strip_invalid_citations, validate


class TestExtractRefs:
    def test_basic(self):
        assert extract_refs("他来自云岚宗[1]。药老助他[2]。") == [1, 2]

    def test_dedupe_keeps_first_order(self):
        # 同一句里重复引用、或答案里 [2] 先于 [1] 出现，都要按首次出现排
        assert extract_refs("先看[2]再[1]，又回到[2]。") == [2, 1]

    def test_ignores_wrong_bracket_styles(self):
        # 只认半角方括号。模型偶尔会写全角，那是格式漂移，
        # 宁可漏掉（→ 判为无引用 → 无法回答）也不要误判成有效引用。
        assert extract_refs("（1）他来了。第2段提到。") == []

    def test_no_refs(self):
        assert extract_refs("完全没有任何引用的一段话。") == []


class TestStripInvalid:
    def test_removes_unknown_ref(self):
        text, invalid = strip_invalid_citations("他说[1]，又提到[7]。", {1, 2, 3})
        assert "[7]" not in text
        assert "[1]" in text
        assert invalid == [7]

    def test_keeps_all_valid(self):
        text, invalid = strip_invalid_citations("甲[1]乙[2]。", {1, 2})
        assert invalid == []
        assert text == "甲[1]乙[2]。"


class TestValidate:
    def test_all_refs_invalid_gives_up(self, chunks):
        """🔴 核心用例：模型只引用了不存在的 [99] ⇒ 判为无法回答。

        这是最典型的幻觉形态 —— 模型"记不清"是哪一段，凭印象编了个序号。
        放它过去，界面上就会出现一个点不动的 [99]。
        """
        result = validate("药老在[99]章第一次出现。", chunks)
        assert result.answer == ""
        assert result.citations == []
        assert result.invalid_refs == [99]

    def test_mixed_refs_keeps_valid_only(self, chunks):
        result = validate("云岚宗在[1]，药老在[99]。", chunks)
        assert [c.index for c in result.citations] == [1]
        assert result.invalid_refs == [99]
        assert "[99]" not in result.answer
        assert "[1]" in result.answer

    def test_out_of_range_high_ref(self, chunks):
        """越界的高位编号（片段只有 3 个，模型报 [100]）也要拦。"""
        result = validate("原文提到[100]。", chunks)
        assert result.answer == ""
        assert result.invalid_refs == [100]

    def test_citation_maps_back_to_chunk(self, chunks):
        """引用编号必须能映射回真实片段 —— 这是"引用可核对"的前提。"""
        result = validate("药老替他解了封印[2]。", chunks)
        assert len(result.citations) == 1
        c = result.citations[0]
        assert c.index == 2
        assert c.book_id == "b1"
        assert c.chapter_index == 929
        assert c.paragraph_index == 1
        assert "武动乾坤" in c.ref
        # 出处显示用 1 起，数据库里是 0 起
        assert "第 930 章" in c.ref
        assert "第 2 段" in c.ref

    def test_uncited_sentences_dropped(self, chunks):
        """没有引用标记的句子要被删掉。

        一段模型用自身知识补出来的文字混在有出处的答案里，
        危害比整段拒答更大 —— 用户会以为整段都有出处。
        """
        result = validate("云岚宗追杀未完[1]。另外林动天赋极高，是天纵之才。", chunks)
        assert "天纵之才" not in result.answer
        assert "云岚宗" in result.answer

    def test_no_chunks_means_no_answer(self):
        """片段集合为空时，无论模型说什么都判为无引用。"""
        result = validate("随便说点什么[1]。", [])
        assert result.answer == ""
        assert result.citations == []

    def test_excerpt_is_truncated(self):
        from qingdu_ai.schemas import LightChunk

        long_chunk = LightChunk(
            book_id="b", book_title="长文", chapter_index=0,
            chapter_title="第一章", paragraph_index=0, text="字" * 500,
        )
        result = validate("如上所述[1]。", [long_chunk])
        assert len(result.citations[0].excerpt) <= 121  # 120 + 省略号
        assert result.citations[0].excerpt.endswith("…")
