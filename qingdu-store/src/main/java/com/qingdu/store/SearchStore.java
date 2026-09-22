package com.qingdu.store;

import com.qingdu.common.util.CjkTokenizer;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchHit;
import com.qingdu.store.model.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * 全文检索：FTS5 索引的建立、失效判断与查询。
 *
 * <p><b>它不是一个"通用搜索引擎"，只是"在一本书里找一串字"。</b>
 * 范围刻意收窄到这个程度，是因为真正难的地方不在架构，而在中文分词 ——
 * 下面几个决定全部来自实测（详见 {@code docs/2026-09-22-fulltext-search-design.md}），
 * 看文档是看不出来的：
 *
 * <ol>
 *   <li><b>必须自己分词。</b>FTS5 默认的 {@code unicode61} 会把连续汉字当成
 *       <b>一个</b> token，"石符"这种子串永远搜不到；内置的 {@code trigram}
 *       按三个字切分，<b>两字查询必然 0 条</b>（而两字人名恰恰是最常见的搜法）。
 *       所以这里用 {@link CjkTokenizer} 做二字滑窗预切分。</li>
 *   <li><b>必须后过滤。</b>bigram 把"云岚宗"拆成 {@code 云岚 岚宗}，
 *       FTS 只保证两个 token <b>都出现</b>，不保证<b>相邻</b>。
 *       「云岚山中有个岚宗派」会被算成命中。而能要求相邻的短语查询
 *       在 {@code detail='none'} 下不可用（实测报错），
 *       所以只能拿原文再 {@code contains} 一次 —— 见 {@link #search}。</li>
 *   <li><b>不存原文。</b>存了索引体积翻倍（69 MB vs 33 MB），
 *       而原文本来就能靠字节偏移读回来，整本只要 83 ms。</li>
 * </ol>
 *
 * <p><b>表为什么不是由 {@link Database} 建的？</b>
 * 检索是可选子系统：没有 FTS5 的环境不该连程序都起不来。
 * 所以这两张表在<b>第一次用到时</b>才建（{@link #ensureSchema}），
 * 建失败就抛 {@link StoreException}，由界面层决定怎么提示。
 */
public class SearchStore {

    /**
     * 分词方案的版本号。
     *
     * <p>它解决一个很隐蔽的问题：<b>换了分词器，老索引就废了</b>。
     * 老索引里写的是旧方案切出来的 token，用新方案去查必然查不准，
     * 而且这种"查不准"不会报错，只会 quietly 少几条结果 —— 最难查的那类 bug。
     * 所以把版本号连同索引一起存下来，对不上就重建。
     */
    public static final int TOKENIZER_VERSION = 1;

    /** 没指定条数时最多返回多少条命中。一本小说里一个常见词能命中上千章。 */
    public static final int DEFAULT_LIMIT = 300;

    /** 每攒这么多章提交一次：批次太小事务开销大，太内存里堆的多。 */
    private static final int BATCH_SIZE = 200;

    /** 摘要里命中词前后各保留多少个字符。 */
    private static final int SNIPPET_RADIUS = 32;

    /** 读不到文件属性时的指纹。保持稳定，避免"每次都重建"。 */
    private static final String UNKNOWN_FINGERPRINT = "unknown";

    private final Database database;

    private volatile boolean schemaReady;

    public SearchStore(Database database) {
        if (database == null) {
            throw new IllegalArgumentException("Database 不能为空");
        }
        this.database = database;
    }

    // ==================== 源文件指纹 ====================

    /**
     * 计算源文件的指纹 —— 用来判断"这本书的内容变了吗，索引要不要重建"。
     *
     * <p>用 <b>文件大小 + 最后修改时间</b>，而不是算内容哈希：
     * 哈希要真读一遍文件（10 MB 起步，还可能更大），而这里只是想在
     * "打开书、决定要不要重建索引"这种高频路径上做个廉价判断。
     * size + mtime 已经足够 ——— 既要大小又要时间都碰巧一样才可能误判，
     * 而且误判的后果只是"用了旧索引"，不是数据错乱。
     *
     * @return 指纹字符串；文件不存在或读不到属性时返回一个固定值
     */
    public static String fingerprint(Path file) {
        if (file == null) {
            return UNKNOWN_FINGERPRINT;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return UNKNOWN_FINGERPRINT;
            }
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return UNKNOWN_FINGERPRINT;
        }
    }

    // ==================== 索引状态 ====================

    /**
     * 这本书的索引是否可用（存在、且指纹与分词器版本都对得上）。
     *
     * <p>两个条件缺一不可：
     * <ul>
     *   <li>指纹不对 —— 用户换了个同名文件，或者重新下载了一版；</li>
     *   <li>分词器版本不对 —— 程序升级后切分规则变了，老索引查不准。</li>
     * </ul>
     * 任何一种情况都要重建，否则用户会搜到"看起来能搜、但结果不对"的东西。
     */
    public boolean isIndexed(String bookId, String fingerprint) {
        if (bookId == null || bookId.isBlank()) {
            return false;
        }
        ensureSchema();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT fingerprint, tokenizer_version FROM search_meta WHERE book_id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return Objects.equals(rs.getString("fingerprint"), fingerprint)
                        && rs.getInt("tokenizer_version") == TOKENIZER_VERSION;
            }
        } catch (SQLException e) {
            throw new StoreException("查询索引状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 已建索引的章节数；没建过返回 {@code -1}。
     *
     * <p>返回 -1 而不是 0 是为了区分"没建索引"和"建了但一本书 0 章" ——
     * 后者虽然罕见，但用 0 表示"没建"会让调用方写出 {@code count > 0} 这种判断，
     * 在边界情况下出错。
     */
    public int indexedChapterCount(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return -1;
        }
        ensureSchema();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT chapter_count FROM search_meta WHERE book_id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new StoreException("查询索引信息失败：" + e.getMessage(), e);
        }
    }

    /** 上次建索引的时间戳（毫秒）；没建过返回 0。 */
    public long indexedAt(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return 0;
        }
        ensureSchema();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexed_at FROM search_meta WHERE book_id = ?")) {
            ps.setString(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new StoreException("查询索引时间失败：" + e.getMessage(), e);
        }
    }

    // ==================== 建索引 ====================

    /**
     * 为整本书建立（或重建）索引。
     *
     * <p><b>为什么整本一次性重建，而不做增量？</b>
     * 建一本 1636 章的索引只要 5 秒，而"只更新变化的章节"需要先判断哪些章变了 ——
     * 章节是按字节偏移定位的，文件一改后面所有章的偏移都可能变，
     * 判断本身就得重新扫一遍全文。增量换不来收益，只换来一堆状态。
     *
     * <p><b>整个过程在一个事务里。</b>建到一半失败（书被删了、磁盘满了）
     * 就整体回滚 —— 用户看到的是"还没建索引"，而不是"建了一半的索引"，
     * 后者会导致搜索结果诡异地少一半。
     *
     * <p><b>前提：这本书必须在 {@code book} 表里。</b>
     * {@code search_meta} 有指向 {@code book(id)} 的外键（这样删书时索引会跟着走），
     * 所以书没入库就建索引会直接抛 {@code FOREIGN KEY constraint failed}。
     * 正常流程里书总是先在书架上（打开/导入时写入），这个前提自然成立。
     *
     * @param bookId      图书 ID
     * @param fingerprint 源文件指纹，见 {@link #fingerprint(Path)}
     * @param documents   每章的原文，按章号升序（顺序不影响检索，但影响进度显示）
     * @param progress    进度回调，参数是"已处理章数"；可以为 {@code null}
     */
    public void index(String bookId, String fingerprint, List<SearchDocument> documents,
                      IntConsumer progress) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("没有可索引的内容");
        }
        if (fingerprint == null) {
            fingerprint = UNKNOWN_FINGERPRINT;
        }
        ensureSchema();

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 先清掉旧索引（重建时旧行必须删干净，否则同一章会出现两次）
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM search_index WHERE book_id = ?")) {
                    ps.setString(1, bookId);
                    ps.executeUpdate();
                }

                int done = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO search_index(book_id, chapter_index, body) VALUES (?, ?, ?)")) {
                    for (SearchDocument doc : documents) {
                        ps.setString(1, bookId);
                        ps.setInt(2, doc.chapterIndex());
                        // 写进去的是【切分后】的 token 串；原文不入库（省一半体积）
                        ps.setString(3, CjkTokenizer.tokenize(doc.text()));
                        ps.addBatch();
                        if (++done % BATCH_SIZE == 0) {
                            ps.executeBatch();
                            if (progress != null) {
                                progress.accept(done);
                            }
                        }
                    }
                    ps.executeBatch();
                }
                if (progress != null) {
                    progress.accept(done);
                }

                upsertMeta(conn, bookId, fingerprint, done);
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new StoreException("建立全文索引失败：" + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StoreException("建立全文索引失败（无法连接数据库）：" + e.getMessage(), e);
        }
    }

    /** 删掉一本书的索引。从书架移除书、或者用户手动清理时调用。 */
    public void drop(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return;
        }
        ensureSchema();
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM search_index WHERE book_id = ?")) {
                ps.setString(1, bookId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM search_meta WHERE book_id = ?")) {
                ps.setString(1, bookId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StoreException("删除全文索引失败：" + e.getMessage(), e);
        }
    }

    /** 清空全部索引。 */
    public void dropAll() {
        ensureSchema();
        try (Connection conn = database.connection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM search_index");
            st.executeUpdate("DELETE FROM search_meta");
        } catch (SQLException e) {
            throw new StoreException("清空全文索引失败：" + e.getMessage(), e);
        }
    }

    // ==================== 查询 ====================

    /**
     * 在一本书里搜一串文字。
     *
     * <p>流程是固定的两步，<b>第二步不能省</b>：
     * <pre>
     *   1. SQL：在 FTS5 里取候选章号（fast，1~14 ms）
     *   2. Java：拿候选章的原文做一次精确子串校验，剔掉假阳性
     * </pre>
     *
     * <p><b>为什么第二步不能省？</b>
     * 搜「云岚宗」时索引里查的是 {@code 云岚 岚宗} 两个 token。
     * 「云岚山中有个岚宗派」两个 token 都有，于是混进候选 ——
     * 但它根本不是用户要找的。能要求"相邻"的短语查询在 {@code detail='none'}
     * 下不可用（实测：{@code fts5: phrase queries are not supported}），
     * 所以只能在 Java 里补这一刀。
     *
     * <p>好消息是这一步几乎不要钱：<b>展示结果本来就要读原文生成摘要</b>，
     * 顺手做个 {@code contains} 而已。而调用方必须先把整本书读进内存
     * （见 {@link ChapterTextSource} 的注释），否则一次查询会退化成上千次随机读。
     *
     * @param bookId  图书 ID
     * @param query   用户输入的查询串；空串或纯标点时返回空结果（不抛异常）
     * @param limit   最多返回几条；{@code <= 0} 时用 {@link #DEFAULT_LIMIT}
     * @param source  原文来源，<b>不能为空</b>
     * @return 结果（含候选数与耗时，便于诊断）
     */
    public SearchResult search(String bookId, String query, int limit, ChapterTextSource source) {
        if (source == null) {
            throw new IllegalArgumentException("缺少原文来源，无法做精确校验");
        }
        String needle = query == null ? "" : query.strip();
        if (bookId == null || bookId.isBlank() || needle.isEmpty()) {
            return new SearchResult(List.of(), 0, false, 0L);
        }

        // 索引侧与查询侧必须用同一个分词器，否则永远搜不到 —— CjkTokenizerTest 钉死了这一点
        String match = CjkTokenizer.tokenizeQuery(needle).trim();
        if (match.isEmpty()) {
            // 全是标点：切不出 token，也就无从检索
            return new SearchResult(List.of(), 0, false, 0L);
        }

        long startedAt = System.nanoTime();
        ensureSchema();
        if (indexedChapterCount(bookId) < 0) {
            // 没建过索引：返回空而不是抛异常 —— 界面层的"懒建索引"要靠这个判断
            return new SearchResult(List.of(), 0, false, 0L);
        }

        List<Integer> candidates = queryCandidates(bookId, match);
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : limit;

        List<SearchHit> hits = new ArrayList<>();
        boolean truncated = false;
        for (int chapterIndex : candidates) {
            if (hits.size() >= effectiveLimit) {
                // 集满就停：剩下的候选**不再做精确校验**。
                // 注意这与"校验了但不合格"是两回事 —— SearchResult.truncated 就是为了
                // 让调用方能区分这两者（第一版验收脚本正是在这里得出过错误结论）。
                truncated = true;
                break;
            }
            String raw = source.textOf(chapterIndex);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            int first = indexOfIgnoreCase(raw, needle);
            if (first < 0) {
                continue; // 假阳性：token 都有，但原文里没有这串字
            }
            hits.add(new SearchHit(chapterIndex, countOccurrences(raw, needle), snippet(raw, needle, first)));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new SearchResult(hits, candidates.size(), truncated, elapsedMs);
    }

    /**
     * 取候选章号（后过滤之前）。
     *
     * <p>SQL 写成 {@code WHERE search_index MATCH ? AND book_id = ?} 而不是给每本书建一张表：
     * 实测在 {@code detail='none'} 下整表 MATCH + book_id 过滤是可用的，
     * 一张表管所有书，省掉"动态建表"这类麻烦事。
     *
     * <p>{@code ORDER BY chapter_index}：结果要按阅读顺序展示。
     */
    private List<Integer> queryCandidates(String bookId, String match) {
        String sql = "SELECT chapter_index FROM search_index "
                + "WHERE search_index MATCH ? AND book_id = ? ORDER BY chapter_index";
        List<Integer> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getInt(1));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("全文检索失败（查询串：" + match + "）：" + e.getMessage(), e);
        }
    }

    // ==================== 文本处理：后过滤与摘要 ====================

    /**
     * 忽略大小写的 {@code indexOf}。
     *
     * <p>只对 ASCII 字母有意义（汉字没有大小写），但必须做：
     * 索引侧的 ASCII token 已经被折叠成小写，FTS5 的匹配是大小写无关的，
     * 如果精确校验这一步区分大小写，就会出现"SQL 说命中、后过滤说没有"的矛盾。
     */
    private static int indexOfIgnoreCase(String haystack, String needle) {
        return indexOfIgnoreCase(haystack, needle, 0);
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (needle.isEmpty() || haystack.length() < needle.length()) {
            return -1;
        }
        char lower = Character.toLowerCase(needle.charAt(0));
        char upper = Character.toUpperCase(needle.charAt(0));
        int max = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            char c = haystack.charAt(i);
            if (c != lower && c != upper) {
                continue;
            }
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** 数一数本章里出现了几次（用于"共 N 处"提示）。 */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int at = indexOfIgnoreCase(haystack.substring(from), needle);
            if (at < 0) {
                break;
            }
            count++;
            from += at + needle.length();
        }
        return count;
    }

    /**
     * 生成命中处上下文摘要。
     *
     * <p>换行一律压成空格：结果列表是一行一行的，
     * 带原文换行会让行高乱掉。
     *
     * @param raw   章节原文
     * @param needle 查询串
     * @param first 第一次命中的下标（由调用方算好传进来，避免重复扫描）
     */
    private static String snippet(String raw, String needle, int first) {
        int from = Math.max(0, first - SNIPPET_RADIUS);
        int to = Math.min(raw.length(), first + needle.length() + SNIPPET_RADIUS);
        StringBuilder sb = new StringBuilder();
        if (from > 0) {
            sb.append('…');
        }
        sb.append(raw, from, to).append('…');
        String text = sb.toString().replace('\n', ' ').replace('\r', ' ');
        // 连续空格压一个：原文里可能本来就有缩进（含全角空格）
        return text.replaceAll("[ \\t\\u3000]{2,}", " ").strip();
    }

    // ==================== 表结构 ====================

    /**
     * 首次用到时建表 + 清理孤儿索引。
     *
     * <p><b>为什么加 {@code detail='none'}</b>：它让 FTS5 不保存 token 的位置信息，
     * 索引体积直接少一半（48 MB → 33 MB）。代价是不能做短语查询和 {@code snippet()} ——
     * 前者本来就用不了（见类注释），后者我们自己做了（见 {@link #snippet}）。
     */
    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            try (Connection conn = database.connection(); Statement st = conn.createStatement()) {
                st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5("
                        + "book_id UNINDEXED, "
                        + "chapter_index UNINDEXED, "
                        + "body, "
                        + "detail = 'none')");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS search_meta (
                            book_id           TEXT    PRIMARY KEY REFERENCES book(id) ON DELETE CASCADE,
                            fingerprint       TEXT    NOT NULL,
                            chapter_count     INTEGER NOT NULL,
                            tokenizer_version INTEGER NOT NULL,
                            indexed_at        INTEGER NOT NULL
                        )""");

                pruneOrphans(conn);
                schemaReady = true;
            } catch (SQLException e) {
                throw new StoreException("全文检索表初始化失败（这个 SQLite 可能没有编译 FTS5）："
                        + e.getMessage(), e);
            }
        }
    }

    /**
     * 清掉"书已经被移除、索引还留着"的孤儿数据。
     *
     * <p>{@code search_meta} 有指向 {@code book(id)} 的外键、级联删除，
     * 所以删书时 meta 会自己没；但 FTS5 是虚拟表，<b>不支持外键</b>，
     * 它的行只能自己动手删。这是"用了虚拟表"必须补的一刀。
     */
    private void pruneOrphans(Connection conn) throws SQLException {
        List<String> orphans = new ArrayList<>();
        String sql = "SELECT m.book_id FROM search_meta m "
                + "LEFT JOIN book b ON b.id = m.book_id WHERE b.id IS NULL";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                orphans.add(rs.getString(1));
            }
        }
        for (String bookId : orphans) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM search_index WHERE book_id = ?")) {
                ps.setString(1, bookId);
                ps.executeUpdate();
            }
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM search_meta WHERE book_id NOT IN (SELECT id FROM book)");
        }
    }

    // ==================== 内部工具 ====================

    private void upsertMeta(Connection conn, String bookId, String fingerprint, int chapterCount)
            throws SQLException {
        String sql = """
                INSERT INTO search_meta (book_id, fingerprint, chapter_count, tokenizer_version, indexed_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(book_id) DO UPDATE SET
                    fingerprint       = excluded.fingerprint,
                    chapter_count     = excluded.chapter_count,
                    tokenizer_version = excluded.tokenizer_version,
                    indexed_at        = excluded.indexed_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bookId);
            ps.setString(2, fingerprint);
            ps.setInt(3, chapterCount);
            ps.setInt(4, TOKENIZER_VERSION);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败已经无能为力，不能让它盖掉真正的异常
        }
    }
}
