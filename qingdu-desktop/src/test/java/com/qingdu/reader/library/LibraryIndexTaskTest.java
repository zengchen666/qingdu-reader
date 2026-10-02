package com.qingdu.reader.library;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.QingduStore;
import com.qingdu.store.model.ReadingProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LibraryIndexTask} 的测试。
 *
 * <p>它要解决的是全库检索最容易让人困惑的一件事：
 * <b>"为什么搜不到"到底是因为没有这个词，还是因为这本书根本没被搜过？</b>
 * 这个任务就是用来把后者变成一个可见、可取消的进度过程的。
 *
 * <p>所以重点测三件事：① 覆盖度算得对（哪些书算"待建"）；
 * ② 一本失败不拖累下一本；③ 取消能立刻生效且不留半截索引。
 */
class LibraryIndexTaskTest {

    @TempDir
    Path tempDir;

    private QingduStore store;
    private LibraryIndexTask task;

    @BeforeEach
    void setUp() {
        store = QingduStore.open(tempDir.resolve("test.db"));
        task = new LibraryIndexTask(store);
    }

    /** 造一本三章的书并登记进书库（只登记，不建索引）。 */
    private Book addBook(String bookId, String title) throws IOException {
        StringBuilder sb = new StringBuilder("测试书籍\n作者 某人\n\n");
        for (int i = 1; i <= 3; i++) {
            sb.append("第").append(chineseNumber(i)).append("章 标题").append(i).append('\n');
            sb.append("这是第").append(i).append("章的正文，云岚宗的山门在这里。").append('\n');
        }
        Path file = tempDir.resolve(bookId + ".txt");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        Book book = new Book(bookId, title, "某作者", BookFormat.TXT,
                file, null, 3, Instant.ofEpochMilli(1_000L));
        store.books().save(book, 3, new ReadingProgress(bookId, 0, null, 0, 1_000L));
        return book;
    }

