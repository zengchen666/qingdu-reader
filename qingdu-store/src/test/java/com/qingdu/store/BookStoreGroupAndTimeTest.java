package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.RecentBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.3 新增的分组与阅读时长测试。
 *
 * <p>和 {@link BookStoreTest} 分开放而不是并进去，理由是<b>关注点不同</b>：
 * 那个文件验的是"书与进度的基本存取"，这里验的是 v0.3 新加的两组规则
 * （分组的归一化、时长的累加语义）。文件变大之后，
 * 一个 {@code @DisplayName} 就能告诉你在测哪一块。
 */
class BookStoreGroupAndTimeTest {

    @TempDir
    Path tempDir;

    private BookStore books;

    @BeforeEach
    void setUp() {
        books = new BookStore(Database.open(tempDir.resolve("test.db")));
    }

    private Book book(String id, String title) {
        return new Book(id, title, "某作者", BookFormat.TXT,
                tempDir.resolve(title + ".txt"), null, 0, Instant.ofEpochMilli(1_000_000L));
    }

    private ReadingProgress progress(String bookId, long updatedAt) {
        return new ReadingProgress(bookId, 0, null, 0, updatedAt);
    }

    // ==================== 分组 ====================

    @Test
    @DisplayName("归组与取消归组")
    void setAndClearGroup() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        assertTrue(books.setGroup("b1", "玄幻"));
        assertEquals("玄幻", books.groupOf("b1"));
        assertEquals("玄幻", books.list().get(0).groupName());

