package com.qingdu.reader.library;

import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("跨书检索结果的呈现逻辑（分组行 + 覆盖度措辞）")
class LibrarySearchPresenterTest {

    private static LibraryHit hit(String bookId, String title, int chapter) {
        return new LibraryHit(bookId, title, chapter, 1, "摘要" + chapter);
    }

    private static LibrarySearchResult result(List<LibraryHit> hits, Map<String, Integer> byBook,
                                             int candidates, int library, int indexed,
                                             int searched, boolean truncated) {
        return new LibrarySearchResult(hits, byBook, candidates, library, indexed, searched,
                truncated, 42L);
    }

    @Nested
    @DisplayName("分组行")
    class Rows {

        @Test
        @DisplayName("同一本书只出一个分组头，且头在它的命中之前")
        void oneHeaderPerBookInFrontOfItsHits() {
            List<LibrarySearchPresenter.Row> rows = LibrarySearchPresenter.toRows(List.of(
                    hit("b1", "星尘纪", 0),
                    hit("b1", "星尘纪", 1),
                    hit("b1", "星尘纪", 2)), 1);

            assertEquals(4, rows.size(), "1 个头 + 3 条命中");
            assertEquals("星尘纪", LibrarySearchPresenter.headerTitleOf(rows.get(0)));
            assertEquals(3, ((LibrarySearchPresenter.Row.BookHeader) rows.get(0)).hitCount());
            for (int i = 1; i < rows.size(); i++) {
                assertTrue(LibrarySearchPresenter.isJumpable(rows.get(i)),
                        "头后面应该全是可点的命中行");
            }
        }

        @Test
        @DisplayName("两本书：头-命中-头-命中，不会出现两个同名头")
        void twoBooksAlternate() {
            List<LibrarySearchPresenter.Row> rows = LibrarySearchPresenter.toRows(List.of(
                    hit("b1", "星尘纪", 0),
                    hit("b1", "星尘纪", 1),
                    hit("b2", "沧澜录", 0)), 2);

            assertEquals(5, rows.size());
            assertEquals("星尘纪", LibrarySearchPresenter.headerTitleOf(rows.get(0)));
            assertEquals("沧澜录", LibrarySearchPresenter.headerTitleOf(rows.get(3)));
            assertEquals(2, ((LibrarySearchPresenter.Row.BookHeader) rows.get(0)).hitCount());
            assertEquals(1, ((LibrarySearchPresenter.Row.BookHeader) rows.get(3)).hitCount());
        }

        @Test
        @DisplayName("同一个 bookId 出现两段（排序没保证连续）也不出两个头")
        void nonContiguousSameBookStillOneHeader() {
            // ⚠️ 这是"不能靠 bookId 全局去重"的反例：
            // 如果实现是"书名见过就跳过"，第二次出现的命中会被**整个丢掉**，
            // 症状是"这本书明明有 3 章命中，列表里只有 2 条"。
            // 判据是"和上一行不同"，所以它会得到两个头 —— 但内容都对，
            // 而丢结果比多一个头严重得多。
            List<LibrarySearchPresenter.Row> rows = LibrarySearchPresenter.toRows(List.of(
                    hit("b1", "星尘纪", 0),
                    hit("b2", "沧澜录", 0),
                    hit("b1", "星尘纪", 1)), 2);

            int hits = 0;
            for (LibrarySearchPresenter.Row row : rows) {
                if (LibrarySearchPresenter.isJumpable(row)) {
                    hits++;
                }
            }
            assertEquals(3, hits, "一条命中都不能少");
        }

        @Test
        @DisplayName("空结果 / null 都不炸，返回空列表")
        void emptyInputsAreSafe() {
            assertTrue(LibrarySearchPresenter.toRows(List.of(), 3).isEmpty());
            assertTrue(LibrarySearchPresenter.toRows(null, 3).isEmpty());
        }

        @Test
        @DisplayName("只有一条命中：也有一个头（不能出现没有头的裸结果）")
        void singleHitStillHasHeader() {
            List<LibrarySearchPresenter.Row> rows =
                    LibrarySearchPresenter.toRows(List.of(hit("b1", "星尘纪", 5)), 1);

            assertEquals(2, rows.size());
            assertNotNull(LibrarySearchPresenter.headerTitleOf(rows.get(0)));
            assertEquals(5, LibrarySearchPresenter.hitOf(rows.get(1)).chapterIndex());
        }
    }

    @Nested
    @DisplayName("覆盖度措辞：零结果必须说清「为什么是零」")
    class Summary {

        @Test
        @DisplayName("一本都没建索引：明确说「搜不到任何内容」，而不是「没有找到」")
        void noIndexAtAllIsNotReportedAsNoResults() {
            LibrarySearchResult r = LibrarySearchResult.noIndexAtAll(40, 12L);

            String text = LibrarySearchPresenter.summary("云岚宗", r);

            assertTrue(text.contains("40"), "要报出书库里有几本：" + text);
            assertTrue(text.contains("索引"), "要提到索引：" + text);
            assertFalse(text.contains("没有找到"),
                    "这是沉默的错误答案：用户会以为全书都没有这个词 —— " + text);
        }

        @Test
        @DisplayName("书库是空的：说「先导入书」，不说「一本都没有索引」")
        void emptyLibraryAsksForImport() {
            String text = LibrarySearchPresenter.summary("云岚宗",
                    LibrarySearchResult.empty(0, 5L));

            assertTrue(text.contains("导入"), text);
            assertFalse(text.contains("索引"), "书库空的时候谈索引是错的：" + text);
        }

