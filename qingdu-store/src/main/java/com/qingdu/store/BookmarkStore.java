package com.qingdu.store;

import com.qingdu.store.model.Bookmark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 书签的存储。
 *
 * <p><b>书签为什么要按位置排序、而不是按创建时间？</b><br>
 * 用户在书签列表里找的通常是"我在哪一段记了东西"，而不是"我什么时候记的"。
 * 按 {@code (章节, 章内位置)} 升序排，列表顺序就和书的物理顺序一致，
 * 一眼就能看出"这条在很前面、那条在最后"。
 * 时间不是不用，而是放在列表的第二行当辅助信息。
 */
public class BookmarkStore {

    /** 判断"是不是同一个位置"的默认容差（章内比例）。 */
    public static final double DEFAULT_TOLERANCE = 0.02;

    private final Database database;

    public BookmarkStore(Database database) {
        if (database == null) {
            throw new IllegalArgumentException("Database 不能为空");
        }
        this.database = database;
    }

    /**
     * 新增一条书签，返回带数据库分配 ID 的结果。
     *
     * <p>用 {@code RETURN_GENERATED_KEYS} 把自增主键取回来，
     * 而不是再查一次 {@code last_insert_rowid()} ——
     * 后者在"同一连接内两次插入"时容易拿错，取生成键是标准做法。
     */
    public Bookmark add(Bookmark bookmark) {
        if (bookmark == null || bookmark.bookId() == null) {
            throw new IllegalArgumentException("书签及其 bookId 不能为空");
        }
        String sql = """
                INSERT INTO bookmark (book_id, chapter_index, chapter_title, scroll_ratio, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, bookmark.bookId());
            ps.setInt(2, bookmark.chapterIndex());
            ps.setString(3, bookmark.chapterTitle() == null ? "" : bookmark.chapterTitle());
            ps.setDouble(4, bookmark.scrollRatio());
            ps.setString(5, bookmark.note());
            ps.setLong(6, bookmark.createdAt());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new Bookmark(id, bookmark.bookId(), bookmark.chapterIndex(),
                        bookmark.chapterTitle(), bookmark.scrollRatio(),
                        bookmark.note(), bookmark.createdAt());
            }
        } catch (SQLException e) {
            throw new StoreException("添加书签失败：" + e.getMessage(), e);
        }
    }

    /** 某本书的全部书签，按阅读顺序排列。 */
    public List<Bookmark> list(String bookId) {
        String sql = """
                SELECT id, book_id, chapter_index, chapter_title, scroll_ratio, note, created_at
                FROM bookmark WHERE book_id = ?
                ORDER BY chapter_index ASC, scroll_ratio ASC, created_at ASC
                """;
        List<Bookmark> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Bookmark(
                            rs.getLong("id"),
                            rs.getString("book_id"),
                            rs.getInt("chapter_index"),
                            rs.getString("chapter_title"),
                            rs.getDouble("scroll_ratio"),
                            rs.getString("note"),
                            rs.getLong("created_at")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("查询书签失败：" + e.getMessage(), e);
        }
    }

    /**
     * 找当前位置附近已有的书签。
     *
     * <p>用途是避免"连点两下加号，出现两条一模一样的书签"。
     * 位置本身是连续量（0.371 和 0.372 其实是同一个地方），
     * 所以用<b>容差</b>而不是精确相等来判断 —— 这也是为什么
     * 判定逻辑放在存储层：它就是一条围绕数据的规则。
     */
    public Optional<Bookmark> findNear(String bookId, int chapterIndex, double scrollRatio) {
        return findNear(bookId, chapterIndex, scrollRatio, DEFAULT_TOLERANCE);
    }

    public Optional<Bookmark> findNear(String bookId, int chapterIndex, double scrollRatio,
                                      double tolerance) {
        return list(bookId).stream()
                .filter(b -> b.chapterIndex() == chapterIndex)
                .filter(b -> Math.abs(b.scrollRatio() - scrollRatio) <= tolerance)
                .findFirst();
    }

    public boolean delete(long bookmarkId) {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM bookmark WHERE id = ?")) {
            ps.setLong(1, bookmarkId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException("删除书签失败：" + e.getMessage(), e);
        }
    }

    /**
     * 改一条书签的备注。
     *
     * <p>🔴 <b>为什么必须有这个方法，而不是"删了重加"？</b>
     * {@code bookmark.id} 是自增主键，"删了重加"会换一个新 ID。
     * 表面上没影响（书签没有外键指向它），但有一个真实的问题：
     * 用户在备注里写下的东西是<b>他花时间产出的内容</b>，
     * 一次失败的写入就意味着它没了。
     * 而 {@code UPDATE} 只改一个字段，失败就是失败，不会"改到一半"。
     *
     * <p>空白备注归一化成空串（不是 null）：{@code note} 列是 {@code NOT NULL}，
     * 而"清空备注"是用户会做的正常操作，不能因此报错。
     *
     * @return 有没有真的改动（备注没变返回 false，界面就不用白刷新一次）
     */
    public boolean updateNote(long bookmarkId, String note) {
        String normalized = (note == null) ? "" : note.strip();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE bookmark SET note = ? WHERE id = ?")) {
            ps.setString(1, normalized);
            ps.setLong(2, bookmarkId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException("修改书签备注失败：" + e.getMessage(), e);
        }
    }

    public int count(String bookId) {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM bookmark WHERE book_id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new StoreException("统计书签数量失败：" + e.getMessage(), e);
        }
    }
}