        assertTrue(books.setGroup("b1", null));
        assertNull(books.groupOf("b1"));
        assertFalse(books.list().get(0).grouped());
    }

    @Test
    @DisplayName("设成同一个分组会返回 false，界面就不用白刷新一次")
    void settingSameGroupReportsNoChange() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));
        books.setGroup("b1", "玄幻");

        assertFalse(books.setGroup("b1", "玄幻"), "本来就在这个分组里，不算改动");
        assertTrue(books.setGroup("b1", null), "从有分组变成没分组，是改动");
        assertFalse(books.setGroup("b1", null), "本来就没分组，不算改动");
    }

    @Test
    @DisplayName("空白分组名一律当未分组，不制造筛不出来的幽灵分组")
    void blankGroupIsNormalizedToNull() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        books.setGroup("b1", "   ");

        assertNull(books.groupOf("b1"), "纯空白等于未分组");
        assertTrue(books.groups().isEmpty(), "不该冒出一个空名字的分组");
    }

    @Test
    @DisplayName("分组名前后空格会被去掉，所以「 玄幻 」和「玄幻」是同一组")
    void groupNameIsStripped() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        books.setGroup("b1", "  玄幻  ");

        assertEquals("玄幻", books.groupOf("b1"));
        assertEquals(List.of("玄幻"), books.groups());
    }

    @Test
    @DisplayName("groups() 只列出真有人在用的分组，且按名字排序")
    void groupsListsOnlyUsedOnes() {
        books.save(book("b1", "甲"), 3, progress("b1", 1_000L));
        books.save(book("b2", "乙"), 3, progress("b2", 2_000L));
        books.save(book("b3", "丙"), 3, progress("b3", 3_000L));
        books.setGroup("b1", "仙侠");
        books.setGroup("b2", "玄幻");
        // b3 不归组

        assertEquals(List.of("仙侠", "玄幻"), books.groups().stream().sorted().toList());
    }

    @Test
    @DisplayName("某本书移出分组后，那个分组自动消失")
    void emptyGroupVanishesOnItsOwn() {
        books.save(book("b1", "甲"), 3, progress("b1", 1_000L));
        books.setGroup("b1", "临时");
        assertEquals(List.of("临时"), books.groups());

        books.setGroup("b1", null);

        assertTrue(books.groups().isEmpty(),
                "分组没有独立表，所以没人用就自然查不到了，不需要额外清理");
    }

    @Test
    @DisplayName("按分组筛选：「全部」包含所有书，未分组单独能取")
    void filterByGroup() {
        books.save(book("a", "甲"), 3, progress("a", 1_000L));
        books.save(book("b", "乙"), 3, progress("b", 2_000L));
        books.save(book("c", "丙"), 3, progress("c", 3_000L));
        books.setGroup("a", "玄幻");
        books.setGroup("b", "玄幻");

        assertEquals(3, books.list().size(), "不筛选时是全部");
        assertEquals(List.of("乙", "甲"),
                books.listByGroup("玄幻").stream().map(r -> r.book().title()).toList());
        assertEquals(List.of("丙"), books.listUngrouped().stream()
                .map(r -> r.book().title()).toList());
    }

    @Test
    @DisplayName("listByGroup 传 null / 空串等于不筛选，不会误变成「只看未分组」")
    void listByGroupWithoutFilterReturnsAll() {
        books.save(book("a", "甲"), 3, progress("a", 1_000L));
        books.save(book("b", "乙"), 3, progress("b", 2_000L));

        assertEquals(2, books.listByGroup(null).size());
        assertEquals(2, books.listByGroup("  ").size());
    }

    @Test
    @DisplayName("筛一个不存在的分组得到空列表，而不是全部")
    void unknownGroupYieldsEmpty() {
        books.save(book("a", "甲"), 3, progress("a", 1_000L));

        assertTrue(books.listByGroup("不存在的组").isEmpty());
    }

    @Test
    @DisplayName("分组里带 SQL 通配符也不会被当成模式匹配")
    void groupNameIsNotTreatedAsPattern() {
        books.save(book("a", "甲"), 3, progress("a", 1_000L));
        books.save(book("b", "乙"), 3, progress("b", 2_000L));
        // % 在 LIKE 里是"匹配任意"，但这里是 = 比较，必须当普通字符
        books.setGroup("a", "100%");

        assertEquals(List.of("100%"), books.groups());
        assertEquals(1, books.listByGroup("100%").size());
    }

    // ==================== 阅读时长 ====================

    @Test
    @DisplayName("时长是累加的，多次记录会叠加")
    void readingTimeAccumulates() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        books.addReadingTime("b1", 60_000L);
        books.addReadingTime("b1", 30_000L);
        books.addReadingTime("b1", 90_000L);

        assertEquals(180_000L, books.readingMillis("b1"));
    }

    @Test
    @DisplayName("没记录过时长是 0，而不是报错")
    void readingTimeDefaultsToZero() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        assertEquals(0L, books.readingMillis("b1"));
        assertEquals(0L, books.readingMillis("不存在的书"));
    }

    @Test
    @DisplayName("非正数的时长被忽略，不会把总时长减成负数")
    void nonPositiveDurationIsIgnored() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));
        books.addReadingTime("b1", 60_000L);

        books.addReadingTime("b1", 0L);
        books.addReadingTime("b1", -5_000L);
        books.addReadingTime(null, 1_000L);
        books.addReadingTime("  ", 1_000L);

        assertEquals(60_000L, books.readingMillis("b1"));
    }

    @Test
    @DisplayName("时长冗余字段与明细表一致 —— 两边不能对不上")
    void redundantTotalMatchesSessionRows() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));

        books.addReadingTime("b1", 120_000L);
        books.addReadingTime("b1", 45_000L);

        assertEquals(165_000L, books.readingMillis("b1"),
                "book.reading_millis 是给书架直接读的冗余值，必须和累加结果一致");
    }

    @Test
    @DisplayName("删书会连带删掉它的阅读时长记录")
    void forgettingBookRemovesSessions() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));
        books.addReadingTime("b1", 60_000L);

        books.forget("b1");

        assertEquals(0L, books.readingMillis("b1"));
    }

    // ==================== 显示文本 ====================

    @Test
    @DisplayName("时长标签挑人看得懂的说法，不出现「PT73H」")
    void readingTimeLabel() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1_000L));
        BookStore store = books;

        assertEquals("", labelOf(0L));
        assertEquals("不足 1 分钟", labelOf(30_000L));
        assertEquals("1 分钟", labelOf(60_000L));
        assertEquals("59 分钟", labelOf(59 * 60_000L));
        assertEquals("1 小时", labelOf(3_600_000L));
        assertEquals("1 小时 1 分", labelOf(3_660_000L));
        assertEquals("73 小时 30 分", labelOf((73 * 60 + 30) * 60_000L));
        assertEquals(store.list().size(), 1);
    }

    private String labelOf(long millis) {
        Book entry = book("x", "临时");
        RecentBook item = new RecentBook(entry, new ReadingProgress("x", 0, null, 0, 0L),
                null, millis);
        return item.readingTimeLabel();
    }
}
