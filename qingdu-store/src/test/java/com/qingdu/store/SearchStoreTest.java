package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchHit;
import com.qingdu.store.model.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchStore} 的集成测试 —— 用真实的 SQLite（临时目录里的文件）跑，
 * 不走内存数据库，也不 mock。
 *
 * <p><b>为什么坚持用真库？</b>
 * 这一层的全部价值就在于"SQLite FTS5 到底认不认我们塞进去的东西"：
 * {@code detail='none'} 支不支持整表 MATCH、短语查询会不会报错、
 * DELETE 带不带得动 WHERE —— 这些全是<b>数据库行为</b>，
 * 用 mock 全都测不出来，而且答错的方向往往和直觉相反。
 *
 * <p>其中"假阳性"那一节用的是<b>构造数据</b>：真实小说语料里碰巧没出现过
 * 候选 != 精确的情况，但那是运气，不是设计上可以省 ——
 * 把它写成用例，后过滤万一被"顺手优化"掉就会立刻红。
 */
class SearchStoreTest {

    private static final String BOOK_ID = "b1";
    private static final String FINGERPRINT = "12345:1700000000000";

    @TempDir
    Path tempDir;

    private Database database;
    private BookStore books;
    private SearchStore search;

    /** 索引时的原文，同时充当查询时的原文来源。 */
    private final List<String> raws = new ArrayList<>();

    @BeforeEach
    void setUp() {
        database = Database.open(tempDir.resolve("test.db"));
        books = new BookStore(database);
        search = new SearchStore(database);
        // search_meta 有指向 book 的外键，书必须先在
        books.save(new Book(BOOK_ID, "星尘纪", "某作者", BookFormat.TXT,
                        tempDir.resolve("a.txt"), null, 0, Instant.ofEpochMilli(1_000L)),
                0, new ReadingProgress(BOOK_ID, 0, null, 0, 1_000L));
        raws.clear();
    }

    /** 造若干章并建索引。 */
    private void index(String... chapters) {
        List<SearchDocument> docs = new ArrayList<>();
        for (int i = 0; i < chapters.length; i++) {
            raws.add(chapters[i]);
            docs.add(new SearchDocument(i, chapters[i]));
        }
        search.index(BOOK_ID, FINGERPRINT, docs, null);
    }

    /** 查询时的原文来源：模拟"把整本书读进内存后按章切片"。 */
    private final ChapterTextSource source = i -> (i >= 0 && i < raws.size()) ? raws.get(i) : "";

    private SearchResult find(String query) {
        return search.search(BOOK_ID, query, 0, source);
    }

    @Nested
    @DisplayName("基础检索")
    class Basic {

        @Test
        @DisplayName("建索引后能查到已知词")
        void findsKnownWord() {
            index("林动手中的石符，忽然散发出一阵微弱的光。",
                    "他走向云岚宗的大门。",
                    "这一章讲的是别的事情。");

            SearchResult result = find("石符");

            assertEquals(1, result.hitCount());
            assertEquals(0, result.hits().get(0).chapterIndex());
        }

        @Test
        @DisplayName("两字词必须能查到 —— 这是 trigram 方案的死穴")
        void findsTwoCharWord() {
            index("药老微微一笑", "萧炎点了点头");

            assertEquals(1, find("药老").hitCount());
            assertEquals(1, find("萧炎").hitCount());
        }

        @Test
        @DisplayName("三字词能查到（靠两个相邻二元组命中）")
        void findsThreeCharWord() {
            index("云岚宗的炼药师大会");

            assertEquals(1, find("云岚宗").hitCount());
            assertEquals(1, find("炼药师").hitCount());
        }

        @Test
        @DisplayName("结果按章节序号升序，不是按命中次数")
        void hitsAreOrderedByChapter() {
            index("萧炎", "萧炎萧炎萧炎", "萧炎");

            List<SearchHit> hits = find("萧炎").hits();

            assertEquals(List.of(0, 1, 2), hits.stream().map(SearchHit::chapterIndex).toList());
            assertEquals(3, hits.get(1).hitCount(), "同一章里的命中次数要单独统计");
        }

        @Test
        @DisplayName("摘要里必须带上命中词本身")
        void snippetContainsQuery() {
            index("前面是一大段铺垫文字，".repeat(3) + "关键是异火出现了" + "，后面又是一大段。".repeat(3));

            SearchHit hit = find("异火").hits().get(0);

            assertTrue(hit.snippet().contains("异火"));
            assertTrue(hit.snippet().startsWith("…"), "截断处要有省略号");
        }
    }

    @Nested
    @DisplayName("后过滤（假阳性）")
    class PostFilter {

