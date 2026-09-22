import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.BookStore;
import com.qingdu.store.Database;
import com.qingdu.store.SearchStore;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchHit;
import com.qingdu.store.model.SearchResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 全文检索的真书语料验收探针 —— v0.2.0 的验收依据。
 *
 * <p>它回答三个问题，每个都只能靠真数据回答：
 * <ol>
 *   <li><b>首次建索引要多久</b>（设计文档的预算是 ≤ 8 秒 / 一本 10 MB 的书）；</li>
 *   <li><b>查询要多久</b>（预算 ≤ 200 ms）；</li>
 *   <li><b>会不会有假阳性</b> —— 也就是「SQL 给的候选章数」和
 *       「原文校验后的精确章数」是否相等（验收标准是四本书上都相等）。</li>
 * </ol>
 *
 * <p>这三个数字是分词方案（bigram）和后过滤两处设计的直接验证：
 * 候选 == 精确说明后过滤没在误杀，也说明假阳性在这个语料上确实罕见；
 * 而一旦换成 trigram 之类的方案，"两字词"那一批会整片变成 0，
 * 一眼就能看出来。
 *
 * <p>用法（classpath 指向 Maven 编译产物，改完代码立刻能验证）：
 * <pre>
 * javac -encoding UTF-8 -cp "qingdu-common\target\classes;qingdu-core\target\classes;qingdu-store\target\classes;dist\QingduReader\app\*" -d out scripts\SearchProbe.java
 * java -cp "out;qingdu-common\target\classes;qingdu-core\target\classes;qingdu-store\target\classes;dist\QingduReader\app\*" SearchProbe &lt;语料目录&gt; &lt;报告文件&gt;
 * </pre>
 *
 * <p>⚠️ 语料目录要用<b>纯 ASCII 路径</b>（中文路径经命令行传给 JVM 会抛
 * {@code InvalidPathException}），文件名可以保留中文 —— 文件名来自
 * {@code Files.list()}，不经过命令行编码。
 */
public class SearchProbe {