    private static String chineseNumber(int n) {
        return switch (n) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            default -> String.valueOf(n);
        };
    }

    @Test
    @DisplayName("没建过索引的书都算待建")
    void pendingCountCoversEverything() throws IOException {
        addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");

        assertEquals(2, task.pendingCount());
        assertEquals(2, task.pendingBooks().size());
    }

    @Test
    @DisplayName("建过的书不再算待建（重建要重读整本书，纯浪费）")
    void alreadyIndexedIsNotPending() throws IOException {
        addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");

        task.run(null, null);

        assertEquals(0, task.pendingCount(),
                "第二次打开全库检索时不该再让用户等一遍建索引");
    }

    @Test
    @DisplayName("文件已经不在的书不算待建（否则这个数字永远降不下来）")
    void missingFileIsNotPending() throws IOException {
        addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");
        Files.delete(tempDir.resolve("b2.txt"));

        assertEquals(1, task.pendingCount());
        assertEquals(List.of("星尘纪"), task.pendingBooks().stream().map(Book::title).toList());
    }

    @Test
    @DisplayName("批量建索引：全部成功")
    void buildsAllIndexes() throws IOException {
        addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");
        addBook("b3", "武动乾坤");

        LibraryIndexTask.Report report = task.run(null, null);

        assertEquals(3, report.succeeded());
        assertEquals(0, report.failed());
        assertFalse(report.cancelled());
        assertTrue(report.isCompleteSuccess());
        assertEquals(0, task.pendingCount());
    }

    @Test
    @DisplayName("建完索引之后真的能搜到（这是整个功能的验收点）")
    void booksAreSearchableAfterIndexing() throws IOException {
        addBook("b1", "星尘纪");
        task.run(null, null);

        try (var source = task.newTextSource()) {
            var result = store.search().searchAll("云岚宗", 0, source);

            assertEquals(3, result.hitCount(), "三章正文里都有'云岚宗'");
            assertEquals(1, result.indexedBooks());
            assertEquals(0, result.unindexedBooks());
        }
    }

    @Test
    @DisplayName("建索引时报进度，每本一次，最后一次是收尾")
    void reportsProgressPerBook() throws IOException {
        addBook("b1", "甲");
        addBook("b2", "乙");
        List<String> seen = new ArrayList<>();

        task.run(null, (done, total, current) ->
                seen.add(done + "/" + total + (current == null ? "" : " " + current.title())));

        assertEquals(3, seen.size(), "两本书 → 两次进度 + 一次收尾");
        assertEquals("0/2 甲", seen.get(0));
        assertEquals("1/2 乙", seen.get(1));
        assertEquals("2/2", seen.get(2), "最后一次 current 为 null 表示收尾");
    }

    @Test
    @DisplayName("中途取消：立刻停下，且如实报告 cancelled")
    void cancelStopsEarly() throws IOException {
        for (int i = 0; i < 5; i++) {
            addBook("b" + i, "书" + i);
        }
        // 处理到第 2 本时喊停
        LibraryIndexTask task2 = new LibraryIndexTask(store);
        task2.run(null, (done, total, current) -> {
            if (done == 2) {
                task2.cancel();
            }
        });

        assertTrue(task2.isCancelled());
        assertTrue(task.pendingCount() > 0, "还有书没建索引 —— 用户随时可以接着建");
    }

    @Test
    @DisplayName("开跑前就取消：一本都不建")
    void cancelBeforeStart() throws IOException {
        addBook("b1", "星尘纪");
        LibraryIndexTask task2 = new LibraryIndexTask(store);
        task2.cancel();

        LibraryIndexTask.Report report = task2.run(null, null);

        assertEquals(0, report.succeeded());
        assertTrue(report.cancelled());
        assertEquals(1, task2.pendingCount());
    }

    @Test
    @DisplayName("一本建成之后才算数：取消时已建的那本是完整可搜的")
    void completedBooksStaySearchableAfterCancel() throws IOException {
        addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");
        LibraryIndexTask task2 = new LibraryIndexTask(store);
        task2.run(null, (done, total, current) -> {
            if (done == 1) {
                task2.cancel();
            }
        });

        // b1 建完了，它必须真的能搜 —— 半截索引比没有索引更糟
        try (var source = task.newTextSource()) {
            var result = store.search().searchAll("云岚宗", 0, source);
            assertEquals(1, result.indexedBooks());
            assertEquals(1, result.unindexedBooks());
        }
    }

    @Test
    @DisplayName("没有章节标题的文件仍能建成索引（整本当一章）")
    void titlelessFileIsStillIndexable() throws IOException {
        // 一个常见的真实情形：有些 TXT 根本没有"第 N 章"这种行。
        // 解析器会把它整本当成一章 —— 依然可搜，所以不该被跳过或报失败。
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "随手记的东西\n第二行也是随手记的\n", StandardCharsets.UTF_8);
        Book book = new Book("plain", "随手记", null, BookFormat.TXT,
                file, null, 1, Instant.ofEpochMilli(1_000L));
        store.books().save(book, 1, new ReadingProgress("plain", 0, null, 0, 1_000L));

        LibraryIndexTask.Report report = task.run(null, null);

        assertEquals(1, report.succeeded());
        assertEquals(0, report.failed());
        try (var source = task.newTextSource()) {
            assertEquals(1, store.search().searchAll("随手记", 0, source).hitCount());
        }
    }

    @Test
    @DisplayName("0 字节文件：建出一个空索引，并且从「待建」里消失")
    void emptyFileGetsAnIndexAndLeavesThePendingList() throws IOException {
        // 🔴 判据是 pendingCount() 归零，不是 succeeded/skipped 的数字。
        //
        // 解析器对空文件会产出一个「全文」章节（偏移 0~0），
        // 所以它是**能**建索引的，真去跳过它反而会出事：
        // pendingBooks() 靠 isIndexed 过滤"待建"，跳过了就永远建不上，
        // 于是这一本会**永远**留在待建清单里 ——
        // 界面上就是"还有 1 本待建"，而用户拿这一本毫无办法。
        // 这正是类注释里说的"数字永远降不下来"。
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        Book book = new Book("empty", "空文件", null, BookFormat.TXT,
                file, null, 0, Instant.ofEpochMilli(1_000L));
        store.books().save(book, 0, new ReadingProgress("empty", 0, null, 0, 1_000L));

        assertEquals(1, task.pendingCount(), "先确认它确实待建，否则下面这个断言没意义");

        LibraryIndexTask.Report report = task.run(null, null);

        assertEquals(1, report.succeeded());
        assertEquals(0, report.failed(), "0 字节不是错误，用户没做错任何事");
        assertEquals(0, task.pendingCount(),
                "建完就该从待建清单里消失；留着会让用户看到一个永远降不下来的数字");
    }

    @Test
    @DisplayName("全是空白的文件同样建得出索引（别把「没内容」当成「出错」）")
    void blankContentIsNotAFailure() throws IOException {
        Path file = tempDir.resolve("blank.txt");
        Files.writeString(file, "\n\n   \n", StandardCharsets.UTF_8);
        Book book = new Book("blank", "空白书", null, BookFormat.TXT,
                file, null, 1, Instant.ofEpochMilli(1_000L));
        store.books().save(book, 1, new ReadingProgress("blank", 0, null, 0, 1_000L));

        LibraryIndexTask.Report report = task.run(null, null);

        assertEquals(1, report.succeeded());
        assertEquals(0, report.failed());
        assertEquals(0, task.pendingCount());
    }

    @Test
    @DisplayName("只给指定的书建索引")
    void canBuildSubset() throws IOException {
        Book b1 = addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");

        LibraryIndexTask.Report report = task.run(List.of(b1), null);

        assertEquals(1, report.succeeded());
        assertEquals(List.of("沧澜录"),
                task.pendingBooks().stream().map(Book::title).toList(),
                "没被指定的书应该还是待建状态");
    }

    @Test
    @DisplayName("建索引途中文件被删：算跳过，不影响后面的书")
    void fileDeletedMidRunIsSkipped() throws IOException {
        Book b1 = addBook("b1", "星尘纪");
        addBook("b2", "沧澜录");

        // 在"决定建哪些"之后、"真的建"之前把 b1 的文件删掉 ——
        // 这正是"用户正在整理文件夹"时会发生的时序
        List<Book> targets = task.pendingBooks();
        Files.delete(tempDir.resolve("b1.txt"));

        LibraryIndexTask.Report report = task.run(targets, null);

        assertEquals(1, report.succeeded(), "b2 不该被 b1 的意外连累");
        assertEquals(1, report.skipped());
        assertEquals(0, report.failed());
        assertEquals("星尘纪", b1.title());
    }

    @Test
    @DisplayName("空书库：跑一遍不报错，也建不出什么")
    void emptyLibrary() {
        LibraryIndexTask.Report report = task.run(null, null);

        assertEquals(0, report.succeeded());
        assertEquals(0, report.failed());
        assertTrue(report.isCompleteSuccess());
        assertEquals(0, task.pendingCount());
    }

    @Test
    @DisplayName("store 为 null 直接拒绝构造（别等跑起来才 NPE）")
    void rejectsNullStore() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryIndexTask(null));
    }

    @Test
    @DisplayName("路径指向目录而不是文件：不算待建（那里根本没有书可读）")
    void directoryIsNotPending() throws IOException {
        Path dir = tempDir.resolve("adir.txt");
        Files.createDirectory(dir);
        Book book = new Book("bad", "假书", null, BookFormat.TXT,
                dir, null, 1, Instant.ofEpochMilli(1_000L));
        store.books().save(book, 1, new ReadingProgress("bad", 0, null, 0, 1_000L));

        assertEquals(0, task.pendingCount(),
                "isRegularFile 会把它挡掉；混进待建列表只会让批量任务报错");
    }
}
