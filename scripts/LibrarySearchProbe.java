import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.util.BookId;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.Database;
import com.qingdu.store.QingduStore;
import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.reader.library.LibraryChapterTextSource;
import com.qingdu.reader.library.LibraryIndexTask;
import com.qingdu.reader.library.LibrarySearchPresenter;
import com.qingdu.store.SearchStore;
import com.qingdu.store.model.SearchDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨书检索的真书语料验收探针 —— v0.3 的验收依据。
 *
 * <p>它回答的是 v0.2.0 的 {@code SearchProbe} <b>回答不了</b>的那些问题。
 * 单书探针证明的是"一本 10 MB 的书里搜得准、搜得快"，
 * 而 v0.3 换了三处实现，每一处都需要<b>跨书</b>口径的数据：
 * <ol>
 *   <li><b>索引是懒建的</b>：四本一起建要多久？内存峰值是多少？
 *       批量建索引的取舍（一次只用一本）就是为了这个数字；</li>
 *   <li><b>跨书查询不能把书读进内存</b>：改用按偏移随机读之后，
 *       查询耗时是升是降？峰值内存是不是真的降下来了？</li>
 *   <li><b>零假阳性在跨书下成不成立</b>：
 *       {@code candidateCount == hitCount} 仍然要成立，而且这次
 *       还要看<b>覆盖度数字对不对</b>（{@code libraryBooks / indexedBooks /
 *       searchedBooks}）—— 那是界面提示"还有几本没被搜"的唯一依据，
 *       数字错了就是在骗用户；</li>
 *   <li><b>书名排序</b>：跨书结果的分组顺序必须是拼音序，不能是 Unicode 码位序
 *       （《子羽书》排在《阿澜书》前面那种"随机顺序"）。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 * javac -encoding UTF-8 -cp "qingdu-common\target\classes;qingdu-core\target\classes;qingdu-store\target\classes;qingdu-desktop\target\classes" -d out scripts\LibrarySearchProbe.java
 * java -cp "out;qingdu-common\target\classes;qingdu-core\target\classes;qingdu-store\target\classes;qingdu-desktop\target\classes" LibrarySearchProbe &lt;语料目录&gt; &lt;报告文件&gt;
 * </pre>
 *
 * <p>⚠️ 语料目录要用<b>纯 ASCII 路径</b>（中文路径经命令行传给 JVM 会抛
 * {@code InvalidPathException}），文件名可以保留中文 —— 文件名来自
 * {@code Files.list()}，不经过命令行编码。
 */
public class LibrarySearchProbe {

    /** 跨书查询词。挑的都是"多本书里可能都有"的词，才是跨书检索的真场景。 */
    private static final String[] QUERIES = {
            "云岚宗", "萧炎", "林动", "叶修", "周元", "元力", "源气", "荣耀", "药老"
    };

