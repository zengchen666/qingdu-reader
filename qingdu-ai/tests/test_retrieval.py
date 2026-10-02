"""片段重排测试 —— 重点是章节打散与去重。"""

from __future__ import annotations

from qingdu_ai.retrieval import render_context, select
from qingdu_ai.schemas import LightChunk


def chunk(b="b1", ci=0, pi=0, text="内容", title="测试"):
    return LightChunk(
        book_id=b, book_title="书", chapter_index=ci,
        chapter_title=title, paragraph_index=pi, text=text,
    )


class TestSelect:
    def test_empty(self):
        assert select([]) == []
        assert select([], top_k=0) == []

    def test_top_k_respected(self):
        cs = [chunk(ci=0, pi=i, text=f"第{i}段") for i in range(10)]
        assert len(select(cs, top_k=4)) == 4

    def test_dedupe_same_paragraph(self):
        """同一段落重复命中只保留一份 —— 省 token，也避免模型重复引用同一段。"""
        cs = [chunk(ci=0, pi=0, text="重复"), chunk(ci=0, pi=0, text="重复"), chunk(ci=0, pi=1, text="另一段")]
        got = select(cs, top_k=5)
        assert len(got) == 2

    def test_same_chapter_capped_when_other_chapters_exist(self):
        """🔴 多章竞争时，同章最多取 2 段。

        一章 5 段全进上下文会挤掉其他章，且让模型过度关注单一场景。
        5 段来自同一本书的不同章，预算 3 —— 期望每章各取 1~2 段。
        """
        cs = [chunk(b="b1", ci=0, pi=i, text=f"a{i}") for i in range(3)]
        cs += [chunk(b="b2", ci=0, pi=i, text=f"b{i}") for i in range(3)]
        got = select(cs, top_k=3)
        assert len(got) == 3
        # 三个名额不能被单一 book_id 吃满
        assert len({c.book_id for c in got}) == 2

    def test_same_chapter_capped_hard(self):
        """只有一章有 5 段、top_k=3 时，硬上限依然生效。"""
        cs = [chunk(ci=0, pi=i, text=f"第{i}段") for i in range(5)]
        got = select(cs, top_k=3)
        assert len(got) == 3
        assert [c.paragraph_index for c in got] == [0, 1, 2]

    def test_single_chapter_not_wasted(self):
        """⚠️ 唯一一章的片段不该被硬性丢弃。

        章节打散的目的只是"别让一章挤掉其他章"；**没有其他章时就不存在"挤"**，
        再按 2 段硬砍等于白扔已召回的原文。所以 top_k 大于该章段数时要全给。
        """
        cs = [chunk(ci=0, pi=i, text=f"第{i}段") for i in range(5)]
        got = select(cs, top_k=5)
        assert len(got) == 5

    def test_round_robin_fill_from_leftovers(self):
        """不够 top_k 时用同章剩余段补齐 —— 不浪费已召回的原文。"""
        cs = [chunk(ci=0, pi=i, text=f"第{i}段") for i in range(4)]
        got = select(cs, top_k=4)
        assert len(got) == 4
        assert [c.paragraph_index for c in got] == [0, 1, 2, 3]

    def test_multi_chapter_spread(self):
        cs = [chunk(b="b1", ci=0, pi=i, text=f"a{i}") for i in range(3)]
        cs += [chunk(b="b2", ci=0, pi=i, text=f"c{i}") for i in range(3)]
        got = select(cs, top_k=4)
        books = {c.book_id for c in got}
        assert books == {"b1", "b2"}

    def test_empty_text_dropped(self):
        assert select([chunk(text="   ")]) == []

    def test_oversized_truncated(self):
        got = select([chunk(text="字" * 5000)])
        assert len(got[0].text) <= 1201
        assert got[0].text.endswith("…")

    def test_preserves_relevance_order(self):
        """打散之后仍按原相关性顺序排回 —— 输出必须可复现。"""
        cs = [chunk(b="b1", ci=0, pi=0, text="a"), chunk(b="b2", ci=0, pi=0, text="b"), chunk(b="b3", ci=0, pi=0, text="c")]
        assert [c.book_id for c in select(cs, top_k=3)] == ["b1", "b2", "b3"]


