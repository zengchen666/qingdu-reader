package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.Bookmark;
import com.qingdu.store.model.ReadingProgress;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BookmarkStore} 的测试。 */
class BookmarkStoreTest {

    private static final String BOOK_ID = "b1";

    @TempDir
    Path tempDir;

    private BookStore books;
    private BookmarkStore bookmarks;

    @BeforeEach
    void setUp() {
        Database database = Database.open(tempDir.resolve("test.db"));
        books = new BookStore(database);
        bookmarks = new BookmarkStore(database);
        // 先把书写进去：bookmark 表有外键指向 book，书不存在时插书签会失败
        books.save(new Book(BOOK_ID, "星尘纪", "某作者", BookFormat.TXT,
                tempDir.resolve("a.txt"), null, 8, Instant.ofEpochMilli(1_000L)),
                8, new ReadingProgress(BOOK_ID, 0, "开篇", 0, 1_000L));
    }

    @Test
    @DisplayName("新增书签后能拿回数据库分配的 id")
    void addAssignsId() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 2, "第二章 远行", 0.25, "这里有伏笔"));

        assertTrue(created.id() > 0, "自增主键应该被回填");
        assertEquals(1, bookmarks.count(BOOK_ID));
    }

    @Test
    @DisplayName("列表按阅读顺序排，不是按添加顺序")
    void listIsOrderedByPosition() {
        // 故意乱序添加
        bookmarks.add(Bookmark.newOne(BOOK_ID, 5, "第五章 归途", 0.1, null));
        bookmarks.add(Bookmark.newOne(BOOK_ID, 1, "第一章 星光落下", 0.8, null));
        bookmarks.add(Bookmark.newOne(BOOK_ID, 1, "第一章 星光落下", 0.2, null));

        List<Bookmark> list = bookmarks.list(BOOK_ID);

        assertEquals(3, list.size());
        assertEquals(1, list.get(0).chapterIndex());
        assertEquals(0.2, list.get(0).scrollRatio(), 1e-9);
        assertEquals(1, list.get(1).chapterIndex());
        assertEquals(0.8, list.get(1).scrollRatio(), 1e-9);
        assertEquals(5, list.get(2).chapterIndex());
    }

    @Test
    @DisplayName("findNear 能把同一个地方认出来（位置是连续量，要按容差比）")
    void findNearMatchesWithinTolerance() {
        bookmarks.add(Bookmark.newOne(BOOK_ID, 3, "第三章 夜访", 0.30, null));

        Optional<Bookmark> near = bookmarks.findNear(BOOK_ID, 3, 0.305);

        assertTrue(near.isPresent());
        assertEquals(0.30, near.get().scrollRatio(), 1e-9);
    }

    @Test
    @DisplayName("差得远的不会误判成同一条")
    void findNearRejectsDistant() {
        bookmarks.add(Bookmark.newOne(BOOK_ID, 3, "第三章 夜访", 0.10, null));

        assertTrue(bookmarks.findNear(BOOK_ID, 3, 0.60).isEmpty());
        // 同一位置但不在同一章，也不算
        assertTrue(bookmarks.findNear(BOOK_ID, 4, 0.10).isEmpty());
    }

    @Test
    @DisplayName("删除按 id 生效，重复删除返回 false")
    void deleteById() {
        Bookmark created = bookmarks.add(Bookmark.newOne(BOOK_ID, 1, "第一章", 0.0, null));

        assertTrue(bookmarks.delete(created.id()));
        assertFalse(bookmarks.delete(created.id()));
        assertEquals(0, bookmarks.count(BOOK_ID));
    }

    @Test
    @DisplayName("空备注会被规整成 null，不会在库里留一堆空串")
    void blankNoteBecomesNull() {
        Bookmark created = bookmarks.add(Bookmark.newOne(BOOK_ID, 1, "第一章", 0.0, "   "));

        assertEquals(null, bookmarks.list(BOOK_ID).get(0).note());
        assertEquals(null, created.note());
    }

    @Test
    @DisplayName("书被移除时，它的书签会被外键级联删掉")
    void bookmarksCascadeOnBookRemoval() {
        bookmarks.add(Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, null));
        bookmarks.add(Bookmark.newOne(BOOK_ID, 2, "第二章", 0.2, null));
        assertEquals(2, bookmarks.count(BOOK_ID));

        books.forget(BOOK_ID);

        // 这条靠的是 SQLite 的 PRAGMA foreign_keys = ON（默认是关的）
        assertEquals(0, bookmarks.count(BOOK_ID));
    }

    @Test
    @DisplayName("给不存在的书加书签会被外键挡住")
    void addForUnknownBookFails() {
        Bookmark orphan = Bookmark.newOne("不存在的书", 1, "第一章", 0.0, null);

        assertThrows(StoreException.class, () -> bookmarks.add(orphan));
        // 这一步顺带证明外键约束是真的生效了，而不是被 SQLite 默认忽略掉
    }

    @Test
    @DisplayName("summary 一行就能说清「在哪、读了多远」")
    void summaryText() {
        Bookmark bookmark = Bookmark.newOne(BOOK_ID, 2, "第二章 远行", 0.425, null);

        assertTrue(bookmark.summary().contains("第二章 远行"));
        assertTrue(bookmark.summary().contains("43%"));
    }

    // ==================== 备注 ====================

    @Test
    @DisplayName("改备注后能读回来")
    void updateNotePersists() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, "旧备注"));

        assertTrue(bookmarks.updateNote(created.id(), "这里埋了伏笔"));

        assertEquals("这里埋了伏笔", bookmarks.list(BOOK_ID).get(0).note());
    }

    @Test
    @DisplayName("改备注不动位置和时间（只该改那一个字段）")
    void updateNoteKeepsOtherFields() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 3, "第三章 夜谈", 0.62, null));

        bookmarks.updateNote(created.id(), "记得回来");

        Bookmark after = bookmarks.list(BOOK_ID).get(0);
        assertEquals(3, after.chapterIndex());
        assertEquals("第三章 夜谈", after.chapterTitle());
        assertEquals(0.62, after.scrollRatio(), 1e-9);
        assertEquals(created.createdAt(), after.createdAt());
    }

    @Test
    @DisplayName("清空备注是合法操作（用户会想做这件事）")
    void noteCanBeCleared() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, "要删掉的备注"));

        assertTrue(bookmarks.updateNote(created.id(), ""));

        String note = bookmarks.list(BOOK_ID).get(0).note();
        assertTrue(note == null || note.isEmpty(), "实际=" + note);
    }

    @Test
    @DisplayName("备注里的首尾空白被去掉")
    void noteIsStripped() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, null));

        bookmarks.updateNote(created.id(), "  这里是重点  \n");

        assertEquals("这里是重点", bookmarks.list(BOOK_ID).get(0).note());
    }

    @Test
    @DisplayName("传 null 备注等于清空，不会抛异常（note 列是 NOT NULL）")
    void nullNoteBecomesEmpty() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, "原备注"));

        assertTrue(bookmarks.updateNote(created.id(), null));

        String note = bookmarks.list(BOOK_ID).get(0).note();
        assertTrue(note == null || note.isEmpty());
    }

    @Test
    @DisplayName("改不存在的书签返回 false，不报错")
    void updateMissingBookmarkReturnsFalse() {
        assertFalse(bookmarks.updateNote(999_999L, "随便写"));
    }

    @Test
    @DisplayName("备注可以写很长（不截断）")
    void longNoteIsKept() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, null));
        String longNote = "伏笔".repeat(500);

        bookmarks.updateNote(created.id(), longNote);

        assertEquals(longNote, bookmarks.list(BOOK_ID).get(0).note());
    }

    @Test
    @DisplayName("多行备注原样保留")
    void multilineNoteIsKept() {
        Bookmark created = bookmarks.add(
                Bookmark.newOne(BOOK_ID, 1, "第一章", 0.1, null));

        bookmarks.updateNote(created.id(), "第一行\n第二行");

        assertEquals("第一行\n第二行", bookmarks.list(BOOK_ID).get(0).note());
    }
}
