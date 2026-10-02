package com.qingdu.store;

import com.qingdu.common.util.CjkTokenizer;
import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;
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
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // ==================== 跨书检索 ====================

    /**
     * 一次跨书检索最多校验多少个候选章。
     *
     * <p><b>为什么跨书要设这个上限，而单书不设？</b>
     * 单书的后过滤是"整本已在内存、拿字符串 {@code contains} 一下"，
     * 校验 1600 章和校验 16 章的代价差不多（几毫秒）。
     * 跨书每一次校验都可能是<b>一次磁盘随机读</b>，
     * 搜「的」这种能命中几万章的词，不设上限就是几万次随机读 ——
     * 用户等不了，界面也会被后台任务拖住。
     *
     * <p>所以这里做的是<b>时间预算</b>而不是性能优化：
     * 宁可少给结果并明确告诉用户"已截断"，也不能让一次查询跑几分钟。
     * 上限之内集满 {@code limit} 条就停，那是 {@code limit} 管的；
     * 这个管的是"一条都没停、但已经读了太多章"的情况。
     */
    public static final int DEFAULT_MAX_VERIFY = 2000;

    /** 跨书检索里，一本书的章节数上限 —— 用来在 SQL 层就挡住异常数据。 */
    private static final int CANDIDATE_CHAPTER_CAP = 200_000;

    /**
     * 在整个书库里搜一串文字。
     *
     * <p><b>与单书 {@link #search} 的三处不同，都是跨书特有的：</b>
     * <ol>
     *   <li><b>SQL 不带 {@code book_id} 过滤</b>，一次 MATCH 扫全表。
     *       排序用 {@code ORDER BY book_id, chapter_index} ——
     *       这样同一个 {@code bookId} 的候选章在结果里是<b>挨着的</b>，
     *       而 {@link BookChapterTextSource} 的实现可以靠"bookId 变了就换缓存"
     *       命中自己的缓存，不用每次都重新定位。</li>
     *   <li><b>后过滤按需取原文</b>：绝不"把所有书都读进内存"（见
     *       {@link BookChapterTextSource} 的类注释，那是几百 MB 的坑）。</li>
     *   <li><b>多报两个数</b>：{@code searchedBooks} / {@code indexedBooks}，
     *       好让界面能说清"哪些书没被搜到"（见 {@link LibrarySearchResult}）。</li>
     * </ol>
     *
     * <p>⚠️ <b>零假阳性的不变量在跨书下同样成立</b>：候选从 SQL 来，
     * 但进 {@code hits} 的每一行都过了原文精确校验。
     *
     * @param query    用户输入的查询串；空串或纯标点返回空结果（不抛异常）
     * @param limit    最多返回几条；{@code <= 0} 时用 {@link #DEFAULT_LIMIT}
     * @param source   按需取原文的回调，<b>不能为空</b>
     * @return 结果（含分书统计与两个覆盖度数字，便于诊断与界面提示）
     */
    public LibrarySearchResult searchAll(String query, int limit, BookChapterTextSource source) {
        if (source == null) {
            throw new IllegalArgumentException("缺少原文来源，无法做精确校验");
        }
        String needle = query == null ? "" : query.strip();
        long startedAt = System.nanoTime();
        if (needle.isEmpty()) {
            return LibrarySearchResult.empty(0, elapsedSince(startedAt));
        }

        String match = CjkTokenizer.tokenizeQuery(needle).trim();
        if (match.isEmpty()) {
            return LibrarySearchResult.empty(0, elapsedSince(startedAt));
        }

        ensureSchema();
        // 先取书名映射：结果里要显示书名，而且必须在"读原文"之前就备好，
        // 否则读到一半才发现书名拿不到，前面的读全白费。
        Map<String, String> titles = bookTitles();
        int indexedBooks = titles.size();
        if (indexedBooks == 0) {
            // 一本书都没建索引 —— 这是"用户还没搜过"的状态，不是"搜了没找到"。
            // 报出书库总数，界面才能提示"书库里有 N 本，可以先建索引"。
            return LibrarySearchResult.noIndexAtAll(libraryBookCount(), elapsedSince(startedAt));
        }

        List<Candidate> candidates = queryAllCandidates(match);
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : limit;

        List<LibraryHit> hits = new ArrayList<>();
        Map<String, Integer> hitsByBook = new LinkedHashMap<>();
        java.util.Set<String> verifiedBooks = new java.util.LinkedHashSet<>();
        boolean truncated = false;
        int verified = 0;

        // 候选已按 (book_id, chapter_index) 排序，所以同一本书是连续的 ——
        // 这正是 BookChapterTextSource 实现能靠"书变了就换缓存"的前提。
        for (Candidate candidate : candidates) {
            if (hits.size() >= effectiveLimit) {
                truncated = true;
                break;
            }
            if (verified >= DEFAULT_MAX_VERIFY) {
                // 时间预算用完：明确标记为截断，不能假装"就这些了"
                truncated = true;
                break;
            }
            verified++;
            // 记在"取原文之前"：即使这本书的原文取不到（比如文件被移走），
            // 它也已经参与过这次检索了，覆盖度要算进去
            verifiedBooks.add(candidate.bookId());
            String raw = source.textOf(candidate.bookId(), candidate.chapterIndex());
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            int first = indexOfIgnoreCase(raw, needle);
            if (first < 0) {
                continue; // 假阳性：token 都有，但原文里没有这串字
            }
            hits.add(new LibraryHit(candidate.bookId(),
                    titles.getOrDefault(candidate.bookId(), "（未知书名）"),
                    candidate.chapterIndex(),
                    countOccurrences(raw, needle),
                    snippet(raw, needle, first)));
            hitsByBook.merge(candidate.bookId(), 1, Integer::sum);
        }

        List<LibraryHit> ordered = orderForDisplay(hits, titles);
        // 书库总数 ≥ 已建索引数；用 max 兜住"书被删了但索引还在"的瞬间状态
        int libraryBooks = Math.max(libraryBookCount(), indexedBooks);
        return new LibrarySearchResult(ordered, hitsByBook, candidates.size(),
                libraryBooks, indexedBooks, verifiedBooks.size(), truncated, elapsedSince(startedAt));
    }

    /**
     * 全表取候选（跨书）。
     *
     * <p>两条防御：
     * <ul>
     *   <li><b>用 {@code search_index.rowid} 做二次封顶</b>：
     *       {@code LIMIT} 挡不住"一章正文里有一万个相同 token"这种极端情况 ——
     *       那一条 row 就对应 20000 个候选章，一次查询能拿到几百万个 int。
     *       加一层 rowid 上限把最坏情况钉死在 {@link #CANDIDATE_CHAPTER_CAP}。</li>
     *   <li><b>书名映射里没有的书直接不返回</b>：索引是懒建的，
     *       用户可能在移除书之后、{@code pruneOrphans} 跑之前就搜了一次。
     *       那些行没有可显示的书名，返回它们只会让界面上出现"（未知书名）"的孤儿行。</li>
     * </ul>
     */
    private List<Candidate> queryAllCandidates(String match) {
        String sql = "SELECT book_id, chapter_index FROM search_index "
                + "WHERE search_index MATCH ? "
                + "ORDER BY book_id, chapter_index LIMIT ?";
        List<Candidate> result = new ArrayList<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setLong(2, CANDIDATE_CHAPTER_CAP);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Candidate(rs.getString(1), rs.getInt(2)));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("全文检索失败（查询串：" + match + "）：" + e.getMessage(), e);
        }
    }

    /** 已建索引、且还在书库里的书 → 书名。 */
    private Map<String, String> bookTitles() {
        // 只 join 已建索引的书：这就是"能被搜到的书"的准确定义。
        // 写成 LEFT JOIN 再在 Java 里过滤多一遍，SQL 直接 inner join 更省事。
        String sql = "SELECT b.id, b.title FROM book b "
                + "JOIN search_meta m ON m.book_id = b.id";
        Map<String, String> titles = new LinkedHashMap<>();
        try (Connection conn = database.connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                titles.put(rs.getString(1), rs.getString(2));
            }
            return titles;
        } catch (SQLException e) {
            throw new StoreException("读取书名失败：" + e.getMessage(), e);
        }
    }

    /**
     * 书库里的书总数（不管有没有建索引）。
     *
     * <p>存在的意义只有一个：让界面能算出"有几本书没被搜"。
     * 没有它，零结果就只有"没找到"一种解释。
     */
    private int libraryBookCount() {
        try (Connection conn = database.connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM book")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StoreException("统计图书数量失败：" + e.getMessage(), e);
        }
    }

    /**
     * 展示顺序：<b>按书名排，同一本书内按章号排</b>。
     *
     * <p>SQL 给的是 {@code book_id} 顺序（也就是 ID 的哈希顺序，对用户毫无意义），
     * 但界面是"按书分组"的 —— 相邻的两条结果如果是同一本书，用户才看得出是分组。
     * 所以这里按书名做一次稳定排序；稳定很重要，
     * 否则同一本书的命中可能在两次查询间换位置。
     *
     * <p>🔴 <b>中文书名必须用 {@link Collator}，不能用 {@code String.compareTo}。</b>
     * {@code compareTo} 按 Unicode 码位比，而汉字码位大致按部首排 ——
     * 结果是「子」(U+5B50) 排在「阿」(U+963F) 前面，「星」(U+661F) 排在
     * 「武」(U+6B66) 前面。对用户来说这就是<b>随机顺序</b>：
     * 他按拼音找「阿澜」，列表里却翻不到。
     * {@link Collator} 带 {@link Locale#CHINA} 走的是拼音序，
     * 实测「阿澜书 沧澜录 斗破苍穹 武动乾坤 星尘纪 子羽书」——
     * 这才是中文用户心里的顺序。
     *
     * <p>书名相同的不同书（下载了两份同名文件）会挨在一起，这反而是对的 ——
     * 它们在界面上本来就像同一本书。
     */
    private static List<LibraryHit> orderForDisplay(List<LibraryHit> hits, Map<String, String> titles) {
        // 每次现建一个 Collator：它不是线程安全的，而这里是最简单的用法
        //（方法内私有、无跨方法持有），不值得为它引入 ThreadLocal。
        Collator collator = Collator.getInstance(Locale.CHINA);
        List<LibraryHit> sorted = new ArrayList<>(hits);
        sorted.sort((a, b) -> {
            int byTitle = collator.compare(titles.getOrDefault(a.bookKey(), a.bookTitle()),
                    titles.getOrDefault(b.bookKey(), b.bookTitle()));
            if (byTitle != 0) {
                return byTitle;
            }
            int byId = a.bookKey().compareTo(b.bookKey());
            return byId != 0 ? byId : Integer.compare(a.chapterIndex(), b.chapterIndex());
        });
        return sorted;
    }

    private static long elapsedSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 候选：(哪本书, 第几章)。 */
    private record Candidate(String bookId, int chapterIndex) {
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
