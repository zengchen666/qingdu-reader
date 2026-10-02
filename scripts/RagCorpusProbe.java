import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.BookStore;
import com.qingdu.store.Database;
import com.qingdu.store.SearchStore;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v0.4 真书验收探针 —— 为「AI 问答引用可追溯」准备素材。
 *
 * <p>它做三件事，串起来正好是 A 步（Java 侧）将要实现的链路：
 * <ol>
 *   <li>用**真实的** {@link SearchStore} 跑一次单书检索（不是手工挑片段）</li>
 *   <li>把命中的章节用**真实的** {@link ChapterTextBatch} 读出来，按段落切</li>
 *   <li>导出成 qingdu-ai 能吃的 JSON</li>
 * </ol>
 *
 * <p><b>为什么不用假文本？</b>
 * 真书里一段可能有上千字、章内段落数极不均匀、甚至可能整章只有一段。
 * 切分在真书上的真实形态正是最容易出问题的地方，用造出来的文本验等于没验。
 *
 * <p>用法：
 * <pre>
 * java -cp "out;dist\QingduReader\app\*" RagCorpusProbe &lt;语料目录&gt; &lt;输出JSON&gt;
 * </pre>
 */
public final class RagCorpusProbe {

    /** 空行切段：TXT 小说正文用空行分段。 */
    private static final Pattern BLANK_SPLIT = Pattern.compile("\\R\\s*\\R");

    /** 单片段字符上限，与 Python 侧 retrieval.MAX_CHARS 一致。 */
    private static final int MAX_CHARS = 1200;