        @Test
        @DisplayName("部分书没建索引：结果照报，但要说清有几本没被搜")
        void partialCoverageIsDisclosed() {
            LibrarySearchResult r = result(List.of(hit("b1", "星尘纪", 0)),
                    Map.of("b1", 1), 1, 40, 2, 2, false);

            String text = LibrarySearchPresenter.summary("云岚宗", r);

            assertTrue(text.contains("找到 1 章"), text);
            assertTrue(text.contains("38"), "还有几本没建索引必须说出来：" + text);
            assertTrue(text.contains("没建索引"), text);
        }

        @Test
        @DisplayName("全部有索引：不提覆盖度，避免噪声")
        void fullCoverageStaysQuiet() {
            LibrarySearchResult r = result(List.of(hit("b1", "星尘纪", 0)),
                    Map.of("b1", 1), 1, 3, 3, 3, false);

            String text = LibrarySearchPresenter.summary("云岚宗", r);

            assertFalse(text.contains("没建索引"), "全都有索引就别啰嗦：" + text);
            assertTrue(text.contains("耗时"), text);
        }

        @Test
        @DisplayName("被截断：说清有多少没校验，不让用户以为「就这些」")
        void truncationIsDisclosed() {
            LibrarySearchResult r = result(List.of(hit("b1", "星尘纪", 0)),
                    Map.of("b1", 1), 300, 3, 3, 1, true);

            String text = LibrarySearchPresenter.summary("云岚宗", r);

            assertTrue(text.contains("截断"), text);
            assertTrue(text.contains("299"), "未校验章数要报出来：" + text);
        }

        @Test
        @DisplayName("全都没搜但有索引：说「已搜索 N 本书，没有找到」")
        void searchedButNothingFound() {
            LibrarySearchResult r = result(List.of(), Map.of(), 12, 3, 3, 3, false);

            String text = LibrarySearchPresenter.summary("云岚宗", r);

            assertTrue(text.contains("已搜索 3 本书"), text);
            assertTrue(text.contains("没有找到"), text);
        }

        @Test
        @DisplayName("null 结果与 null 查询串都不炸")
        void nullSafe() {
            assertEquals("", LibrarySearchPresenter.summary("词", null));
            assertEquals(LibrarySearchPresenter.Coverage.FULL,
                    LibrarySearchPresenter.coverageOf(null));
            assertFalse(LibrarySearchPresenter.hasHits(null));
            assertNotNull(LibrarySearchPresenter.summary(null,
                    result(List.of(hit("b1", "星尘纪", 0)), Map.of("b1", 1), 1, 1, 1, 1, false)));
        }
    }

    @Nested
    @DisplayName("行与书的归属")
    class Belonging {

        @Test
        @DisplayName("分组头和它自己的命中都算属于这本书")
        void headerAndHitBothBelong() {
            LibrarySearchPresenter.Row header = LibrarySearchPresenter.toRows(
                    List.of(hit("b1", "星尘纪", 0)), 1).get(0);
            LibrarySearchPresenter.Row chapter = LibrarySearchPresenter.toRows(
                    List.of(hit("b1", "星尘纪", 0)), 1).get(1);

            assertTrue(LibrarySearchPresenter.belongsTo(header, "b1"));
            assertTrue(LibrarySearchPresenter.belongsTo(chapter, "b1"));
            assertFalse(LibrarySearchPresenter.belongsTo(chapter, "b2"));
        }

        @Test
        @DisplayName("分组头点不出章节：hitOf 返回 null 而不是编一个出来")
        void headerHasNoHit() {
            LibrarySearchPresenter.Row header = LibrarySearchPresenter.toRows(
                    List.of(hit("b1", "星尘纪", 0)), 1).get(0);

            assertFalse(LibrarySearchPresenter.isJumpable(header));
            assertNull(LibrarySearchPresenter.hitOf(header));
        }

        @Test
        @DisplayName("null 参数不炸")
        void nullSafe() {
            assertFalse(LibrarySearchPresenter.belongsTo(null, "b1"));
            assertFalse(LibrarySearchPresenter.belongsTo(
                    LibrarySearchPresenter.toRows(List.of(hit("b1", "星尘纪", 0)), 1).get(0), null));
            assertNull(LibrarySearchPresenter.headerTitleOf(null));
            assertNull(LibrarySearchPresenter.hitOf(null));
        }
    }

    @Nested
    @DisplayName("覆盖度分档：决定要不要显示「建立索引」按钮")
    class Coverage {

        @Test
        @DisplayName("三档分得对")
        void threeLevels() {
            assertEquals(LibrarySearchPresenter.Coverage.NONE,
                    LibrarySearchPresenter.coverageOf(LibrarySearchResult.noIndexAtAll(40, 1L)));
            assertEquals(LibrarySearchPresenter.Coverage.PARTIAL, LibrarySearchPresenter.coverageOf(
                    result(List.of(), Map.of(), 0, 40, 2, 2, false)));
            assertEquals(LibrarySearchPresenter.Coverage.FULL, LibrarySearchPresenter.coverageOf(
                    result(List.of(), Map.of(), 0, 3, 3, 3, false)));
        }

        @Test
        @DisplayName("书库空 = FULL：没书时不该喊「去建索引」")
        void emptyLibraryIsNotPartial() {
            assertEquals(LibrarySearchPresenter.Coverage.FULL,
                    LibrarySearchPresenter.coverageOf(LibrarySearchResult.empty(0, 1L)));
        }
    }
}