    /** 每本书搜这几个词。挑的都是各书的标志性词（两字、三字都有）。 */
    private static final Map<String, String[]> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put("元尊", new String[]{"周元", "源气", "气运", "天源界"});
        WORDS.put("全职高手", new String[]{"叶修", "荣耀", "兴欣", "剑客"});
        WORDS.put("斗破苍穹", new String[]{"斗之气", "药老", "萧炎", "异火", "云岚宗", "炼药师", "纳戒"});
        WORDS.put("武动乾坤", new String[]{"林动", "元力", "石符", "青阳镇"});
    }

    public static void main(String[] args) throws Exception {
        Path corpusDir = Path.of(args[0]);
        Path reportFile = Path.of(args[1]);

        Path dbFile = corpusDir.resolveSibling("search-probe.db");
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));

        StringBuilder report = new StringBuilder();
        report.append("# 全文检索真书语料验收\n\n");
        report.append("语料目录：`").append(corpusDir).append("`\n\n");
        report.append("| 书 | 章节 | 建索引 (ms) | 本书索引 (MB) | 数据库累计 (MB) |\n|---|---|---|---|---|\n");

        Database database = Database.open(dbFile);
        BookStore books = new BookStore(database);
        SearchStore search = new SearchStore(database);
        TxtBookParser parser = new TxtBookParser();

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(corpusDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }

        StringBuilder detail = new StringBuilder();
        detail.append("\n## 逐词结果（候选 = SQL 给的章数，精确 = 原文校验后的章数）\n\n");

        long totalIndexMs = 0;
        int totalChapters = 0;
        long lastDbSize = 0;      // 上一本跑完时的库体积，用来算「本书索引」的增量
        long totalIndexBytes = 0; // 各本增量之和 = 四本书的索引总体积

        for (Path file : files) {
            Book book = parser.parseMetadata(file);
            List<Chapter> chapters = parser.parseChapters(file, book.id());
            ChapterTextBatch texts = ChapterTextBatch.load(file, chapters);

            List<SearchDocument> docs = new ArrayList<>(chapters.size());
            for (int i = 0; i < chapters.size(); i++) {
                docs.add(new SearchDocument(i, texts.textOf(i)));
            }

            // 【顺序不能反】search_meta 有指向 book(id) 的外键，
            // 书没进书库就建索引会直接抛 FOREIGN KEY constraint failed
            books.save(book, chapters.size(),
                    new ReadingProgress(book.id(), 0, null, 0, System.currentTimeMillis()));

            long startedAt = System.nanoTime();
            search.index(book.id(), SearchStore.fingerprint(file), docs, null);
            long indexMs = (System.nanoTime() - startedAt) / 1_000_000L;

            // ⚠️ 口径易错：库是累积的（本探针不删前一本的索引），
            // 直接印 Files.size() 得到的是「累计体积」，会被读成「本书索引体积」。
            // 版本 0.2.0 的第一版报告就踩了这个坑（末本 116.0 MB 看着像单本索引）。
            long dbSize = sizeOf(dbFile);
            long bookIndexBytes = dbSize - lastDbSize;
            lastDbSize = dbSize;

            totalIndexMs += indexMs;
            totalChapters += chapters.size();
            totalIndexBytes += bookIndexBytes;

            report.append(String.format("| %s | %d | %,d | %.1f | %.1f |%n",
                    book.title(), chapters.size(), indexMs,
                    bookIndexBytes / 1024.0 / 1024.0, dbSize / 1024.0 / 1024.0));

            String[] words = wordsFor(book.title());
            detail.append("\n### ").append(book.title())
                    .append("（").append(chapters.size()).append(" 章, ")
                    .append(String.format("%.1f MB", Files.size(file) / 1024.0 / 1024.0))
                    .append("）\n\n");
            detail.append("| 词 | 候选章数 | 精确章数 | 全量耗时 (ms) | 候选 == 精确？ | 默认上限耗时 (ms) |\n");
            detail.append("|---|---|---|---|---|---|\n");

            for (String word : words) {
                // 【必须是 Integer.MAX_VALUE】不能用默认上限：默认会集满 300 条就停下，
                // 那时 candidateCount - hitCount 是"没校验的候选"，不是假阳性。
                // 第一版脚本就是因为这个把「萧炎」报成 1322 个假阳性。
                SearchResult full = search.search(book.id(), word, Integer.MAX_VALUE, texts::textOf);
                SearchResult capped = search.search(book.id(), word, 0, texts::textOf);
                int falsePositives = full.candidateCount() - full.hitCount();
                detail.append(String.format("| %s | %d | %d | %d | %s | %d |%n",
                        word, full.candidateCount(), full.hitCount(), full.elapsedMs(),
                        full.candidateCount() == full.hitCount() ? "✅ 零假阳性"
                                : "❌ 假阳性 " + falsePositives,
                        capped.elapsedMs()));
            }

            // 抽样验证：随便挑一条命中，确认它真的出现在那一章的原文里
            String sample = words[0];
            SearchResult sampleResult = search.search(book.id(), sample, 1, texts::textOf); // 默认上限下取第一条
            if (!sampleResult.hits().isEmpty()) {
                SearchHit hit = sampleResult.hits().get(0);
                String raw = texts.textOf(hit.chapterIndex());
                detail.append("\n抽样：搜「").append(sample).append("」第一条 = 第 ")
                        .append(hit.chapterIndex() + 1).append(" 章，命中 ")
                        .append(hit.hitCount()).append(" 次，原文校验 ")
                        .append(raw.contains(sample) ? "通过 ✅" : "失败 ❌").append('\n');
                detail.append("\n摘要：").append(hit.snippet().replace("\n", " ")).append("\n");
            }
        }

        report.append(String.format("%n合计：%d 章，建索引 %,d ms（平均每本 %,d ms）%n",
                totalChapters, totalIndexMs, totalIndexMs / Math.max(1, files.size())));
        report.append(String.format("索引体积：平均每本 %.1f MB，%d 本累计 %.1f MB%n",
                totalIndexBytes / 1024.0 / 1024.0 / Math.max(1, files.size()),
                files.size(), totalIndexBytes / 1024.0 / 1024.0));

        report.append(detail);
        Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
        System.out.println("written " + reportFile);
    }

    private static String[] wordsFor(String title) {
        for (Map.Entry<String, String[]> entry : WORDS.entrySet()) {
            if (title != null && title.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        // 认不出来的书名：给一组通用词，只为把耗时跑出来
        return new String[]{"第一章", "他说"};
    }

    private static long sizeOf(Path file) throws IOException {
        long size = Files.exists(file) ? Files.size(file) : 0;
        for (String suffix : new String[]{"-wal", "-shm"}) {
            Path extra = Path.of(file + suffix);
            if (Files.exists(extra)) {
                size += Files.size(extra);
            }
        }
        return size;
    }
}