        /**
         * 这一章和下一章都含「云岚」「岚宗」两个 token，但只有第 0 章是真的「云岚宗」。
         * 探针 8 实测：SQL 候选 [0,1]，精确 [0]。
         */
        @Test
        @DisplayName("三字词的两个 token 分处两处时必须被剔除")
        void removesFalsePositive() {
            index("他走向云岚宗的大门。",
                    "云岚山中有个岚宗派。");

            SearchResult result = find("云岚宗");

            assertEquals(2, result.candidateCount(), "SQL 阶段两个都是候选");
            assertEquals(1, result.hitCount(), "后过滤之后只剩真命中");
            assertEquals(0, result.hits().get(0).chapterIndex());
            assertFalse(result.truncated(), "只有两个候选，没触发上限");
            assertEquals(1, result.candidateCount() - result.hitCount());
        }

        @Test
        @DisplayName("真命中不能被后过滤误杀")
        void keepsTruePositive() {
            index("云岚山中有个岚宗派。", "他走向云岚宗的大门。", "云岚宗宗主");

            assertEquals(2, find("云岚宗").hitCount());
            assertEquals(List.of(1, 2), find("云岚宗").hits().stream()
                    .map(SearchHit::chapterIndex).toList());
        }

        @Test
        @DisplayName("原文校验用的是原文，不是切分后的 token 串")
        void verifiesAgainstRawText() {
            // 切分后是「云岚 岚宗」，如果拿切分结果去 contains("云岚宗") 必然 0 命中 ——
            // 这个坑探针自己踩过一次，留个用例防它回来
            index("云岚宗");

            assertEquals(1, find("云岚宗").hitCount());
        }
    }

    @Nested
    @DisplayName("索引状态与失效")
    class Staleness {

        @Test
        @DisplayName("没建索引时查询返回空，不抛异常（界面层靠这个触发懒建索引）")
        void searchWithoutIndexReturnsEmpty() {
            SearchResult result = find("云岚宗");

            assertTrue(result.hits().isEmpty());
            assertEquals(0, result.candidateCount());
        }

        @Test
        @DisplayName("指纹一致时认为索引可用")
        void isIndexedWhenFingerprintMatches() {
            index("云岚宗");
            assertTrue(search.isIndexed(BOOK_ID, FINGERPRINT));
        }

        @Test
        @DisplayName("源文件变了（指纹不同）索引就算失效")
        void isStaleWhenFingerprintDiffers() {
            index("云岚宗");

            assertFalse(search.isIndexed(BOOK_ID, "99999:1700000000000"));
        }

        @Test
        @DisplayName("重建索引后能查到新内容，旧内容查不到")
        void reindexReplacesOldContent() {
            index("这一章只有石符");
            assertEquals(1, find("石符").hitCount());

            raws.clear();
            raws.add("这一章换成了异火");
            search.index(BOOK_ID, "67890:1700000000001",
                    List.of(new SearchDocument(0, raws.get(0))), null);

            assertEquals(0, find("石符").hitCount(), "旧 token 必须被删干净");
            assertEquals(1, find("异火").hitCount());
        }

        @Test
        @DisplayName("分词器版本对不上时索引失效 —— 换分词方案不能悄悄用老索引")
        void isStaleWhenTokenizerVersionDiffers() throws Exception {
            index("云岚宗");
            assertTrue(search.isIndexed(BOOK_ID, FINGERPRINT));

            // 手工把版本号改成一个"未来"的值，模拟程序升级后切分规则变了
            try (Connection conn = database.connection(); Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE search_meta SET tokenizer_version = 999 "
                        + "WHERE book_id = '" + BOOK_ID + "'");
            }

            assertFalse(search.isIndexed(BOOK_ID, FINGERPRINT));
        }

        @Test
        @DisplayName("删除索引后查不到")
        void dropRemovesIndex() {
            index("云岚宗");
            search.drop(BOOK_ID);

            assertEquals(0, find("云岚宗").hitCount());
            assertEquals(-1, search.indexedChapterCount(BOOK_ID));
        }