    public static void main(String[] args) throws Exception {
        Path corpusDir = Path.of(args[0]);
        Path reportFile = Path.of(args[1]);

        Path dbFile = corpusDir.resolveSibling("library-probe.db");
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));

        StringBuilder report = new StringBuilder();
        report.append("# 跨书检索真书语料验收（v0.3）\n\n");
        report.append("语料目录：").append(corpusDir).append("\n\n");

        // Database 没有 close()（SQLite 连接是每次操作现开现关的），
        // 所以这里不用 try-with-resources
        QingduStore store = QingduStore.open(Database.open(dbFile));
        {
            List<Book> books = importCorpus(store, corpusDir, report);
            if (books.isEmpty()) {
                report.append("没有找到任何 .txt 语料，报告无意义。\n");
                Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
                return;
            }

            LibraryIndexTask indexTask = new LibraryIndexTask(store);
            report.append("## 一、批量建索引（懒建 → 首次全库检索的代价）\n\n");
            report.append("| 书 | 字节 | 章节 | 建索引耗时 | 建完索引体积 |\n");
            report.append("|---|---|---|---|---|\n");
            for (Book book : books) {
                Path file = book.filePath();
                long bytes = Files.size(file);
                // 走公开的 run(books, progress) 而不是包可见的 indexOne：
                // 探针属于"外部包"，用公开 API 才不会逼着生产代码放宽可见性。
                // 每次只给一本书，所以 Report 里的成败就是这一本的成败。
                long started = System.nanoTime();
                LibraryIndexTask.Report one = indexTask.run(List.of(book), null);
                long millis = (System.nanoTime() - started) / 1_000_000L;
                long indexBytes = indexSize(dbFile);
                report.append(String.format("| 《%s》 | %s | %d | %d ms | %s |%n",
                        book.title(), mib(bytes), book.chapterCount(), millis, mib(indexBytes)));
                if (one.failed() > 0) {
                    report.append("| ⚠️ 这一本失败了：")
                            .append(one.failures().values().stream().findFirst().orElse("?"))
                            .append(" |\n");
                }
            }
            report.append(String.format("%n建完索引后书库共 %d 本、待建 %d 本（应为 0）。%n%n",
                    store.books().count(), indexTask.pendingCount()));

            // ---- 峰值内存：这是 v0.3 最需要拿数字证明的一点 ----
            report.append("## 二、内存峰值：批量建索引 vs 跨书查询\n\n");
            report.append("| 阶段 | 已用堆 | 峰值堆 |\n|---|---|---|\n");
            report.append(String.format("| 建完全部索引后 | %s | %s |%n",
                    mib(usedHeap()), mib(peakHeap())));

            report.append("\n## 三、跨书查询：耗时 / 覆盖度 / 零假阳性\n\n");
            report.append("> **冷热两列的区别就是 v0.3 最关键的一个设计决定。**\n");
            report.append("> 「冷」= 每次查询都新建一个原文源；「热」= 复用同一个。\n");
            report.append("> 差的那部分就是<b>重算章节表</b>的钱：按字节偏移 seek 之前\n");
            report.append("> 必须知道每章的偏移，而偏移来自分章，分章要读整个文件。\n");
            report.append("> 界面上的实现是<b>长期持有一个源</b>（见 SearchPanel.librarySource），\n");
            report.append("> 所以用户实际体验到的是「热」那一列。\n\n");
            report.append("| 查询词 | 命中章 | 涉及书 | 候选章 | 候选==精确 | 书库 | 已索引 | 已校验 | 冷(ms) | 热(ms) | 省 |\n");
            report.append("|---|---|---|---|---|---|---|---|---|---|---|\n");

            boolean allExact = true;
            boolean allCoverageRight = true;
            List<String> failures = new ArrayList<>();

            // 冷：每次都新建源（这是"错误的做法"，留着做对照）
            List<Long> cold = new ArrayList<>();
            List<Long> warm = new ArrayList<>();
            LibraryChapterTextSource warmSource = indexTask.newTextSource();
            for (String query : QUERIES) {
                long t0 = System.nanoTime();
                try (LibraryChapterTextSource coldSource = indexTask.newTextSource()) {
                    searchAll(store, coldSource, query, 0);
                }
                cold.add((System.nanoTime() - t0) / 1_000_000L);

                LibrarySearchResult r = searchAll(store, warmSource, query, 0);
                warm.add(r.elapsedMs());

                // 统计精确章数必须不限条数，否则截断会让"候选 != 精确"
                LibrarySearchResult full = searchAll(store, warmSource, query, Integer.MAX_VALUE);
                boolean exact = full.candidateCount() == full.hitCount();
                boolean coverageRight = full.searchedBooks() <= full.indexedBooks()
                        && full.indexedBooks() <= full.libraryBooks();
                allExact &= exact;
                allCoverageRight &= coverageRight;
                if (!exact) {
                    failures.add("「" + query + "」候选 " + full.candidateCount()
                            + " ≠ 精确 " + full.hitCount() + "（有假阳性）");
                }
                if (!coverageRight) {
                    failures.add("「" + query + "」覆盖度不自洽：书库 " + full.libraryBooks()
                            + " / 已索引 " + full.indexedBooks()
                            + " / 已校验 " + full.searchedBooks());
                }
                long c = cold.get(cold.size() - 1);
                long w = warm.get(warm.size() - 1);
                report.append(String.format(
                        "| %s | %d | %d | %d | %s | %d | %d | %d | %d | %d | %.1fx |%n",
                        query, r.hitCount(), r.hitsByBook().size(), r.candidateCount(),
                        exact ? "是" : "**否**", r.libraryBooks(), r.indexedBooks(),
                        r.searchedBooks(), c, w, c <= 0 ? 0 : (double) c / Math.max(1, w)));
            }
            warmSource.close();
            report.append(String.format("%n查询后已用堆 %s，峰值堆 %s。%n%n",
                    mib(usedHeap()), mib(peakHeap())));

            // ---- 书名排序 ----
            report.append("## 四、结果分组顺序（必须是拼音序）\n\n");
            try (LibraryChapterTextSource source = indexTask.newTextSource()) {
                LibrarySearchResult r = searchAll(store, source, "之", 0);
                List<String> titles = new ArrayList<>();
                for (LibrarySearchPresenter.Row row
                        : LibrarySearchPresenter.toRows(r.hits(), r.hitsByBook().size())) {
                    String title = LibrarySearchPresenter.headerTitleOf(row);
                    if (title != null && !titles.contains(title)) {
                        titles.add(title);
                    }
                }
                report.append("查「之」得到的分组顺序：");
                for (String t : titles) {
                    report.append(' ').append(t);
                }
                report.append("\n\n");
            }

            // ---- 界面提示文案 ----
            report.append("## 五、覆盖度提示文案（界面会显示的那句话）\n\n");
            try (LibraryChapterTextSource source = indexTask.newTextSource()) {
                LibrarySearchResult r = searchAll(store, source, "云岚宗", 0);
                report.append("> ").append(LibrarySearchPresenter.summary("云岚宗", r))
                        .append("\n>\n> 覆盖度分档：")
                        .append(LibrarySearchPresenter.coverageOf(r)).append("\n\n");
            }

            report.append("## 六、结论\n\n");
            report.append("- 零假阳性（候选 == 精确）：").append(allExact ? "**全部通过**" : "**有失败**")
                    .append('\n');
            report.append("- 覆盖度自洽：").append(allCoverageRight ? "**通过**" : "**有失败**")
                    .append('\n');
            if (!failures.isEmpty()) {
                report.append("\n失败明细：\n");
                for (String f : failures) {
                    report.append("- ").append(f).append('\n');
                }
            }
        }

        Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
        System.out.println("report -> " + reportFile);
    }

    /** 跑一次跨书查询；传 {@link Integer#MAX_VALUE} 可以拿到未截断的精确统计。 */
    private static LibrarySearchResult searchAll(QingduStore store,
                                                 LibraryChapterTextSource source,
                                                 String query, int limit) {
        return store.search().searchAll(query, limit, source);
    }

    /** 把语料目录里的 TXT 登记进书库（不建索引，那是下一步的事）。 */
    private static List<Book> importCorpus(QingduStore store, Path dir, StringBuilder report)
            throws Exception {
        TxtBookParser parser = new TxtBookParser();
        List<Book> books = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            var files = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                Book meta = parser.parseMetadata(file);
                int chapters = countChapters(meta);
                Book book = new Book(BookId.of(file), meta.title(), meta.author(),
                        meta.format(), file, null, chapters, meta.addedAt());
                store.books().save(book, chapters,
                        new ReadingProgress(book.id(), 0, null, 0, System.currentTimeMillis()));
                books.add(book);
            }
        }
        report.append("## 〇、语料入库\n\n");
        for (Book b : books) {
            report.append("- 《").append(b.title()).append("》 ")
                    .append(mib(Files.size(b.filePath())))
                    .append("    ").append(b.chapterCount()).append(" 章\n");
        }
        report.append('\n');
        return books;
    }

    private static int countChapters(Book book) {
        try {
            List<Chapter> chapters = new TxtBookParser().parseChapters(
                    book.filePath(), book.id());
            return chapters.size();
        } catch (Exception e) {
            return 0;
        }
    }
    /** FTS5 索引的体积：主库减去 -wal/-shm 就是它（外加 book 表，量级可忽略）。 */
    private static long indexSize(Path dbFile) {
        try {
            return Files.size(dbFile);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long peakHeap() {
        // 没有直接读"峰值堆"的标准 API，用总量近似：
        // 一个跑完大批量建索引 + 跨书查询的 JVM，totalMemory 已经被峰值撑大了
        return Runtime.getRuntime().totalMemory();
    }

    private static String mib(long bytes) {
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
