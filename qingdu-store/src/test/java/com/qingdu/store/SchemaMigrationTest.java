package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.RecentBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1 → v2 的 schema 迁移测试。
 *
 * <p><b>为什么单独一个文件，而且要手工造一个"老库"？</b>
 * 因为迁移代码最危险的地方<b>只在老库上才暴露</b>：
 * 新装的库总是走"建基线表 → 迁移"这条路径，
 * 而真实用户库里那张 {@code book} 表是<b>v0.2.1 亲手建的</b>，
 * 它没有 {@code group_name} 列。如果迁移逻辑写错（比如无脑 {@code ALTER}），
 * 新库测试<b>全绿</b>，而真实用户一升级就崩 —— 库坏了、书没了。
 *
 * <p>所以这里用 JDBC 手工建出 v1 形态的库（模拟"用户从 v0.2.1 升级上来"），
 * 再让 {@link Database#open} 去处理它。
 */
class SchemaMigrationTest {

    @TempDir
    Path tempDir;

    /**
     * 手工建一个 v1 形态的库：结构和 {@code createBaselineTables} 一字不差，
     * 但<b>没有</b> {@code group_name} / {@code reading_millis}，
     * 并且 {@code user_version = 1}。
     */
    private void createLegacyV1Database(Path dbFile) throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE book (
                        id                 TEXT    PRIMARY KEY,
                        path               TEXT    NOT NULL,
                        title              TEXT    NOT NULL,
                        author             TEXT,
                        format             TEXT    NOT NULL DEFAULT 'TXT',
                        chapter_count      INTEGER NOT NULL DEFAULT 0,
                        added_at           INTEGER NOT NULL,
                        last_chapter_index INTEGER NOT NULL DEFAULT 0,
                        last_chapter_title TEXT,
                        last_scroll_ratio  REAL    NOT NULL DEFAULT 0,
                        last_read_at       INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE bookmark (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        book_id       TEXT    NOT NULL REFERENCES book(id) ON DELETE CASCADE,
                        chapter_index INTEGER NOT NULL,
                        chapter_title TEXT    NOT NULL,
                        scroll_ratio  REAL    NOT NULL DEFAULT 0,
                        note          TEXT,
                        created_at    INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE setting (
                        key        TEXT PRIMARY KEY,
                        value      TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
            // 塞一条真实数据：迁移要是把它弄丢了，这个测试就白搭了
            st.executeUpdate("INSERT INTO book(id, path, title, author, format, chapter_count,"
                    + " added_at, last_chapter_index, last_chapter_title, last_scroll_ratio, last_read_at)"
                    + " VALUES ('old1', 'D:/novels/星尘纪.txt', '星尘纪', '某作者', 'TXT', 1636,"
                    + " 1600000000000, 42, '第四十三章', 0.37, 1600000000000)");
            st.executeUpdate("INSERT INTO bookmark(book_id, chapter_index, chapter_title,"
                    + " scroll_ratio, note, created_at)"
                    + " VALUES ('old1', 42, '第四十三章', 0.37, '这里的伏笔', 1600000000000)");
            st.executeUpdate("PRAGMA user_version = 1");
        }
    }

    @Test
    @DisplayName("v1 老库升级后，版本号变成 2，新列出现")
    void legacyDatabaseIsUpgraded() throws SQLException {
        Path dbFile = tempDir.resolve("legacy.db");
        createLegacyV1Database(dbFile);

        Database database = Database.open(dbFile);
        BookStore books = new BookStore(database);

        assertEquals(2, database.schemaVersion(), "迁移后应该自报版本 2");
        assertNull(books.list().get(0).groupName(), "新列存在、默认值是未分组");
        assertEquals(0L, books.readingMillis("old1"), "新列的默认值是 0");
    }

    @Test
    @DisplayName("迁移保留老数据：书、进度、书签一个都不能少")
    void migrationPreservesExistingData() throws SQLException {
        Path dbFile = tempDir.resolve("legacy.db");
        createLegacyV1Database(dbFile);

        Database database = Database.open(dbFile);
        BookStore books = new BookStore(database);

        List<RecentBook> all = books.list();
        assertEquals(1, all.size());
        RecentBook book = all.get(0);
        assertEquals("星尘纪", book.book().title());
        assertEquals("某作者", book.book().author());
        assertEquals(1636, book.book().chapterCount());
        assertEquals(42, book.progress().chapterIndex());
        assertEquals("第四十三章", book.progress().chapterTitle());
        assertEquals(0.37, book.progress().scrollRatio(), 1e-9);

        // 书签在另一张表、由另一个 Store 管；迁移漏掉它同样是"数据没了"
        List<com.qingdu.store.model.Bookmark> marks = new BookmarkStore(database).list("old1");
        assertEquals(1, marks.size());
        assertEquals("这里的伏笔", marks.get(0).note());
    }

    @Test
    @DisplayName("迁移后的库能立刻用新功能：归组和时长都写得进去")
    void migratedDatabaseAcceptsNewWrites() throws SQLException {
        Path dbFile = tempDir.resolve("legacy.db");
        createLegacyV1Database(dbFile);

        BookStore books = new BookStore(Database.open(dbFile));
        books.setGroup("old1", "玄幻");
        books.addReadingTime("old1", 90_000L);

        RecentBook reloaded = books.list().get(0);
        assertEquals("玄幻", reloaded.groupName());
        assertEquals(90_000L, reloaded.readingMillis());
    }

    @Test
    @DisplayName("reading_session 表建出来了，且外键级联生效")
    void sessionTableIsCreated() throws SQLException {
        Path dbFile = tempDir.resolve("legacy.db");
        createLegacyV1Database(dbFile);
        BookStore books = new BookStore(Database.open(dbFile));
        books.addReadingTime("old1", 60_000L);

        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reading_session")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "每段时长都要留一行明细");
        }
    }

    @Test
    @DisplayName("重复打开不会重复迁移，也不会把已有数据重置掉")
    void migrationIsIdempotent() throws SQLException {
        Path dbFile = tempDir.resolve("legacy.db");
        createLegacyV1Database(dbFile);

        BookStore first = new BookStore(Database.open(dbFile));
        first.setGroup("old1", "玄幻");
        first.addReadingTime("old1", 60_000L);

        // 模拟"程序重启三次"
        for (int i = 0; i < 3; i++) {
            assertEquals(2, Database.open(dbFile).schemaVersion());
        }

        RecentBook book = new BookStore(Database.open(dbFile)).list().get(0);
        assertEquals("玄幻", book.groupName(), "重启不该把分组清掉");
        assertEquals(60_000L, book.readingMillis(), "重启不该把时长清零");
    }

    @Test
    @DisplayName("崩在 ALTER 之后、user_version 之前的半成品库也能自愈")
    void halfMigratedDatabaseRecovers() throws SQLException {
        Path dbFile = tempDir.resolve("half.db");
        createLegacyV1Database(dbFile);
        // 模拟"上次已经加了 group_name，但还没来得及写 user_version 就被杀了"
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE book ADD COLUMN group_name TEXT");
        }

        // 关键：无脑 ALTER 会在这里撞上 duplicate column name 而炸掉
        Database database = Database.open(dbFile);
        BookStore books = new BookStore(database);

        assertEquals(2, database.schemaVersion());
        assertEquals(0L, books.readingMillis("old1"), "另一半迁移也补上了");
    }

    @Test
    @DisplayName("全新库也走同一条路径，直接就是版本 2")
    void freshDatabaseIsAlreadyV2() {
        Database database = Database.open(tempDir.resolve("fresh.db"));

        assertEquals(2, database.schemaVersion());
    }

    @Test
    @DisplayName("全新库里新列立刻可用，不需要任何手动操作")
    void freshDatabaseHasWorkingNewColumns() {
        BookStore books = new BookStore(Database.open(tempDir.resolve("fresh.db")));
        Book book = new Book("b1", "星尘纪", "某作者", BookFormat.TXT,
                tempDir.resolve("a.txt"), null, 3, Instant.ofEpochMilli(1_000L));
        books.save(book, 3, new ReadingProgress("b1", 1, "第二章", 0.1, 2_000L));

        books.setGroup("b1", "玄幻");
        books.addReadingTime("b1", 3_600_000L);

        RecentBook item = books.list().get(0);
        assertEquals("玄幻", item.groupName());
        assertEquals("1 小时", item.readingTimeLabel());
    }

    @Test
    @DisplayName("删除的列不会留下孤儿数据：书没了，分组统计也跟着少一个")
    void groupCountFollowsBookRemoval() {
        BookStore books = new BookStore(Database.open(tempDir.resolve("test.db")));
        Book book = new Book("b1", "甲", "某作者", BookFormat.TXT,
                tempDir.resolve("a.txt"), null, 3, Instant.ofEpochMilli(1_000L));
        books.save(book, 3, new ReadingProgress("b1", 0, null, 0, 1_000L));
        books.setGroup("b1", "玄幻");
        assertEquals(1, books.listByGroup("玄幻").size());

        books.forget("b1");

        assertTrue(books.listByGroup("玄幻").isEmpty());
        assertTrue(books.groups().isEmpty());
    }

    @Test
    @DisplayName("把还没归组的书归到组里，list() 里能立刻看到")
    void groupAppearsInPlainList() {
        BookStore books = new BookStore(Database.open(tempDir.resolve("test.db")));
        Book book = new Book("b1", "甲", "某作者", BookFormat.TXT,
                tempDir.resolve("a.txt"), null, 3, Instant.ofEpochMilli(1_000L));
        books.save(book, 3, new ReadingProgress("b1", 0, null, 0, 1_000L));
        assertFalse(books.list().get(0).grouped());

        books.setGroup("b1", "玄幻");

        assertTrue(books.list().get(0).grouped());
    }
}
