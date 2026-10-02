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
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        return list(null, false);
    }

    /**
     * 按分组筛选书库。
     *
     * <p><b>为什么"未分组"要一个专门的参数而不是靠 groupName = null 去理解？</b>
     * {@code groupName = null} 在"筛选未分组"和"不筛选"之间是歧义的 ——
     * SQL 写不出"WHERE group_name IS NULL"和"WHERE 1=1"的统一形式，
     * 靠调用方约定 null 的含义，迟早有人传错。所以拆成两个方法，
     * 各自只做一件事。
     *
     * @param group 分组名；{@code null} 或空串表示"不筛选"
     * @return 该分组下的书（按最后阅读时间倒序）
     */
    public List<RecentBook> listByGroup(String group) {
        String normalized = normalizeGroup(group);
        if (normalized == null) {
            return list();
        }
        return list(normalized, true);
    }

    /** 只看没归组的书。 */
    public List<RecentBook> listUngrouped() {
        return list(null, true);
    }

    /**
     * {@link #list()} 与按分组筛选的共同实现。
     *
     * <p>筛选交给数据库而不是取回来在 Java 里过一遍：分组筛选会改变行数，
     * 先取全量再过滤等于把不该读的列也读了。
     * 代价是 SQL 字符串要动态拼，但可控 —— 分组名是<b>参数</b>不是拼接进去的，
     * 所以不存在注入问题。
     */
    private List<RecentBook> list(String group, boolean filtered) {
        StringBuilder sql = new StringBuilder("SELECT * FROM book");
        if (filtered) {
            sql.append(group == null ? " WHERE group_name IS NULL" : " WHERE group_name = ?");
        }
        sql.append(" ORDER BY last_read_at DESC");

        List<RecentBook> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (filtered && group != null) {
                ps.setString(1, group);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecent(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("读取书架失败：" + e.getMessage(), e);
        }
    }

    // ==================== 分组 ====================

    /**
     * 书库里出现过的全部分组名，按名字排序。
     *
     * <p><b>刻意不建 {@code book_group} 表。</b>分组没有"名字之外的属性"
     * —— 没有颜色、没有排序、没有层级，就是一个标签。
     * 为一个纯粹的标签建一张表，就得额外维护"哪些标签还在用、哪些该删"
     * （否则书库里会出现一个空分组，用户还得能删它）。
     * 直接 {@code SELECT DISTINCT group_name FROM book WHERE group_name IS NOT NULL}
     * 就够了：<b>空分组自动消失</b>，不需要任何清理逻辑。
     *
     * <p>如果将来分组要挂"颜色 / 排序 / 合并"，那时再建表并做一次迁移也不迟。
     */
    public List<String> groups() {
        String sql = "SELECT DISTINCT group_name FROM book "
                + "WHERE group_name IS NOT NULL AND TRIM(group_name) <> '' "
                + "ORDER BY group_name";
        List<String> result = new ArrayList<>();
        try (Connection conn = database.connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("读取分组失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把一本书归到某个分组；传 {@code null} / 空串等于移出分组。
     *
     * @return 是否真的改了（本来就在这个分组里则返回 false，让界面知道不用刷新）
     */
    public boolean setGroup(String bookId, String group) {
        if (bookId == null || bookId.isBlank()) {
            return false;
        }
        String normalized = normalizeGroup(group);
        String current = groupOf(bookId);
        if (Objects.equals(current, normalized)) {
            return false;
        }
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("UPDATE book SET group_name = ? WHERE id = ?")) {
            if (normalized == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, normalized);
            }
            ps.setString(2, bookId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StoreException("设置分组失败：" + e.getMessage(), e);
        }
    }

    /** 这本书当前在哪个分组；没归组返回 {@code null}。 */
    public String groupOf(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return null;
        }
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT group_name FROM book WHERE id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? normalizeGroup(rs.getString(1)) : null;
            }
        } catch (SQLException e) {
            throw new StoreException("查询分组失败：" + e.getMessage(), e);
        }
    }

    /**
     * 分组名的统一规范化：去空白，空串当没有。
     *
     * <p>所有写入口都走它，是为了让"未分组"只有一种表示。
     * 否则用户输入一个空格就能造出一个"看起来有分组、实际筛不出来"的幽灵分组。
     */
    private static String normalizeGroup(String group) {
        if (group == null) {
            return null;
        }
        String stripped = group.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    // ==================== 阅读时长 ====================

    /**
     * 累加一段阅读时长。
     *
     * <p>🔴 <b>为什么不是"设置总时长"而是"累加一段"？</b>
     * 因为时长来自"用户正在读"这个连续过程：开着窗口读 30 分钟、
     * 关掉、隔天再读 40 分钟，就是两次 {@code addReadingTime}。
     * 如果接口是"设置"，调用方就得先读出旧值再写回，
     * 而中间那段时间如果程序退出、旧值就丢了 —— 统计功能最不能接受的就是丢数据。
     * 累加是<b>幂等友好</b>的：每段时长独立写进 {@code reading_session}，
     * 重复提交不会把总时长算错。
     *
     * <p>同时更新 {@code book.reading_millis} 这个冗余字段：
     * 书架要显示每本书的时长，那是"每本书一行"的查询，
     * 每次现算 {@code SUM(millis)} 虽然也快（几十到几百行），
     * 但把值冗余进主表能让书架列表<b>一次查询拿全</b>，不用多一个关联查询。
     * 冗余的代价是"两个地方可能不一致"，所以累加必须在<b>同一个事务</b>里做。
     *
     * @param bookId 书 ID
     * @param millis 本次时长（毫秒）；非正数直接忽略
     */
    public void addReadingTime(String bookId, long millis) {
        if (bookId == null || bookId.isBlank() || millis <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO reading_session(book_id, started_at, ended_at, millis) "
                                + "VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, bookId);
                    ps.setLong(2, now - millis);
                    ps.setLong(3, now);
                    ps.setLong(4, millis);
                    ps.executeUpdate();
                }
                // 表不存在时（比如用户拿一个更老的库直接跑新版本，且迁移被跳过），
                // 增量统计属于"锦上添花"，不该让整个"记录阅读时长"失败
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE book SET reading_millis = reading_millis + ? WHERE id = ?")) {
                    ps.setLong(1, millis);
                    ps.setString(2, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StoreException("记录阅读时长失败：" + e.getMessage(), e);
        }
    }

    /** 这本书的累计阅读时长（毫秒）；没记录过返回 0。 */
    public long readingMillis(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return 0L;
        }
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT reading_millis FROM book WHERE id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0L, rs.getLong(1)) : 0L;
            }
        } catch (SQLException e) {
            throw new StoreException("查询阅读时长失败：" + e.getMessage(), e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败已经无能为力，不能让它盖掉真正的异常
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
     * 一行 → 一本书 + 它的阅读位置 + 分组 + 时长。
     *
     * <p>{@code recent()} / {@code list()} / {@code listByGroup()} 取的是同一张表的全部列、
     * 映射方式完全一样，只有"WHERE 条件和要不要 LIMIT"这几处差别。抽出来是为了让字段对应关系
     * <b>只有一份</b> —— 以后给 book 表加列时，不会出现"书架上有、最近打开里没有"这种不一致。
     *
     * <p>⚠️ {@code group_name} 与 {@code reading_millis} 是 v2 才加的列。
     * 迁移一定在 {@code Database} 初始化时跑完了，所以这里直接读；
     * 但仍然兜一层"读不到就当没有" —— 因为列名拼错时的报错信息
     * （{@code no such column}）指向的是这一行，跟 {@code Database} 里的迁移没关系，
     * 不兜底会让人查错方向。
     */
    private RecentBook mapRecent(ResultSet rs) throws SQLException {
        Book book = mapBook(rs);
        ReadingProgress progress = new ReadingProgress(
                book.id(),
                rs.getInt("last_chapter_index"),
                rs.getString("last_chapter_title"),
                rs.getDouble("last_scroll_ratio"),
                rs.getLong("last_read_at"));
        return new RecentBook(book, progress, optionalString(rs, "group_name"),
                optionalLong(rs, "reading_millis"));
    }

    private static String optionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private static long optionalLong(ResultSet rs, String column) {
        try {
            return rs.getLong(column);
        } catch (SQLException e) {
            return 0L;
        }
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