class TestCamelCaseContract:
    """🔴 跨进程字段名契约。

    轻读用 Jackson，默认发 camelCase（``bookIds`` / ``chapterIndex``）。
    Pydantic 默认认 snake_case，**不匹配的多余字段会被静默丢弃** ——
    请求返回 200，但数据全丢。这条测试就是为了钉住这个坑。
    """

    def test_request_accepts_camel_case(self):
        from qingdu_ai.schemas import AskRequest

        req = AskRequest.model_validate({
            "question": "药老是谁",
            "bookIds": ["b1", "b2"],
            "chunks": [{
                "bookId": "b1",
                "bookTitle": "武动乾坤",
                "chapterIndex": 929,
                "chapterTitle": "第九百三十章",
                "paragraphIndex": 2,
                "text": "药老现身。",
            }],
            "topK": 4,
        })
        assert req.book_ids == ["b1", "b2"]
        assert req.top_k == 4
        assert req.chunks[0].book_id == "b1"
        assert req.chunks[0].chapter_index == 929
        assert req.chunks[0].paragraph_index == 2

    def test_response_emits_camel_case(self):
        """响应侧也要是 camelCase —— 轻读按 camelCase 解析。"""
        from qingdu_ai.schemas import AskResponse, Citation, Coverage

        resp = AskResponse(
            answer="答案[1]",
            citations=[Citation(index=1, book_id="b1", book_title="书",
                               chapter_index=0, paragraph_index=1, ref="《书》第 1 章")],
            coverage=Coverage(searched_books=2, indexed_books=2, hits=3),
            elapsed_ms=42,
        )
        dumped = resp.model_dump(by_alias=True)
        assert "elapsedMs" in dumped
        assert dumped["coverage"]["searchedBooks"] == 2
        assert dumped["citations"][0]["bookId"] == "b1"
        assert dumped["citations"][0]["chapterIndex"] == 0
        assert "book_id" not in dumped["citations"][0]


class TestRerankByQuestion:
    """🔴 真机验收逼出来的重排测试。

    第一版 `select` 只做去重+打散，完全依赖传入顺序。而轻读传的是
    **「按章排列的该章全部段落」**，不是按段落命中次数排列。
    在《全职高手》/叶修 上实测：候选 156 段里 66 段含「叶修」，
    但打散取「每章前 2 段」后送进上下文的 8 段**含词 0 段** ——
    模型于是诚实回答「原文里没有提到叶修」。它说的是真话，但答案毫无价值。

    这不是幻觉问题，是**召回排序问题**：候选里有答案，却没被选进去。
    """

    def test_answer_bearing_chunk_rises_to_top(self):
        # 模拟真书形态：命中章里 40 段，只有第 39 段含关键词
        cs = [chunk(ci=0, pi=i, text=f"无关键词的第{i}段") for i in range(39)]
        cs.append(chunk(ci=0, pi=39, text="叶修是C区47号机登记的客人名字"))
        got = select(cs, top_k=2, question="叶修")
        assert any("叶修" in c.text for c in got), "含答案的片段必须被选进上下文"

    def test_stable_for_equal_scores(self):
        """同分片段必须保持原顺序 —— 否则同一份输入得到不同输出，无法复现。"""
        cs = [chunk(ci=0, pi=i, text=f"叶修段落{i}") for i in range(4)]
        got = select(cs, top_k=4, question="叶修")
        assert [c.paragraph_index for c in got] == [0, 1, 2, 3]

    def test_query_is_stripped_of_particles(self):
        """问题里的疑问词不该影响打分：「叶修是怎么退役的」≈「叶修」。

        打分只关心"哪些字值得数"，不追求切分正确 ——
        真正判断这段有没有答案的是模型。
        """
        cs = [chunk(ci=0, pi=0, text="无关内容")]
        cs.append(chunk(ci=0, pi=1, text="叶修退役了"))
        got = select(cs, top_k=1, question="叶修是怎么退役的")
        assert "叶修" in got[0].text

    def test_rerank_happens_before_spreading(self):
        """🔴 重排必须在章节打散**之前**。

        打散取的是「每组的前 N 段」—— 只有先按相关性排好，
        取出来的才是相关的段。顺序反了等于没重排。
        """
        cs = [chunk(ci=0, pi=i, text=f"填充{i}") for i in range(10)]
        cs.append(chunk(ci=0, pi=10, text="云岚宗的核心势力"))
        got = select(cs, top_k=2, question="云岚宗")
        assert got[0].text == "云岚宗的核心势力"

    def test_no_question_keeps_original_order(self):
        """不传问题时退化为原有行为，不引入未定义行为。"""
        cs = [chunk(ci=0, pi=0, text="甲"), chunk(ci=0, pi=1, text="乙")]
        assert [c.text for c in select(cs, top_k=2)] == ["甲", "乙"]


class TestRenderContext:
    def test_numbered_from_one(self):
        ctx = render_context([chunk(ci=0, pi=0, text="甲"), chunk(ci=0, pi=1, text="乙")])
        assert "【片段1" in ctx
        assert "【片段2" in ctx
        assert "甲" in ctx and "乙" in ctx

    def test_includes_location_hint(self):
        """每段前面带"第几章第几段" —— 让模型生成时就带着出处意识。

        后处理只能过滤假引用，补不出真引用：模型若没记录出处，事后无从得知。
        """
        ctx = render_context([chunk(ci=929, pi=2, title="第九百三十章")])
        assert "第 930 章" in ctx
        assert "第 3 段" in ctx

    def test_empty_context(self):
        assert render_context([]) == ""
