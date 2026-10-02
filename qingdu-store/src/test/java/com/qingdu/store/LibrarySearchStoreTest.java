package com.qingdu.store;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.SearchDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨书检索（{@link SearchStore#searchAll}）的集成测试。
 *
 * <p><b>为什么单书测试不够？</b>
 * v0.2.0 的验收里有一条铁律：<b>零假阳性</b> —— FTS5 的
 * {@code detail='none'} 只出候选，正确性靠拿原文再校验一次。
 * 单书场景下这件事几乎是白送的：整本书已经在内存里，
 * 校验就是一次 {@code String.contains()}。
 *
 * <p>跨书就变了：没有"整本在内存"这个前提，校验要<b>按需去磁盘取原文</b>，
 * 于是多出三个全新的失效点：
 * <ol>
 *   <li>取到的原文<b>不是索引时的那一份</b>（编码/清洗不一致 → 假阳性漏网或假阴性）；</li>
 *   <li>取原文失败（文件被移走）时被当成"不命中"，用户看不到任何提示；</li>
 *   <li>结果被上限截断后，<b>覆盖度数字撒谎</b> —— 用户以为 30 本都搜过了。</li>
 * </ol>
 * 这一类就是把它们逐个钉住的地方。
 */
class LibrarySearchStoreTest {

    private static final String FP = "1:1700000000000";

    @TempDir
    Path tempDir;

    private Database database;
    private BookStore books;
    private SearchStore search;

    /** 每本书的章节原文，键是 bookId —— 模拟磁盘上的书。 */
    private final Map<String, List<String>> texts = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        database = Database.open(tempDir.resolve("lib.db"));
        books = new BookStore(database);
        search = new SearchStore(database);
        texts.clear();
    }

    /** 登记一本书并建索引；{@code chapters} 同时充当"磁盘上的原文"。 */
    private void addBook(String bookId, String title, String... chapters) {
        books.save(new Book(bookId, title, "某作者", BookFormat.TXT,
                        tempDir.resolve(bookId + ".txt"), null, 0, Instant.ofEpochMilli(1_000L)),
                chapters.length, new ReadingProgress(bookId, 0, null, 0, 1_000L));
        List<String> raws = new ArrayList<>(List.of(chapters));
        texts.put(bookId, raws);
        List<SearchDocument> docs = new ArrayList<>();
        for (int i = 0; i < chapters.length; i++) {
            docs.add(new SearchDocument(i, chapters[i]));
        }
        search.index(bookId, FP, docs, null);
    }

    /** 只登记进书库、不建索引（模拟"用户没搜过这本书"）。 */
    private void addBookWithoutIndex(String bookId, String title) {
        books.save(new Book(bookId, title, "某作者", BookFormat.TXT,
                        tempDir.resolve(bookId + ".txt"), null, 0, Instant.ofEpochMilli(1_000L)),
                10, new ReadingProgress(bookId, 0, null, 0, 1_000L));
    }

    private final BookChapterTextSource source = BookChapterTextSource.ofSnapshot(texts);

    private LibrarySearchResult find(String query) {
        return search.searchAll(query, 0, source);
    }

    @Nested
    @DisplayName("跨书命中")
    class Basics {

        @Test
        @DisplayName("一次查询能同时命中多本书，每条都带书名")
        void hitsAcrossBooks() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门", "山门之外，风雪交加");
            addBook("b2", "沧澜录", "云岚宗的弟子低头行礼", "海潮拍岸，卷起千堆雪");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(2, r.hitCount(), "两本书各一章命中");
            assertEquals(2, r.hitsByBook().size());
            assertTrue(r.hits().stream().anyMatch(h -> h.bookTitle().equals("星尘纪")));
            assertTrue(r.hits().stream().anyMatch(h -> h.bookTitle().equals("沧澜录")));
        }

        @Test
        @DisplayName("结果按书名拼音排序、书内按章号排序")
        void orderedByTitleThenChapter() {
            // bookId 的字典序与书名顺序刻意相反：验证排序依据是书名而不是 ID
            addBook("zzz", "阿澜书", "云岚宗甲", "云岚宗乙", "云岚宗丙");
            addBook("aaa", "子羽书", "云岚宗丁");

            List<LibraryHit> hits = find("云岚宗").hits();

            // 「阿」拼音 a 在「子」拼音 z 之前。用码点比会反过来（子 U+5B50 < 阿 U+963F），
            // 那正是这个用例要守住的东西
            assertEquals("阿澜书", hits.get(0).bookTitle());
            assertEquals("子羽书", hits.get(3).bookTitle());
            assertEquals(List.of(0, 1, 2),
                    hits.subList(0, 3).stream().map(LibraryHit::chapterIndex).toList(),
                    "同一本书内必须按章号递增");
        }

        @Test
        @DisplayName("书名按拼音排而不是按 Unicode 码位排")
        void sortsByPinyinNotCodePoint() {
            addBook("a1", "子羽书", "云岚宗一");
            addBook("a2", "阿澜书", "云岚宗二");
            addBook("a3", "星尘纪", "云岚宗三");
            addBook("a4", "沧澜录", "云岚宗四");

            List<String> titles = find("云岚宗").hits().stream()
                    .map(LibraryHit::bookTitle).toList();

            assertEquals(List.of("阿澜书", "沧澜录", "星尘纪", "子羽书"), titles,
                    "中文书名必须按拼音排：码位序会把「子」排到「阿」前面");
        }

        @Test
        @DisplayName("命中摘要里含原词")
        void snippetContainsQuery() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门，山风扑面");

            LibraryHit hit = find("云岚宗").hits().get(0);

            assertTrue(hit.snippet().contains("云岚宗"), "实际=" + hit.snippet());
            assertEquals(1, hit.hitCount());
        }

        @Test
        @DisplayName("跨书统计命中次数")
        void countsOccurrencesPerChapter() {
            addBook("b1", "星尘纪", "云岚宗、云岚宗、又是云岚宗");

            LibraryHit hit = find("云岚宗").hits().get(0);

            assertEquals(3, hit.hitCount());
        }
    }

    @Nested
    @DisplayName("零假阳性不变量")
    class ZeroFalsePositive {

        @Test
        @DisplayName("token 都命中但原文没有整串 → 判为假阳性并剔除")
        void removesFalsePositive() {
            // ⚠️ 「云岚」和「岚宗」必须在<b>同一章里</b>：FTS5 是按行匹配的，
            // 分到两章的话 SQL 根本不会把任何一章当候选（两个 token 是 AND 关系），
            // 那样这个用例就测不到后过滤了。构造数据时踩过一次这个坑。
            addBook("b1", "星尘纪", "他走向星尘纪的大门", "云岚山中有个岚宗派");
            addBook("b2", "沧澜录", "他正式拜入云岚宗");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(2, r.candidateCount(), "SQL 阶段两章都是候选");
            assertEquals(1, r.hitCount(), "后过滤之后只剩真命中");
            assertEquals("沧澜录", r.hits().get(0).bookTitle());
        }

        @Test
        @DisplayName("全部命中都是假阳性时返回零结果，而不是返回一个看起来像命中的东西")
        void allFalsePositiveYieldsEmpty() {
            addBook("b1", "星尘纪", "云岚山中有个岚宗派", "另有岚宗旧碑、云海茫茫");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(0, r.hitCount());
            assertTrue(r.candidateCount() > 0, "候选不为零，说明 SQL 侧确实有匹配");
            assertEquals(0, r.unchecked(), "全查完了，没有未校验的候选");
        }

        @Test
        @DisplayName("取原文失败时不算命中，但书要计入覆盖度")
        void missingRawTextStillCountsAsSearched() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门");
            // b2 已建索引，但原文取不到（文件被移走）
            addBook("b2", "沧澜录", "云岚宗的弟子低头行礼");
            BookChapterTextSource broken = new BookChapterTextSource() {
                @Override
                public String textOf(String bookId, int chapterIndex) {
                    return "b2".equals(bookId) ? null : texts.get(bookId).get(chapterIndex);
                }
            };

            LibrarySearchResult r = search.searchAll("云岚宗", 0, broken);

            assertEquals(1, r.hitCount());
            assertTrue(r.hits().stream().noneMatch(h -> h.bookTitle().equals("沧澜录")));
            assertEquals(2, r.searchedBooks(),
                    "b2 的原文取不到，但它确实参与过这次检索，覆盖度要算进去");
        }
    }

    @Nested
    @DisplayName("覆盖度数字")
    class Coverage {

        @Test
        @DisplayName("一本书都没建索引时，报出书库总数而不是只说'没找到'")
        void reportsLibrarySizeWhenNoIndexAtAll() {
            addBookWithoutIndex("b1", "星尘纪");
            addBookWithoutIndex("b2", "沧澜录");
            addBookWithoutIndex("b3", "山海志");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(0, r.hitCount());
            assertEquals(3, r.libraryBooks(), "界面要靠它提示'书库里有 3 本，可以先建索引'");
            assertEquals(0, r.indexedBooks());
            assertEquals(3, r.unindexedBooks());
        }

        @Test
        @DisplayName("部分书建了索引时，未建索引的那部分要能被算出来")
        void reportsUnindexedCount() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门");
            addBookWithoutIndex("b2", "沧澜录");
            addBookWithoutIndex("b3", "山海志");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(1, r.hitCount());
            assertEquals(3, r.libraryBooks());
            assertEquals(1, r.indexedBooks());
            assertEquals(2, r.unindexedBooks(),
                    "只搜了 1 本却说'没找到'，用户会以为另外 2 本也没有 —— 沉默的错误答案");
        }

        @Test
        @DisplayName("书库里只有已建索引的书时，未建索引数为 0")
        void unindexedIsZeroWhenAllIndexed() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门");
            addBook("b2", "沧澜录", "云岚宗的弟子低头行礼");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(0, r.unindexedBooks());
            assertEquals(0, r.skippedBooks());
        }

        @Test
        @DisplayName("结果被条数上限截断时 searchedBooks 小于 indexedBooks")
        void searchedBooksShrinksWhenTruncated() {
            addBook("b1", "书甲", "云岚宗之一", "云岚宗之二", "云岚宗之三");
            addBook("b2", "书乙", "云岚宗之四");

            LibrarySearchResult r = search.searchAll("云岚宗", 2, source);

            assertTrue(r.truncated());
            assertEquals(2, r.hitCount());
            assertEquals(2, r.indexedBooks());
            assertEquals(1, r.searchedBooks(), "只校验了 1 本的书的候选就满了");
            assertEquals(1, r.skippedBooks());
        }
    }

    @Nested
    @DisplayName("截断与边界")
    class Truncation {

        @Test
        @DisplayName("truncated 时 unchecked 是'没看'而不是'不合格'")
        void uncheckedMeansNotVerified() {
            addBook("b1", "书甲", "云岚宗之一", "云岚宗之二", "云岚宗之三", "云岚宗之四");

            LibrarySearchResult r = search.searchAll("云岚宗", 2, source);

            assertTrue(r.truncated());
            assertEquals(4, r.candidateCount(), "4 章都是候选");
            assertEquals(2, r.hitCount(), "结果上限 2 条");
            assertEquals(2, r.unchecked(), "4 个候选只校验了 2 个，另 2 个是没看不是不合格");
        }

        @Test
        @DisplayName("没截断时 unchecked 恒为 0")
        void uncheckedIsZeroWhenComplete() {
            addBook("b1", "书甲", "云岚宗之一", "云岚宗之二");

            LibrarySearchResult r = find("云岚宗");

            assertFalse(r.truncated());
            assertEquals(0, r.unchecked());
        }

        @Test
        @DisplayName("查无此词时是零结果且未截断")
        void missingWordYieldsEmpty() {
            addBook("b1", "星尘纪", "山门之外，风雪交加");

            LibrarySearchResult r = find("云岚宗");

            assertEquals(0, r.hitCount());
            assertFalse(r.truncated());
            assertEquals(1, r.indexedBooks(), "书搜过了，就是没有 —— 这时不该再提示'没建索引'");
        }
    }

    @Nested
    @DisplayName("入参边界")
    class InputEdges {

        @Test
        @DisplayName("空白查询直接空结果，不去打数据库")
        void blankQuery() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门");

            assertEquals(0, find("   ").hitCount());
            assertEquals(0, find(null).hitCount());
        }

        @Test
        @DisplayName("纯标点查询也空结果（分词后没有 token）")
        void punctuationOnlyQuery() {
            addBook("b1", "星尘纪", "他走进云岚宗的大门");

            assertEquals(0, find("！？。。").hitCount());
        }

        @Test
        @DisplayName("必须有原文来源，否则后过滤无从谈起")
        void requiresTextSource() {
            assertThrows(IllegalArgumentException.class,
                    () -> search.searchAll("云岚宗", 0, null));
        }

        @Test
        @DisplayName("ASCII 词跨书也能搜（大小写不敏感）")
        void asciiAcrossBooks() {
            addBook("b1", "星尘纪", "The Ring belongs to someone", "风雪交加");
            addBook("b2", "沧澜录", "a ring of gold", "海潮拍岸");

            LibrarySearchResult r = find("ring");

            assertEquals(2, r.hitCount());
        }
    }
}
