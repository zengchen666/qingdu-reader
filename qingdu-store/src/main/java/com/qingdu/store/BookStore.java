package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.RecentBook;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 图书与阅读进度的存储。
 *
 * <p>对外只暴露一个写入方法 {@link #save}，它把"书的信息"和"读到哪了"
 * <b>一次性写进同一行</b>。这是刻意的：
 * 如果拆成 {@code saveBook()} 和 {@code saveProgress()} 两个方法，
 * 调用方就必须记得按顺序调、还要保证书先存在（否则 UPDATE 影响 0 行、
 * 进度静默丢失）。合成一个方法之后，无论谁先谁后、书是否存在，
 * 结果都是对的 —— <b>把"必须遵守的调用顺序"从文档里移到代码里</b>，
 * 这是消除一整类 bug 的常用手法。
 */
public class BookStore {

    private final Database database;

    public BookStore(Database database) {
        if (database == null) {
            throw new IllegalArgumentException("Database 不能为空");
        }
        this.database = database;
    }

    /** 最近打开列表默认最多取这么多本。 */
    public static final int DEFAULT_RECENT_LIMIT = 12;

    private static final String UPSERT_SQL = """
            INSERT INTO book (
                id, path, title, author, format, chapter_count, added_at,
                last_chapter_index, last_chapter_title, last_scroll_ratio, last_read_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                path               = excluded.path,
                title              = excluded.title,
                author             = excluded.author,
                format             = excluded.format,
                chapter_count      = excluded.chapter_count,
                last_chapter_index = excluded.last_chapter_index,
                last_chapter_title = excluded.last_chapter_title,
                last_scroll_ratio  = excluded.last_scroll_ratio,
                last_read_at       = excluded.last_read_at
            """;

    /**
     * 写入一本书的元信息 + 当前阅读位置。
     *
     * <p>{@code ON CONFLICT ... DO UPDATE} 是 SQLite 的"更新或插入"
     * （别的数据库叫 UPSERT / MERGE）。{@code excluded} 是个特殊别名，
     * 代表"本来打算插入的那一行"，用它可以方便地把新值搬到旧行上。
     *
     * <p>注意 {@code added_at} <b>故意没有出现在 DO UPDATE 列表里</b>：
     * 它记录的是"我第一次把这本书加入书架的时间"，重复保存时不应该被刷新，
     * 否则这个字段就退化成了 last_read_at。
     *
     * @param book         图书元信息
     * @param chapterCount 识别出的章节总数
     * @param progress     当前阅读位置
     */
    public void save(Book book, int chapterCount, ReadingProgress progress) {
        if (book == null || book.id() == null) {
            throw new IllegalArgumentException("book 及其 id 不能为空");
        }
        ReadingProgress p = (progress == null)
                ? new ReadingProgress(book.id(), 0, null, 0, System.currentTimeMillis())
                : progress;

        long now = System.currentTimeMillis();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, book.id());
            ps.setString(2, BookStore.pathOf(book));
            ps.setString(3, book.title());
            ps.setString(4, book.author());
            ps.setString(5, book.format() == null ? BookFormat.TXT.name() : book.format().name());
            ps.setInt(6, Math.max(0, chapterCount));
            // 新书用"现在"作为加入时间；已存在的行不会被这个值覆盖（见 SQL）
            ps.setLong(7, book.addedAt() == null ? now : book.addedAt().toEpochMilli());
            ps.setInt(8, p.chapterIndex());
            ps.setString(9, p.chapterTitle());
            ps.setDouble(10, p.scrollRatio());
            ps.setLong(11, p.updatedAt() == 0 ? now : p.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("保存阅读记录失败：" + e.getMessage(), e);
        }
    }

    /** 只更新阅读位置，书的信息用库里已有的。书不存在时静默忽略。 */
    public void saveProgress(ReadingProgress progress) {
        if (progress == null || progress.bookId() == null) {
            return;
        }
        String sql = """
                UPDATE book SET
                    last_chapter_index = ?,
                    last_chapter_title = ?,
                    last_scroll_ratio  = ?,
                    last_read_at       = ?
                WHERE id = ?
                """;
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, progress.chapterIndex());
            ps.setString(2, progress.chapterTitle());
            ps.setDouble(3, progress.scrollRatio());
            ps.setLong(4, progress.updatedAt());
            ps.setString(5, progress.bookId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("更新阅读进度失败：" + e.getMessage(), e);
        }
    }

    public Optional<Book> find(String bookId) {
        String sql = "SELECT * FROM book WHERE id = ?";
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBook(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StoreException("查询图书失败：" + e.getMessage(), e);
        }
    }

    public Optional<ReadingProgress> findProgress(String bookId) {
        String sql = "SELECT last_chapter_index, last_chapter_title, last_scroll_ratio, last_read_at "
                + "FROM book WHERE id = ?";
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReadingProgress(
                        bookId,
                        rs.getInt("last_chapter_index"),
                        rs.getString("last_chapter_title"),
                        rs.getDouble("last_scroll_ratio"),
                        rs.getLong("last_read_at")));
            }
        } catch (SQLException e) {
            throw new StoreException("查询阅读进度失败：" + e.getMessage(), e);
        }
    }

    /**
     * 最近打开的书，按最后阅读时间倒序。
     *
     * <p>排序直接交给数据库（配合 {@code ix_book_last_read} 索引），
     * 而不是取出来再用 Java 排 —— 后者在书多的时候要白读一遍全部行。
     */
    public List<RecentBook> recent(int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_RECENT_LIMIT : limit;
        String sql = "SELECT * FROM book ORDER BY last_read_at DESC LIMIT ?";
        List<RecentBook> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, effectiveLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecent(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("查询最近打开失败：" + e.getMessage(), e);
        }
    }

    /**
     * 书库里的全部图书，最近读（或最近入库）的排在前面。
     *
     * <p>和 {@link #recent} 的区别只有一个：<b>不设条数上限</b>。
     * 之所以单独给一个方法而不是让调用方传 {@code Integer.MAX_VALUE}：
     * "最近打开的 12 本"和"书架上所有的书"是两件不同的事，
     * 前者是菜单，后者是书架 —— 混用一个 API 会让后来改的人以为它们是一回事。
     *
     * <p>排序沿用 {@code last_read_at}：导入一本书时会顺手记一次"当前时间"，
     * 所以刚导入的书会浮到最前面，正好是用户想看到的位置。
     */
    public List<RecentBook> list() {
        String sql = "SELECT * FROM book ORDER BY last_read_at DESC";
        List<RecentBook> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRecent(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("读取书架失败：" + e.getMessage(), e);
        }
    }

    /** 把一本书从记录里移除（连同它的书签，靠外键级联）。 */
    public boolean forget(String bookId) {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM book WHERE id = ?")) {
            ps.setString(1, bookId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException("删除图书记录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 清空全部阅读记录（书籍文件本身不动）。
     *
     * @return 清掉了几行
     */
    public int forgetAll() {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM book")) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("清空阅读记录失败：" + e.getMessage(), e);
        }
    }

    public int count() {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM book");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StoreException("统计图书数量失败：" + e.getMessage(), e);
        }
    }

    // ==================== 行 → 对象 ====================

    /**
     * 一行 → 一本书 + 它的阅读位置。
     *
     * <p>{@code recent()} 和 {@code list()} 取的是同一张表的全部列、映射方式完全一样，
     * 只有"要不要 LIMIT"这一处差别。抽出来是为了让这两处的字段对应关系<b>只有一份</b> ——
     * 以后给 book 表加列时，不会出现"书架上有、最近打开里没有"这种不一致。
     */
    private RecentBook mapRecent(ResultSet rs) throws SQLException {
        Book book = mapBook(rs);
        ReadingProgress progress = new ReadingProgress(
                book.id(),
                rs.getInt("last_chapter_index"),
                rs.getString("last_chapter_title"),
                rs.getDouble("last_scroll_ratio"),
                rs.getLong("last_read_at"));
        return new RecentBook(book, progress);
    }

    private Book mapBook(ResultSet rs) throws SQLException {
        long addedAt = rs.getLong("added_at");
        return new Book(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("author"),
                parseFormat(rs.getString("format")),
                Path.of(rs.getString("path")),
                null,
                rs.getInt("chapter_count"),
                addedAt == 0 ? Instant.now() : Instant.ofEpochMilli(addedAt));
    }

    /** 数据库里的格式字符串可能来自旧版本，认不出就按 TXT 处理。 */
    private BookFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return BookFormat.TXT;
        }
        try {
            return BookFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BookFormat.UNKNOWN;
        }
    }

    static String pathOf(Book book) {
        return book.filePath() == null ? "" : book.filePath().toString();
    }
}
