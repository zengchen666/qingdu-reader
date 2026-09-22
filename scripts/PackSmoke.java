import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.util.CjkTokenizer;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.store.QingduStore;
import com.qingdu.store.SearchStore;
import com.qingdu.store.model.Bookmark;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchHit;
import com.qingdu.store.model.SearchResult;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 打包产物冒烟测试 —— 用「打包时同一套模块列表」跑一遍 TXT 解析主链路。
 *
 * <p><b>为什么需要它？</b><br>
 * jpackage 会把用不到的 JDK 模块裁掉。而 {@code Charset.forName("GB18030")} 是运行时
 * 通过服务提供者机制查找字符集的，jdeps 看不到这层依赖，很容易把 {@code jdk.charsets}
 * 一起裁掉。真被裁掉的话，编译、单元测试、打包全程都不报错，只有用户打开一本 GBK
 * 小说时才抛 {@code UnsupportedCharsetException}。这个类就是用来提前把这种情况抓出来。
 *
 * <p><b>用法</b>（在 qingdu-reader 目录下执行）：
 * <pre>{@code
 * javac -encoding UTF-8 -cp "qingdu-desktop\target\package-stage\app\*" -d out scripts\PackSmoke.java
 * java --module-path qingdu-desktop\target\package-stage\fx \
 *      --add-modules javafx.controls,javafx.fxml \
 *      --limit-modules java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.scripting,java.sql,java.naming,jdk.jfr,javafx.base,javafx.graphics,javafx.controls,javafx.fxml,jdk.unsupported,jdk.charsets \
 *      -cp "out;qingdu-desktop\target\package-stage\app\*" PackSmoke samples\星尘纪-示例-GBK.txt report.txt
 * }</pre>
 *
 * <p>模块列表要和 {@code dist/QingduReader/runtime/release} 里的 {@code MODULES} 保持一致。
 * 注意 {@code --limit-modules} 只认识 JDK 自带的模块，JavaFX 那几个必须同时挂到
 * {@code --module-path} 上，否则会直接报 {@code Module javafx.base not found}。
 * 结果会写成 UTF-8 文本（顺便避开 Windows 控制台编码问题），最后一行是
 * {@code [result] SMOKE TEST PASSED} 或 {@code FAILED}。
 *
 * <p><b>第二部分：存储层</b>。sqlite-jdbc 会把一个本地库（dll）打包在 jar 里，
 * 运行时解压到临时目录再 {@code System.load}。这带来两个只有"跑起来"才暴露的风险：
 * 一是裁剪运行时可能漏掉 {@code java.sql} 等模块，二是 JDK 24+ 会因加载本地库而
 * 打出一屏 native-access 警告。所以这里在临时目录里真开一次库、
 * 写一次进度和书签、再读回来 —— 而不是只检查类能不能加载。
 * 命令行里要带上 {@code --enable-native-access=ALL-UNNAMED}，
 * 因为 sqlite-jdbc 是放在 classpath（未命名模块）里的。
 *
 * <p>注：这个类刻意不放在任何 package 下，是为了能用 {@code -cp} 直接运行、不必配 module-path。
 */
public class PackSmoke {