    private RagCorpusProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: RagCorpusProbe <corpusDir> <outJson>");
            return;
        }
        Path corpus = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        TxtBookParser parser = new TxtBookParser();
        List<Map<String, Object>> cases = new ArrayList<>();

        // ⚠️ 问题必须**按书配对**。第一版四本书共用同一组问题，结果「药老」
        // 在《武动乾坤》里 0 命中 —— 药老是《斗破苍穹》的人物，不是《武动乾坤》的。
        // 这不是检索的 bug，是素材设计的缺陷：跨书共用问题会制造大量假阴性，
        // 让人误以为召回坏了。
        //
        // 每组三类问题，对应三种召回行为：
        //  ① 单个专有名词      → 关键词召回应当命中
        //  ② 专有名词 + 修饰    → 看召回质量（片段会多、可能不精准）
        //  ③ 不含专名的语义问题 → **应当召不回**，验证服务诚实说"不知道"
        java.util.Map<String, String[]> questionSets = new java.util.LinkedHashMap<>();
        questionSets.put("斗破苍穹", new String[]{"云岚宗", "药老", "萧炎是怎么变强的"});
        questionSets.put("武动乾坤", new String[]{"云岚宗", "药老", "林动是怎么变强的"});
        questionSets.put("元尊", new String[]{"元尊", "洛尘", "元尊是怎么修炼的"});
        questionSets.put("全职高手", new String[]{"荣耀", "叶修", "叶修是怎么退役的"});

        List<Path> books = new ArrayList<>();
        try (var s = Files.list(corpus)) {
            s.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(books::add);
        }
        System.out.println("发现真书 " + books.size() + " 本");

        for (Path bookFile : books) {
            try {
                Book meta = parser.parseMetadata(bookFile);
                String title = (meta.title() == null || meta.title().isBlank())
                        ? bookFile.getFileName().toString() : meta.title();
                String[] qs = matchQuestions(title, bookFile.getFileName().toString(), questionSets);
                if (qs == null) {
                    System.out.println("跳过（没有配对的问题）: " + title);
                    continue;
                }
                Map<String, Object> c = buildCase(parser, bookFile, qs);
                if (c != null) {
                    cases.add(c);
                }
            } catch (Exception e) {
                System.out.println("跳过 " + bookFile.getFileName() + " : " + e);
            }
        }

        Files.writeString(out, toJson(cases), StandardCharsets.UTF_8);
        System.out.println("已写出 " + out.toAbsolutePath() + "  案例数=" + cases.size());
    }

    /**
     * 按书名（或文件名）配对问题组。
     *
     * <p>书名可能被清洗过（剥掉《》），文件名带作者名，所以两个都试一遍。
     * 配不上就返回 null 跳过 —— 宁可少验一本，也不用错配的问题制造假阴性。
     */
    private static String[] matchQuestions(String title, String fileName,
                                           java.util.Map<String, String[]> sets) {
        for (var e : sets.entrySet()) {
            if (title.contains(e.getKey()) || fileName.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> buildCase(TxtBookParser parser, Path bookFile,
                                                 String[] questions) throws Exception {
        Book meta = parser.parseMetadata(bookFile);
        List<Chapter> chapters = parser.parseChapters(bookFile, "probe");
        if (chapters.isEmpty()) {
            System.out.println("跳过（无章节）: " + bookFile.getFileName());
            return null;
        }
        String title = (meta.title() == null || meta.title().isBlank())
                ? bookFile.getFileName().toString() : meta.title();

        // 整本读一次，按偏移切章 —— 与 SearchStore 建索引走的是同一条路径
        ChapterTextBatch batch = ChapterTextBatch.load(bookFile, chapters);

        List<SearchDocument> docs = new ArrayList<>();
        for (Chapter c : chapters) {
            docs.add(new SearchDocument(c.index(), batch.textOf(c.index())));
        }

        // ⚠️ 建索引前书必须已入库：search_meta.book_id 有指向 book(id) 的外键，
        // 直接 index() 会撞 SQLITE_CONSTRAINT_FOREIGNKEY。这与轻读实际流程一致
        // （先导入登记，再建索引）。
        String bookId = "probe:" + bookFile.getFileName();
        Path dbFile = Files.createTempFile("ragprobe", ".db");
        dbFile.toFile().deleteOnExit();

        // Database 没有 close()：探针是短命进程，退出时连接自然释放。
        Database db = Database.open(dbFile);

        BookStore books = new BookStore(db);
        books.save(new Book(bookId, title,
                meta.author() == null ? "" : meta.author(),
                BookFormat.TXT, bookFile.toAbsolutePath(), null,
                chapters.size(), Instant.now()), chapters.size(), null);

        SearchStore store = new SearchStore(db);
        store.index(bookId, SearchStore.fingerprint(bookFile), docs, null);

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("bookTitle", title);
        c.put("chapterCount", chapters.size());

        List<Map<String, Object>> qs = new ArrayList<>();
        for (String q : questions) {
            qs.add(buildQuestion(store, bookId, bookFile, chapters, title, q));
        }
        c.put("questions", qs);
        System.out.println(title + " : " + chapters.size() + " 章");
        return c;
    }

    /**
     * 对一个问题：检索 → 取命中章节 → 切段 → 导出候选片段。
     *
     * <p>用 {@code search} 而非 {@code searchAll}：单书探针不需要跨书索引，
     * 省掉多书协调的复杂度。我们要验的是「切分 + 引用映射」，不是跨书性能。
     */
    private static Map<String, Object> buildQuestion(SearchStore store, String bookId, Path bookFile,
                                                     List<Chapter> chapters, String title, String question) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("question", question);

        List<Map<String, Object>> chunks = new ArrayList<>();
        String keyword = keywordOf(question);
        q.put("keyword", keyword);

        try {
            TxtBookParser parser = new TxtBookParser();
            // 借 ChapterTextBatch 的结果做懒加载缓存：lambda 里不能抛受检异常，
            // 而这里又要避免同一章被 loadChapter 读两次。
            final String[] cache = new String[chapters.size()];
            // 只取 2 个命中章：验收要看的是"引用能否映射回真实片段"，
            // 而一章能切出上百段 —— 取 6 章会产出近 300 个片段，
            // 既拖慢又淹没有效性信息。
            SearchResult res = store.search(bookId, keyword, 2, ci -> {
                if (ci < 0 || ci >= cache.length || cache[ci] != null) {
                    return (ci >= 0 && ci < cache.length) ? cache[ci] : "";
                }
                try {
                    cache[ci] = plainText(parser.loadChapter(bookFile, chapters.get(ci)));
                } catch (Exception e) {
                    cache[ci] = "";
                }
                return cache[ci];
            });

            q.put("hitChapters", res.hits().size());
            for (var hit : res.hits()) {
                Chapter ch = chapters.get(hit.chapterIndex());
                String text = cache[hit.chapterIndex()];
                if (text == null) {
                    continue;
                }
                int pi = 0;
                for (String para : BLANK_SPLIT.split(text)) {
                    String t = para.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (t.length() > MAX_CHARS) {
                        t = t.substring(0, MAX_CHARS) + "…";
                    }
                    Map<String, Object> ck = new LinkedHashMap<>();
                    ck.put("bookId", bookId);
                    ck.put("bookTitle", title);
                    ck.put("chapterIndex", ch.index());
                    ck.put("chapterTitle", ch.title());
                    ck.put("paragraphIndex", pi);
                    ck.put("text", t);
                    chunks.add(ck);
                    pi++;
                }
            }
        } catch (Exception e) {
            q.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        q.put("chunks", chunks);
        return q;
    }

    /** 只取正文段落，拼成一段纯文本（段落之间用空行分隔）。 */
    private static String plainText(Chapter ch) {
        return ch.blocks().stream()
                .filter(x -> x instanceof ChapterBlock.Paragraph)
                .map(x -> ((ChapterBlock.Paragraph) x).text())
                .reduce((a, x) -> a + "\n\n" + x)
                .orElse("");
    }

    /**
     * 从问题里抽出检索关键词。
     *
     * <p>刻意做成朴素的去疑问词 —— 目的是**模拟轻读 A 步将要实现的取词逻辑**，
     * 而不是追求召回质量。这里召回不准不要紧，验收看的是引用映射对不对。
     */
    private static String keywordOf(String question) {
        return question.replaceAll("[？?]", "")
                .replace("是什么", "")
                .replace("在哪里", "")
                .replace("第几章", "")
                .replace("怎么", "")
                .replace("为什么", "")
                .trim();
    }

    /** 极简 JSON 序列化：只处理 Map / List / String / Number / Boolean / null。 */
    private static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, l.get(i));
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
