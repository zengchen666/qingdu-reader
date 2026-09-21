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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookStore} 的测试。
 *
 * <p>每个测试用一个<b>临时目录里的全新数据库</b>（{@link TempDir}），
 * 所以测试之间互不影响，也不会碰到用户真实的阅读记录 ——
 * 这是把存储层单独拆成一个模块最直接的回报：
 * 不用起界面、不用 mock，就能把数据规则验完。
 */
class BookStoreTest {

    @TempDir
    Path tempDir;

    private BookStore books;

    @BeforeEach
    void setUp() {
        Database database = Database.open(tempDir.resolve("test.db"));
        books = new BookStore(database);
    }

    private Book book(String id, String title) {
        return new Book(id, title, "某作者", BookFormat.TXT,
                tempDir.resolve(title + ".txt"), null, 0, Instant.ofEpochMilli(1_000_000L));
    }

    private ReadingProgress progress(String bookId, int chapter, double ratio, long updatedAt) {
        return new ReadingProgress(bookId, chapter, "第 " + (chapter + 1) + " 章", ratio, updatedAt);
    }

    @Test
    @DisplayName("保存后能按 id 查回图书信息")
    void saveThenFind() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 2, 0.4, 5_000L));

        Optional<Book> found = books.find("b1");

        assertTrue(found.isPresent());
        assertEquals("星尘纪", found.get().title());
        assertEquals("某作者", found.get().author());
        assertEquals(BookFormat.TXT, found.get().format());
        // 章节数是在 save 时一并写进去的
        assertEquals(8, found.get().chapterCount());
    }

    @Test
    @DisplayName("进度能写进去、也能读回来")
    void saveAndReadProgress() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 3, 0.37, 9_000L));

        ReadingProgress read = books.findProgress("b1").orElseThrow();

        assertEquals(3, read.chapterIndex());
        assertEquals(0.37, read.scrollRatio(), 1e-9);
        assertEquals(9_000L, read.updatedAt());
        assertTrue(read.started());
    }

    @Test
    @DisplayName("重复保存会覆盖进度，而不是插出第二行")
    void saveTwiceOverwrites() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 1, 0.1, 1_000L));
        books.save(book("b1", "星尘纪"), 8, progress("b1", 6, 0.9, 2_000L));

        assertEquals(1, books.count());
        assertEquals(6, books.findProgress("b1").orElseThrow().chapterIndex());
    }

    @Test
    @DisplayName("重复保存不会刷新「加入时间」，那是第一次的纪念")
    void addedAtIsPreserved() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 0, 0, 1_000L));
        Instant firstAdded = books.find("b1").orElseThrow().addedAt();

        // 换一个完全不同 addedAt 的 Book 再存一次
        Book later = new Book("b1", "星尘纪", "某作者", BookFormat.TXT,
                tempDir.resolve("x.txt"), null, 8, Instant.ofEpochMilli(7_000_000L));
        books.save(later, 8, progress("b1", 4, 0.5, 2_000L));

        assertEquals(firstAdded, books.find("b1").orElseThrow().addedAt(),
                "added_at 不该被后续写入覆盖");
    }

    @Test
    @DisplayName("saveProgress 只改位置，不动书目信息")
    void saveProgressOnlyTouchesPosition() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 0, 0, 1_000L));

        books.saveProgress(progress("b1", 5, 0.25, 3_000L));

        Book found = books.find("b1").orElseThrow();
        assertEquals("星尘纪", found.title());
        assertEquals(8, found.chapterCount());
        assertEquals(5, books.findProgress("b1").orElseThrow().chapterIndex());
    }

    @Test
    @DisplayName("saveProgress 对不存在的书安静地什么都不做")
    void saveProgressOnUnknownBookIsNoop() {
        // 不抛异常是刻意设计：调用方永远不该因为"书还没登记"而崩掉
        books.saveProgress(progress("missing", 1, 0, 1_000L));

        assertTrue(books.findProgress("missing").isEmpty());
    }

    @Test
    @DisplayName("最近打开按最后阅读时间倒序，并且尊重条数上限")
    void recentOrderingAndLimit() {
        books.save(book("old", "旧书"), 3, progress("old", 1, 0, 1_000L));
        books.save(book("mid", "中间那本"), 3, progress("mid", 1, 0, 2_000L));
        books.save(book("new", "新书"), 3, progress("new", 1, 0, 3_000L));

        List<RecentBook> all = books.recent(10);
        assertEquals(List.of("新书", "中间那本", "旧书"),
                all.stream().map(r -> r.book().title()).toList());

        List<RecentBook> topTwo = books.recent(2);
        assertEquals(2, topTwo.size());
        assertEquals("新书", topTwo.get(0).book().title());
    }

    @Test
    @DisplayName("recent 里能直接拿到进度，不必再查一次")
    void recentCarriesProgress() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 4, 0.6, 1_000L));

        RecentBook item = books.recent(1).get(0);

        assertEquals(4, item.progress().chapterIndex());
        assertEquals(0.6, item.progress().scrollRatio(), 1e-9);
        assertTrue(item.describe().contains("60%"));
    }

    @Test
    @DisplayName("list() 列出全部图书，不受 recent 的条数上限约束")
    void listIsUnlimited() {
        for (int i = 0; i < 20; i++) {
            books.save(book("b" + i, "书" + i), 3, progress("b" + i, 1, 0, 1_000L + i));
        }

        assertEquals(BookStore.DEFAULT_RECENT_LIMIT, books.recent(0).size(),
                "recent 传 0 时按默认上限来");
        assertEquals(20, books.list().size(), "书架要的是全部，不是前 12 本");
    }

    @Test
    @DisplayName("list() 里能直接拿到每本书的进度，不用再查一次")
    void listCarriesProgress() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 4, 0.6, 1_000L));

        RecentBook item = books.list().get(0);

        assertEquals("星尘纪", item.book().title());
        assertEquals(4, item.progress().chapterIndex());
        assertEquals(0.6, item.progress().scrollRatio(), 1e-9);
    }

    @Test
    @DisplayName("list() 和 recent() 的排序口径一致：最近读的在前")
    void listSharesOrderingWithRecent() {
        books.save(book("old", "旧书"), 3, progress("old", 1, 0, 1_000L));
        books.save(book("new", "新书"), 3, progress("new", 1, 0, 3_000L));

        assertEquals(books.recent(10).stream().map(r -> r.book().title()).toList(),
                books.list().stream().map(r -> r.book().title()).toList());
    }

    @Test
    @DisplayName("还没开始读的书，describe 里说的是「尚未开始阅读」")
    void describeForUnstartedBook() {
        books.save(book("b1", "星尘纪"), 8, progress("b1", 0, 0, 1_000L));

        assertTrue(books.recent(1).get(0).describe().contains("尚未开始阅读"));
    }

    @Test
    @DisplayName("forget 删一本，forgetAll 清空")
    void forgetAndForgetAll() {
        books.save(book("b1", "一"), 3, progress("b1", 0, 0, 1_000L));
        books.save(book("b2", "二"), 3, progress("b2", 0, 0, 2_000L));

        assertTrue(books.forget("b1"));
        assertEquals(1, books.count());
        assertFalse(books.forget("b1"), "再删一次应该返回 false");

        assertEquals(1, books.forgetAll());
        assertEquals(0, books.count());
    }

    @Test
    @DisplayName("书名、作者为空时会落到兜底值，不会因为脏数据建不出对象")
    void blankFieldsFallBack() {
        Book messy = new Book("b1", "   ", "  ", BookFormat.TXT,
                tempDir.resolve("a.txt"), null, 3, Instant.ofEpochMilli(1_000L));
        books.save(messy, 3, progress("b1", 0, 0, 1_000L));

        Book found = books.find("b1").orElseThrow();
        assertEquals("未命名", found.title());
        assertTrue(found.authorName().isEmpty());
    }
}