    public static void main(String[] args) throws Exception {
        // 第二个参数是报告文件路径：把结果直接写成 UTF-8，绕开控制台编码问题
        if (args.length > 1) {
            PrintStream ps = new PrintStream(new FileOutputStream(args[1]), true, StandardCharsets.UTF_8);
            System.setOut(ps);
            System.setErr(ps);
        }

        System.out.println("[env] java.version   = " + System.getProperty("java.version"));
        System.out.println("[env] file.encoding  = " + System.getProperty("file.encoding"));

        // ---- 1. 字符集可用性：这是裁剪运行时唯一真正的风险点 ----
        System.out.println("[charset] ---- availability ----");
        boolean charsetOk = true;
        for (String name : new String[]{"UTF-8", "GBK", "GB18030", "Big5", "UTF-16LE", "UTF-16BE"}) {
            boolean ok;
            String detail;
            try {
                Charset cs = Charset.forName(name);
                ok = cs.canEncode();
                detail = cs.name();
            } catch (Throwable t) {
                ok = false;
                detail = t.getClass().getSimpleName();
                // UTF-8 属于 java.base，它要是不在，问题就不在字符集上了
                if (!"UTF-8".equals(name) && !name.startsWith("UTF-16")) {
                    charsetOk = false;
                }
            }
            System.out.println("[charset] " + pad(name, 9) + " -> "
                    + (ok ? "OK  (" + detail + ")" : "FAIL (" + detail + ")"));
        }

        Path file = Path.of(args[0]);
        System.out.println("[file] " + file);
        System.out.println("[file] size = " + Files.size(file) + " bytes");

        // ---- 2. 编码探测 ----
        CharsetDetector.Detection det = CharsetDetector.detect(file);
        System.out.println("[detect] charset = " + det.charset().name()
                + " | source = " + det.source()
                + " | reason = " + det.reason());
        System.out.println("[detect] displayName = " + det.displayName());

        // ---- 3. 建索引 + 切章 ----
        TxtBookParser parser = new TxtBookParser();
        Book book = parser.parseMetadata(file);
        System.out.println("[book] title  = " + book.title());
        System.out.println("[book] author = " + book.author());

        List<Chapter> chapters = parser.parseChapters(file, book.id());
        System.out.println("[chapters] count = " + chapters.size());
        for (int i = 0; i < Math.min(chapters.size(), 6); i++) {
            Chapter c = chapters.get(i);
            System.out.println("[chapters]   #" + i + "  " + c.title()
                    + "   [" + c.startOffset() + " -> " + c.endOffset()
                    + ", " + c.byteLength() + "B]");
        }

        // ---- 4. 按偏移量加载第一章正文并解码（真正会用到 charset 的地方）----
        if (!chapters.isEmpty()) {
            Chapter loaded = parser.loadChapter(file, chapters.get(0));
            System.out.println("[load] first chapter blocks = " + loaded.blocks().size());
            StringBuilder sb = new StringBuilder();
            for (ChapterBlock b : loaded.blocks()) {
                sb.append(switch (b) {
                    case ChapterBlock.Heading h -> "#" + h.level() + " " + h.text();
                    case ChapterBlock.Paragraph p -> p.text();
                    case ChapterBlock.Image img -> "<img " + img.resourcePath() + ">";
                }).append('\n');
                if (sb.length() > 240) {
                    break;
                }
            }
            String text = sb.toString().trim();
            System.out.println("[load] ---- decoded preview ----");
            System.out.println(text.length() > 200 ? text.substring(0, 200) : text);
            System.out.println("[load] ---- end preview ----");
            System.out.println("[load] mojibake-check(contains '？' or '锟' or U+FFFD) = "
                    + (text.contains("？") || text.contains("锟") || text.contains("\uFFFD")));
        }

        // ---- 5. 存储层：在裁过的运行时里真开一次 SQLite 库 ----
        // 这一段最容易出问题的地方不是 SQL 写错（那有单元测试兜着），
        // 而是 jlink 把 java.sql 之类的模块裁掉了、或者 sqlite-jdbc 的本地库
        // 在运行时解不出来。这两种情况都只有真正跑起来才会暴露。
        boolean storeOk = false;
        boolean searchOk = false;
        Path dbDir = null;
        try {
            dbDir = Files.createTempDirectory("qingdu-packsmoke-");
            Path dbFile = dbDir.resolve("library.db");
            QingduStore store = QingduStore.open(dbFile);

            store.books().save(book, chapters.size(),
                    new ReadingProgress(book.id(), 2, "第三章 夜访", 0.42, System.currentTimeMillis()));

            ReadingProgress read = store.books().findProgress(book.id()).orElse(null);
            Bookmark bookmark = store.bookmarks().add(
                    Bookmark.newOne(book.id(), 2, "第三章 夜访", 0.42, "冒烟测试"));
            store.settings().put("smoke.key", "ok");

            System.out.println("[store] db file        = " + dbFile.getFileName());
            System.out.println("[store] schemaVersion  = " + store.schemaVersion());
            System.out.println("[store] book count     = " + store.books().count());
            System.out.println("[store] progress       = " + (read == null ? "null"
                    : ("chapter " + read.chapterIndex() + " @ " + read.percentLabel())));
            System.out.println("[store] bookmark id    = " + bookmark.id()
                    + " (count = " + store.bookmarks().count(book.id()) + ")");
            System.out.println("[store] setting        = " + store.settings().get("smoke.key", null));

            storeOk = read != null && read.chapterIndex() == 2 && bookmark.id() > 0;

            // ---- 6. 全文检索：验证"这个 sqlite-jdbc 真的带了 FTS5" ----
            // 这是 v0.2.0 新增的一类运行期风险，和上面那两类同源：
            // FTS5 是**编译进 sqlite 本地库**的，JDK 侧编译、单元测试、打包全都看不到它 ——
            // 万一某个环境的 sqlite-jdbc 没编 FTS5，CREATE VIRTUAL TABLE 会在用户点搜索时
            // 才抛 "no such module: fts5"。所以这里真建一次索引、真查一次。
            //
            // 待查的词不写死，而是从第一章正文里现取一个"连续两字汉字"——
            // 这样不管样例文件怎么改，它一定确实出现在书里（否则这条用例会假红）。
            searchOk = smokeSearch(store, parser, file, book, chapters);
        } catch (Throwable t) {
            System.out.println("[store] FAILED: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (dbDir != null) {
                try (var walk = Files.walk(dbDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // 临时文件删不掉不影响结论
                        }
                    });
                } catch (Exception ignored) {
                    // 同上
                }
            }
        }

        boolean ok = charsetOk && storeOk && searchOk;
        System.out.println("[result] " + (ok ? "SMOKE TEST PASSED"
                : "SMOKE TEST FAILED"
                + (charsetOk ? "" : " (charset missing)")
                + (storeOk ? "" : " (store unavailable)")
                + (searchOk ? "" : " (full-text search unavailable)")));
    }

    /**
     * 在打包产物上跑一遍"建索引 → 查询 → 后过滤"。
     *
     * @return 全链路是否正常
     */
    private static boolean smokeSearch(QingduStore store, TxtBookParser parser, Path file,
                                       Book book, List<Chapter> chapters) {
        System.out.println("[search] ---- FTS5 availability ----");
        try {
            if (chapters.isEmpty()) {
                System.out.println("[search] FAILED: 样本书没有章节，无从建索引");
                return false;
            }
            SearchStore search = store.search();

            // 从每章正文里取一段，作为索引内容（真实流程也是这样：正文来自字节偏移切片）
            ChapterTextBatch texts = ChapterTextBatch.load(file, chapters);
            List<SearchDocument> docs = new ArrayList<>(chapters.size());
            for (int i = 0; i < chapters.size(); i++) {
                docs.add(new SearchDocument(i, texts.textOf(i)));
            }
            String word = pickTwoCharWord(texts.textOf(0));
            if (word == null) {
                System.out.println("[search] FAILED: 第一章正文里找不到连续两字汉字，样例不合适");
                return false;
            }

            long startedAt = System.nanoTime();
            search.index(book.id(), SearchStore.fingerprint(file), docs, null);
            long indexMs = (System.nanoTime() - startedAt) / 1_000_000L;

            SearchResult result = search.search(book.id(), word, Integer.MAX_VALUE, texts::textOf);
            List<Integer> hitChapters = result.hits().stream().map(SearchHit::chapterIndex).toList();

            System.out.println("[search] fts5           = OK（CREATE VIRTUAL TABLE 成功）");
            System.out.println("[search] tokenize('" + word + "') = "
                    + CjkTokenizer.tokenize(word).trim());
            System.out.println("[search] chapters       = " + chapters.size()
                    + " | indexed = " + search.indexedChapterCount(book.id())
                    + " | index ms = " + indexMs);
            System.out.println("[search] query '" + word + "' -> chapters " + hitChapters
                    + "（候选 " + result.candidateCount() + "，耗时 " + result.elapsedMs() + " ms）");
            if (!result.hits().isEmpty()) {
                System.out.println("[search] snippet        = " + result.hits().get(0).snippet());
            }

            boolean hit = hitChapters.contains(0);
            if (!hit) {
                System.out.println("[search] FAILED: 第一章里明明有「" + word + "」，却没被搜到");
            }
            return hit;
        } catch (Throwable t) {
            System.out.println("[search] FAILED: " + t);
            t.printStackTrace(System.out);
            return false;
        }
    }

    /** 取一段文本里第一处"连续两个汉字"，用作待查词 —— 保证它一定在书里。 */
    private static String pickTwoCharWord(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            if (CjkTokenizer.isHan(text.charAt(i)) && CjkTokenizer.isHan(text.charAt(i + 1))) {
                return text.substring(i, i + 2);
            }
        }
        return null;
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