        @Test
        @DisplayName("清空索引后所有书都查不到")
        void dropAllRemovesEverything() {
            index("云岚宗");
            search.dropAll();

            assertEquals(0, find("云岚宗").hitCount());
        }
    }

    @Nested
    @DisplayName("输入边界")
    class InputEdges {

        @Test
        @DisplayName("空查询与空白查询返回空结果")
        void blankQueryReturnsEmpty() {
            index("云岚宗");

            assertEquals(0, find("").hitCount());
            assertEquals(0, find("   ").hitCount());
            assertEquals(0, find(null).hitCount());
        }

        @Test
        @DisplayName("纯标点查询返回空结果（切不出 token）")
        void punctuationOnlyQueryReturnsEmpty() {
            index("云岚宗");

            assertEquals(0, find("。，！？").hitCount());
        }

        @Test
        @DisplayName("查不到的词返回空，不是报错")
        void missingWordReturnsEmpty() {
            index("云岚宗");

            assertEquals(0, find("不存在的词").hitCount());
        }

        @Test
        @DisplayName("条数上限生效")
        void limitIsRespected() {
            index("萧炎", "萧炎", "萧炎", "萧炎", "萧炎");

            assertEquals(2, search.search(BOOK_ID, "萧炎", 2, source).hitCount());
        }

        @Test
        @DisplayName("集满上限要报 truncated，剩下的候选算「未校验」而不是「假阳性」")
        void truncatedIsReportedSeparately() {
            // 这条用例来自真实语料上踩过的坑：拿候选数减命中数当假阳性，
            // 会把「还没查的」误报成「查了不合格的」（实录见 SearchProbe 的报告）
            index("萧炎", "萧炎", "萧炎", "萧炎", "萧炎");

            SearchResult capped = search.search(BOOK_ID, "萧炎", 2, source);

            assertEquals(2, capped.hitCount());
            assertTrue(capped.truncated(), "5 个候选只处理了 2 个，必须标成截断");
            assertEquals(3, capped.unchecked());

            SearchResult full = search.search(BOOK_ID, "萧炎", Integer.MAX_VALUE, source);

            assertFalse(full.truncated());
            assertEquals(5, full.hitCount());
            assertEquals(0, full.unchecked(), "全量查询不该有未校验的候选");
            assertEquals(0, full.candidateCount() - full.hitCount(), "没有上限时，候选差就是真假阳性");
        }

        @Test
        @DisplayName("缺少原文来源时直接报错，而不是跳过精确校验")
        void requiresTextSource() {
            index("云岚宗");

            assertThrows(IllegalArgumentException.class,
                    () -> search.search(BOOK_ID, "云岚宗", 0, null));
        }
    }

    @Nested
    @DisplayName("非中文与混排")
    class MixedContent {

        @Test
        @DisplayName("ASCII 词能查到，且大小写不敏感")
        void findsAsciiCaseInsensitively() {
            index("用 JavaFX 做界面", "SQLite 的 FTS5 模块");

            assertEquals(1, find("JavaFX").hitCount());
            assertEquals(1, find("javafx").hitCount(), "索引侧折叠成小写，查询侧也要能命中");
            assertEquals(1, find("FTS5").hitCount());
        }

        @Test
        @DisplayName("中英混排各自都能查")
        void findsMixed() {
            index("这一章提到了 GB18030 编码");

            assertEquals(1, find("编码").hitCount());
            assertEquals(1, find("gb18030").hitCount());
        }
    }

    @Nested
    @DisplayName("多本书互不干扰")
    class MultiBook {

        @Test
        @DisplayName("同一张表里两本书的结果不会串")
        void resultsAreScopedByBook() {
            // 第二本书也要先在 book 表里，外键才过得去
            books.save(new Book("b2", "另一本", null, BookFormat.TXT,
                            tempDir.resolve("b.txt"), null, 0, Instant.ofEpochMilli(2_000L)),
                    0, new ReadingProgress("b2", 0, null, 0, 2_000L));
            index("第一本书里的云岚宗");
            List<String> secondRaws = List.of("第二本书里的云岚宗");
            search.index("b2", "fp-b2", List.of(new SearchDocument(0, secondRaws.get(0))), null);

            SearchResult inFirst = find("云岚宗");
            SearchResult inSecond = search.search("b2", "云岚宗", 0,
                    i -> i == 0 ? secondRaws.get(0) : "");

            assertEquals(1, inFirst.hitCount());
            assertEquals(1, inSecond.hitCount());
            assertEquals(2, inFirst.hitCount() + inSecond.hitCount(),
                    "两张表/两本书的索引是分开的，各查到各的");
        }
    }

    @Nested
    @DisplayName("建索引")
    class Indexing {

        @Test
        @DisplayName("进度回调最终会报出总章数")
        void progressReportsTotalCount() {
            List<Integer> progress = new ArrayList<>();
            List<SearchDocument> docs = new ArrayList<>();
            for (int i = 0; i < 450; i++) {
                docs.add(new SearchDocument(i, "第" + i + "章的内容，云岚宗"));
            }
            search.index(BOOK_ID, FINGERPRINT, docs, progress::add);

            assertEquals(450, progress.get(progress.size() - 1));
            assertEquals(450, search.indexedChapterCount(BOOK_ID));
        }

        @Test
        @DisplayName("空文档列表直接报错，而不是建出一个空索引")
        void rejectsEmptyDocuments() {
            assertThrows(IllegalArgumentException.class,
                    () -> search.index(BOOK_ID, FINGERPRINT, List.of(), null));
        }
    }
}
