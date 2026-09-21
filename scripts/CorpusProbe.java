import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.util.ChineseNumerals;
import com.qingdu.core.parser.txt.ChapterTitleMatcher;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.core.parser.txt.TxtChapterSplitter;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.core.text.TextCodec;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实语料审计探针 —— 拿硬盘上真正的大部头小说，把解析引擎从头到尾走一遍，
 * 把「看起来对」和「真的对」分开。
 *
 * <p><b>为什么需要它？</b><br>
 * 单元测试用的是自己造的样本（几百字节、几十章），能覆盖逻辑分支，但覆盖不了
 * <b>真实文件的体量与脏数据</b>：7~11MB 的单文件、上千章、混在正文里的目录页、
 * 各种「番外」「作品相关」的标题变体、偶尔出现的空行与全角空格。
 * 这里用一串探针式检查把它们变成可自动判定的结论：
 *
 * <ol>
 *   <li><b>编码真的对了吗</b> —— 不只看探测器报了什么名字，而是把正文按它报的编码解出来，
 *       统计「中日韩统一汉字 + 中文标点」占非空白字符的比例。解错了的话这个比例会明显偏低，
 *       或者出现 {@code U+FFFD}。</li>
 *   <li><b>分章过程数据</b> —— 直接读 {@link TxtChapterSplitter.Report}，
 *       看清「候选多少个 → 被目录过滤吃掉多少 → 被序号校验毙掉多少」，而不是只看最终章数。
 *       结果不对时，这三行数字能立刻指出是哪一道关出的问题。</li>
 *   <li><b>偏移量真的连续吗</b> —— 第 i 章的 endOffset 必须等于第 i+1 章的 startOffset，
 *       末章 endOffset 必须等于文件大小。断一处就说明划分漏了一段或重了一段。</li>
 *   <li><b>章节序号真的单调吗</b> —— 从标题里抽出「第 X 章」的序号，分别统计
 *       <b>倒退</b>（正文引述）、<b>重复</b>（重章）、<b>跳号</b>。
 *       其中<b>跳号是最灵敏的漏切信号</b>：漏切会把后面几章并进一章，序号立刻断档，
 *       而且断档缺几个序号就说明合并了几章。</li>
 *   <li><b>长度分布有离群点吗</b> —— 分 ×2.5（大概是漏切了一章）和 ×5（漏切了多章）两档列出来。</li>
 *   <li><b>有没有行内标题被漏掉</b> —— 这是本次审计发现的<b>真实脏数据形态</b>：
 *       下载站抓下来的书，常把作者感言和章节标题拼在同一行：
 *       <pre>　　（求收藏求推荐票，谢谢大家了第八章冲突</pre>
 *       整行远超 40 字，被形态校验当成正文毙掉。本项扫描「行内/段尾」能否构成合法标题，
 *       再用「序号落在已解析序号的空档里」二次确认 —— 落在空档里的几乎必然是漏切。</li>
 *   <li><b>抽样解码</b> —— 随机抽首/中/末三章真正解码出来，看中文占比、替换字符、首个内容块。</li>
 * </ol>
 *
 * <p><b>用法</b>（在 qingdu-reader 目录下执行）：
 * <pre>{@code
 * # 开发回归：classpath 指向 Maven 的编译产物（改完代码立刻能验证）
 * javac -encoding UTF-8 -cp "qingdu-common\target\classes;qingdu-core\target\classes" -d out scripts\CorpusProbe.java
 * java -cp "out;qingdu-common\target\classes;qingdu-core\target\classes" CorpusProbe <语料目录> <报告文件> [清单文件]
 *
 * # 发布验收：classpath 换成发布版产物，顺便验证"打出来的包真的能解析真实大文件"
 * javac -encoding UTF-8 -cp "dist\QingduReader\app\*" -d out scripts\CorpusProbe.java
 * java -cp "out;dist\QingduReader\app\*" CorpusProbe <语料目录> <报告文件> [清单文件]
 * }</pre>
 *
 * <p>语料目录请用<b>纯 ASCII 路径</b>：中文路径经命令行传给 JVM 会因控制台编码
 * 报 {@code InvalidPathException}（本项目踩过这个坑）。清单文件是 UTF-8 的
 * {@code ASCII文件名<TAB>原始文件名} 两列，用来在报告里显示真实书名。
 *
 * <p>报告写成 UTF-8 文件，顺便绕开 Windows 控制台编码问题。
 */
public class CorpusProbe {

    /** 从「第一章」「第1章」「第两百章」「第一百二十三节」里抽序号。 */
    private static final Pattern CHAPTER_NUM =
            Pattern.compile("第\\s*([0-9０-９零〇一二三四五六七八九十百千万两]+)\\s*[章节回卷]");

    /** 明确不是正文章节的标题特征。 */
    private static final String[] NON_BODY = {
            "作品相关", "番外", "外传", "楔子", "序章", "序言", "前言", "后记", "尾声",
            "完本感言", "感言", "公告", "请假", "说明", "附录", "目录", "简介"
    };

    /** 分卷单位，用来识别「卷标题独立成章」。 */
    private static final String VOLUME_UNITS = "卷部篇";

    /**
     * 宽松的「行首看起来就是标题」模式 —— 只看形态，不看长度和标点。
     *
     * <p>用它把"文件里有、但被生产代码拒了"的行捞出来，才能回答
     * "候选标题数为什么比文件里的标题行少"这个问题。
     */
    private static final Pattern LOOSE_HEADING_AT_START = Pattern.compile(
            "^第[\\s\\u3000]*[0-9０-９零〇一二三四五六七八九十百千万两]{1,12}"
                    + "[\\s\\u3000]*[章节回卷部篇集幕]");

    /** 作者名尾部可能残留的杂符。 */
    private static final String TRAILING_JUNK = "』」】》）)]”’\"'、，,。.；;：:·-—";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: CorpusProbe <语料目录> <报告文件> [清单文件]");
            System.exit(2);
        }
        Path corpusDir = Path.of(args[0]);
        Path report = Path.of(args[1]);

        try (PrintStream ps = new PrintStream(new FileOutputStream(report.toFile()),
                true, StandardCharsets.UTF_8)) {
            PrintStream old = System.out;
            System.setOut(ps);
            try {
                Map<String, String> displayNames = args.length > 2
                        ? readIndex(Path.of(args[2])) : Map.of();
                run(corpusDir, displayNames);
            } finally {
                System.setOut(old);
            }
        }
        System.out.println("报告已写出: " + report);
    }

    /** 读 UTF-8 清单：ASCII文件名 <TAB> 原始文件名 [<TAB> sha256]。 */
    private static Map<String, String> readIndex(Path p) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 2) {
                m.put(parts[0].trim(), parts[1].trim());
            }
        }
        return m;
    }

    private static void run(Path corpusDir, Map<String, String> displayNames) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(corpusDir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }

        print("真实语料审计报告");
        print("生成时间 = " + java.time.LocalDateTime.now());
        print("java.version = " + System.getProperty("java.version")
                + "   file.encoding = " + System.getProperty("file.encoding"));
        print("语料目录 = " + corpusDir);
        print("文件数 = " + files.size());
        print(repeat('=', 78));
        print("");

        List<Verdict> verdicts = new ArrayList<>();
        for (Path f : files) {
            String ascii = f.getFileName().toString();
            String shown = displayNames.getOrDefault(ascii, ascii);
            verdicts.add(probeOne(f, ascii, shown));
            print("");
            print(repeat('=', 78));
            print("");
        }

        print("总览");
        print(repeat('-', 78));
        for (Verdict v : verdicts) {
            print(String.format(Locale.ROOT, "%-8s %-34s 章节 %5d  耗时 %6d ms   %s",
                    v.ascii, shorten(v.shown, 34), v.chapters, v.parseMillis,
                    v.flags.isEmpty() ? "干净" : "⚠ " + v.flags.size() + " 项待看"));
        }
        print("");
        int totalFlags = verdicts.stream().mapToInt(v -> v.flags.size()).sum();
        print("全部检查通过的 = "
                + verdicts.stream().filter(v -> v.flags.isEmpty()).count() + " / " + verdicts.size());
        print("待复核项合计 = " + totalFlags);
    }

    // ------------------------------------------------------------------ 单本审计

    private record Verdict(String ascii, String shown, int chapters, long parseMillis, List<String> flags) {
    }

    private static Verdict probeOne(Path file, String ascii, String shown) throws Exception {
        List<String> flags = new ArrayList<>();
        long fileSize = Files.size(file);

        print("【" + ascii + "】" + shown);
        print("文件大小 = " + fileSize + " B  (" + mb(fileSize) + ")");

        // ---- 1. 编码探测 ----
        print("");
        print("1) 编码探测");
        long t0 = System.nanoTime();
        CharsetDetector.Detection det = CharsetDetector.detect(file);
        long detectMs = (System.nanoTime() - t0) / 1_000_000;
        print("   charset     = " + det.charset().name());
        print("   source      = " + det.source());
        print("   reason      = " + det.reason());
        print("   displayName = " + det.displayName());
        print("   耗时        = " + detectMs + " ms");

        // ---- 2. 元信息 ----
        TxtBookParser parser = new TxtBookParser();
        Book book = parser.parseMetadata(file);
        print("");
        print("2) 元信息");
        print("   title  = " + book.title());
        print("   author = " + (book.author() == null ? "(空)" : book.author()));
        print("   id     = " + book.id());
        String author = book.author();
        if (author != null && !author.isEmpty()
                && TRAILING_JUNK.indexOf(author.charAt(author.length() - 1)) >= 0) {
            flags.add("作者名尾部残留杂符「" + author.charAt(author.length() - 1) + "」：" + author);
        }

        // ---- 3. 建索引 + 分章（用带诊断的 index，一次拿到章节和过程数据） ----
        print("");
        print("3) 建索引 + 分章");
        t0 = System.nanoTime();
        TxtChapterSplitter.Report rep = parser.index(file, book.id());
        long parseMs = (System.nanoTime() - t0) / 1_000_000;
        List<Chapter> chapters = rep.chapters();
        print("   章节数 = " + chapters.size());
        print("   耗时   = " + parseMs + " ms"
                + (parseMs > 0
                ? String.format(Locale.ROOT, "   (%.2f MB/s)", mb(fileSize) / (parseMs / 1000.0))
                : ""));
        if (chapters.isEmpty()) {
            flags.add("一章都没分出来");
            return new Verdict(ascii, shown, 0, parseMs, flags);
        }
        print(String.format(Locale.ROOT, "   平均每章 %.1f KB", fileSize / 1024.0 / chapters.size()));

        print("   --- 分章过程数据 ---");
        print("   候选标题数（前两重校验通过）= " + rep.candidateCount());
        print("   其中行内标题切出来的          = " + rep.inlineHeadings());
        print("   被目录区过滤丢弃            = " + rep.tocDropped());
        print("   被序号单调性拒绝            = " + rep.sequenceRejected());
        print("   卷边界重置次数              = " + rep.volumeResets());
        print("   是否退化成单章全文          = " + rep.fallback());
        print("   摘要： " + rep.summary());
        if (rep.fallback()) {
            flags.add("分章退化成了单章全文");
        }
        if (rep.candidateCount() > 0) {
            double dropRate = (rep.tocDropped() + rep.sequenceRejected()) * 100.0 / rep.candidateCount();
            if (dropRate > 30.0) {
                flags.add(String.format(Locale.ROOT, "候选标题丢弃率 %.0f%% 偏高（目录过滤 %d + 序号拒绝 %d）",
                        dropRate, rep.tocDropped(), rep.sequenceRejected()));
            }
        }

        // ---- 4. 偏移量连续性 ----
        print("");
        print("4) 偏移量连续性");
        print("   首章 startOffset = " + chapters.get(0).startOffset()
                + "   (正文前有 " + chapters.get(0).startOffset() + " 字节的前置内容)");
        print("   末章 endOffset   = " + chapters.get(chapters.size() - 1).endOffset()
                + "   文件大小 = " + fileSize);
        int gaps = 0;
        long gapBytes = 0;
        List<String> gapSamples = new ArrayList<>();
        for (int i = 0; i + 1 < chapters.size(); i++) {
            long end = chapters.get(i).endOffset();
            long nextStart = chapters.get(i + 1).startOffset();
            if (end != nextStart) {
                gaps++;
                gapBytes += Math.abs(nextStart - end);
                if (gapSamples.size() < 5) {
                    gapSamples.add(String.format(Locale.ROOT,
                            "      #%d end=%d  ->  #%d start=%d   (差 %d)",
                            i, end, i + 1, nextStart, nextStart - end));
                }
            }
        }
        print("   相邻章不连续的处数 = " + gaps
                + (gaps == 0 ? "   -> 完全首尾相接" : "   累计偏差 " + gapBytes + " 字节"));
        gapSamples.forEach(CorpusProbe::print);
        if (gaps > 0) {
            flags.add("偏移量有 " + gaps + " 处不连续");
        }
        long lastEnd = chapters.get(chapters.size() - 1).endOffset();
        if (lastEnd != fileSize) {
            flags.add("末章 endOffset(" + lastEnd + ") != 文件大小(" + fileSize + ")");
        }
        int zeroLen = 0;
        for (Chapter c : chapters) {
            if (c.byteLength() <= 0) {
                zeroLen++;
            }
        }
        print("   零长度章节数 = " + zeroLen);
        if (zeroLen > 0) {
            flags.add(zeroLen + " 个零长度章节");
        }

        // ---- 5. 长度分布 ----
        print("");
        print("5) 章节长度分布（字节）");
        long[] lens = chapters.stream().mapToLong(Chapter::byteLength).toArray();
        long[] sorted = lens.clone();
        java.util.Arrays.sort(sorted);
        long min = sorted[0];
        long max = sorted[sorted.length - 1];
        long median = sorted[sorted.length / 2];
        long p90 = sorted[(int) (sorted.length * 0.9)];
        print(String.format(Locale.ROOT, "   min=%d  median=%d  p90=%d  max=%d", min, median, p90, max));
        print(String.format(Locale.ROOT, "   （换算：median=%.1f KB  p90=%.1f KB  max=%.1f KB）",
                median / 1024.0, p90 / 1024.0, max / 1024.0));
        List<Integer> mergers = new ArrayList<>();
        List<Integer> bigOutliers = new ArrayList<>();
        List<Integer> smallOutliers = new ArrayList<>();
        for (int i = 0; i < lens.length; i++) {
            if (lens[i] > median * 5.0) {
                bigOutliers.add(i);
            } else if (lens[i] > median * 2.5) {
                mergers.add(i);
            }
            if (lens[i] < median / 5.0) {
                smallOutliers.add(i);
            }
        }
        print("   超过 median×5 的章节数 = " + bigOutliers.size() + "   （漏切了多章）");
        bigOutliers.stream().limit(6).forEach(i -> print(String.format(Locale.ROOT,
                "      #%d  %s  (%d B, 是 median 的 %.1f 倍)",
                i, shorten(chapters.get(i).title(), 28), lens[i], lens[i] / (double) median)));
        print("   median×2.5 ~ ×5 的章节数 = " + mergers.size() + "   （大概漏切了一章）");
        mergers.stream().limit(6).forEach(i -> print(String.format(Locale.ROOT,
                "      #%d  %s  (%d B, 是 median 的 %.1f 倍)",
                i, shorten(chapters.get(i).title(), 28), lens[i], lens[i] / (double) median)));
        print("   小于 median/5 的章节数 = " + smallOutliers.size());
        smallOutliers.stream().limit(6).forEach(i -> print(String.format(Locale.ROOT,
                "      #%d  %s  (%d B)", i, shorten(chapters.get(i).title(), 28), lens[i])));
        if (!bigOutliers.isEmpty()) {
            flags.add(bigOutliers.size() + " 个超长章节（median×5 以上，可能是漏切）");
        }
        if (!mergers.isEmpty()) {
            flags.add(mergers.size() + " 个偏长章节（median×2.5 以上，可能漏切了一章）");
        }
        if (!smallOutliers.isEmpty()) {
            flags.add(smallOutliers.size() + " 个超短章节（可能是过切）");
        }

        // ---- 6. 标题检查 ----
        print("");
        print("6) 标题检查");
        Map<String, Integer> titleCount = new HashMap<>();
        int tooLong = 0;
        int noNumber = 0;
        int nonBody = 0;
        int volumeOnly = 0;
        List<String> volumeSamples = new ArrayList<>();
        for (Chapter c : chapters) {
            String t = c.title();
            titleCount.merge(t, 1, Integer::sum);
            if (t.length() > 30) {
                tooLong++;
            }
            if (!CHAPTER_NUM.matcher(t).find()) {
                noNumber++;
            }
            for (String k : NON_BODY) {
                if (t.contains(k)) {
                    nonBody++;
                    break;
                }
            }
            if (c.byteLength() < 200 && containsAny(t, VOLUME_UNITS)) {
                volumeOnly++;
                if (volumeSamples.size() < 5) {
                    volumeSamples.add(String.format(Locale.ROOT, "      #%d 「%s」(%d B)",
                            c.index(), shorten(t, 30), c.byteLength()));
                }
            }
        }
        List<Map.Entry<String, Integer>> dups = titleCount.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5).toList();
        int dupKinds = (int) titleCount.values().stream().filter(v -> v > 1).count();
        int dupExtra = titleCount.values().stream().mapToInt(v -> v - 1).sum();
        print("   标题去重后种类数 = " + titleCount.size() + " / " + chapters.size());
        print("   重复标题的「种类数」= " + dupKinds + "，重复多出的条目数 = " + dupExtra);
        dups.forEach(e -> print("      「" + shorten(e.getKey(), 30) + "」出现 " + e.getValue() + " 次"));
        print("   标题里抽不出「第X章」的 = " + noNumber);
        print("   含「番外/作品相关/楔子…」等非正文章特征的 = " + nonBody);
        print("   超过 30 字的超长标题 = " + tooLong);
        print("   卷标题独立成章（<200B 且含卷/部/篇，目录里会显示成空章节）= " + volumeOnly);
        volumeSamples.forEach(CorpusProbe::print);
        if (dupExtra > chapters.size() * 0.01) {
            flags.add("重复标题偏多（" + dupExtra + " 条重复）");
        }
        if (tooLong > 0) {
            flags.add(tooLong + " 个超长标题");
        }
        if (volumeOnly > 0) {
            flags.add(volumeOnly + " 个卷标题独立成章（目录里是空条目，建议折叠成分组）");
        }

        // ---- 7. 序号单调性 + 跳号（漏切最灵敏的信号） ----
        print("");
        print("7) 章节序号单调性 / 跳号");
        print("   （按「章 / 卷」分开比较，和生产逻辑一致 —— 否则「第二卷」的卷号会冒充章号，");
        print("     把「第二章 -> 第九十一章」算成缺 88 个，产生假警报）");
        Set<Integer> parsedNumbers = new HashSet<>();
        Map<ChapterTitleMatcher.Kind, Integer> prevOfKind = new EnumMap<>(ChapterTitleMatcher.Kind.class);
        int numbered = 0;
        int regressions = 0;
        int duplicates = 0;
        int jumps = 0;
        int missingTotal = 0;
        int maxSeen = 0;
        int volumeMarks = 0;
        int specials = 0;
        List<String> regSamples = new ArrayList<>();
        List<String> jumpSamples = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            var maybe = ChapterTitleMatcher.match(chapter.title());
            if (maybe.isEmpty()) {
                continue;   // 「开篇」「全文」这类没有章节号，不参与序列
            }
            ChapterTitleMatcher.Match m = maybe.get();
            if (m.kind() == ChapterTitleMatcher.Kind.VOLUME) {
                volumeMarks++;
                prevOfKind.clear();      // 生产逻辑：卷标题会重置计数
                continue;
            }
            if (!m.numbered()) {
                specials++;
                continue;
            }
            int n = m.number();
            numbered++;
            parsedNumbers.add(n);
            Integer prev = prevOfKind.get(m.kind());
            if (prev != null) {
                if (n < prev) {
                    regressions++;
                    if (regSamples.size() < 6) {
                        regSamples.add(String.format(Locale.ROOT,
                                "      #%d  %s   (序号 %d < 上一章 %d)",
                                i, shorten(chapter.title(), 26), n, prev));
                    }
                } else if (n == prev) {
                    duplicates++;
                    if (regSamples.size() < 6) {
                        regSamples.add(String.format(Locale.ROOT,
                                "      #%d  %s   (序号 %d 与上一章重复)",
                                i, shorten(chapter.title(), 26), n));
                    }
                } else if (n > prev + 1) {
                    jumps++;
                    missingTotal += n - prev - 1;
                    if (jumpSamples.size() < 8) {
                        jumpSamples.add(String.format(Locale.ROOT,
                                "      #%d  %s   (序号 %d -> %d，缺 %d 个)",
                                i, shorten(chapter.title(), 26), prev, n, n - prev - 1));
                    }
                }
            }
            prevOfKind.put(m.kind(), n);
            maxSeen = Math.max(maxSeen, n);
        }
        print("   能抽出序号的章节数 = " + numbered + " / " + chapters.size());
        print("   最大序号 = " + maxSeen + "   卷标题数 = " + volumeMarks + "   特殊章名数 = " + specials);
        print("   序号倒退的处数 = " + regressions + "   序号重复的处数 = " + duplicates);
        regSamples.forEach(CorpusProbe::print);
        print("   序号跳号的处数 = " + jumps + "   累计缺号 = " + missingTotal
                + "   （缺号最可能的原因就是漏切：几章被并成了一章）");
        jumpSamples.forEach(CorpusProbe::print);
        if (numbered > 0 && regressions > numbered * 0.02) {
            flags.add("序号倒退 " + regressions + " 处（占已编号章节 "
                    + String.format(Locale.ROOT, "%.1f%%", regressions * 100.0 / numbered) + "）");
        }
        if (jumps > 0) {
            flags.add("序号跳号 " + jumps + " 处、累计缺号 " + missingTotal + " 个（强烈提示漏切）");
        }

        // ---- 8. 漏切定位 ----
        print("");
        print("8) 漏切定位");
        byte[] rawBytes = Files.readAllBytes(file);
        String[] lines = det.codec().decodeAll(rawBytes).split("\n", -1);
        scanRejectedHeadings(lines, flags);
        print("");
        scanInlineHeadings(lines, chapters, parsedNumbers, maxSeen, flags);

        // ---- 9. 抽样解码 ----
        print("");
        print("9) 抽样解码（第 0 章 / 中间章 / 末章）");
        int[] picks = {0, chapters.size() / 2, chapters.size() - 1};
        for (int pi : picks) {
            Chapter sk = chapters.get(pi);
            long ts = System.nanoTime();
            Chapter loaded = parser.loadChapter(file, sk);
            long loadMs = (System.nanoTime() - ts) / 1_000_000;
            StringBuilder text = new StringBuilder();
            int paras = 0;
            int heads = 0;
            int maxParaLen = 0;
            for (ChapterBlock b : loaded.blocks()) {
                switch (b) {
                    case ChapterBlock.Heading h -> {
                        heads++;
                        text.append(h.text()).append('\n');
                    }
                    case ChapterBlock.Paragraph p -> {
                        paras++;
                        maxParaLen = Math.max(maxParaLen, p.text().length());
                        text.append(p.text()).append('\n');
                    }
                    case ChapterBlock.Image img -> text.append("<img ").append(img.resourcePath()).append(">\n");
                }
            }
            String t = text.toString();
            long cjk = t.codePoints().filter(CorpusProbe::isCjkOrPunct).count();
            long nonBlank = t.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
            int bad = countOf(t, '\uFFFD');
            boolean kun = t.contains("锟");
            print(String.format(Locale.ROOT,
                    "   #%d 「%s」 blocks=%d (标题 %d / 段落 %d)  加载 %d ms  最长段落 %d 字",
                    pi, shorten(sk.title(), 26), loaded.blocks().size(), heads, paras, loadMs, maxParaLen));
            print(String.format(Locale.ROOT,
                    "      中文占比 = %.1f%%  (中日韩汉字+中文标点 / 非空白字符)",
                    nonBlank == 0 ? 0.0 : cjk * 100.0 / nonBlank));
            print("      U+FFFD 个数 = " + bad + "   含「锟」= " + kun
                    + "   首行 = " + shorten(firstLine(t), 40));
            if (bad > 0 || kun) {
                flags.add("第 " + pi + " 章解码出现乱码标记");
            }
            if (nonBlank > 200 && cjk * 100.0 / nonBlank < 80.0) {
                flags.add("第 " + pi + " 章中文占比偏低（"
                        + String.format(Locale.ROOT, "%.1f%%", cjk * 100.0 / nonBlank) + "）");
            }
            if (loaded.blocks().isEmpty()) {
                flags.add("第 " + pi + " 章加载后没有任何内容块");
            }
        }

        print("");
        if (flags.isEmpty()) {
            print("结论：全部检查通过。");
        } else {
            print("结论：有 " + flags.size() + " 项需要复核 ——");
            flags.forEach(f -> print("   ⚠ " + f));
        }
        return new Verdict(ascii, shown, chapters.size(), parseMs, flags);
    }

    // ------------------------------------------------------------------ 漏切定位

    /**
     * 8.1 行首候选的形态校验结果 —— 看「候选标题数」为什么比文件里的标题行少。
     *
     * <p>做法：先用<b>只看形态</b>的宽松模式把「行首长得就像标题」的行捞出来，
     * 再逐行送进生产代码的 {@link ChapterTitleMatcher#inspect}，按<b>拒绝原因</b>归类计数。
     * 只报「拒了多少条」没用，要知道<b>拒的理由</b>才能判断是误杀还是该拒。
     *
     * <p>本次审计就是靠这一项定位到那个高频误杀的：章节名本身带感叹号
     * （{@code 第007章  休！}），被「含句末标点即正文」这条规则连带毙掉，
     * 于是几百章并进了前一章 —— 序号跳号、章节超长，两个症状一起出现。
     */
    private static void scanRejectedHeadings(String[] lines, List<String> flags) {
        print("   8.1 行首候选的形态校验结果");
        int loose = 0;
        int accepted = 0;
        Map<String, Integer> reasonCount = new LinkedHashMap<>();
        Map<String, List<String>> reasonSamples = new LinkedHashMap<>();
        for (int li = 0; li < lines.length; li++) {
            String s = lines[li].replace('\r', ' ').strip();
            if (s.isEmpty() || !LOOSE_HEADING_AT_START.matcher(s).find()) {
                continue;
            }
            loose++;
            ChapterTitleMatcher.Verdict v = ChapterTitleMatcher.inspect(s);
            if (v.accepted()) {
                accepted++;
                continue;
            }
            reasonCount.merge(v.reason(), 1, Integer::sum);
            reasonSamples.computeIfAbsent(v.reason(), k -> new ArrayList<>())
                    .add(String.format(Locale.ROOT, "            行%-7d %s", li, shorten(s, 56)));
        }
        int rejected = loose - accepted;
        print("      行首形态像标题的行数     = " + loose);
        print("      其中被接受（= 候选标题） = " + accepted);
        print("      被形态校验拒绝           = " + rejected);
        if (rejected > 0) {
            print("      拒绝原因分布（按条数降序）：");
            reasonCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> {
                        print("        " + e.getValue() + " 条  " + e.getKey());
                        reasonSamples.get(e.getKey()).stream().limit(3).forEach(CorpusProbe::print);
                    });
            flags.add("行首标题被形态校验拒 " + rejected + " 条（见 8.1 原因分布）");
        }
    }

    /**
     * 8.2 扫描「标题被拼在段落里」的行 —— 下载站抓取产物的典型脏数据。
     *
     * <p>做法：整行先走一遍 {@link ChapterTitleMatcher}；不通过的话，
     * 再从行尾往前（最多回溯 42 个字符，因为超长就一定被形态校验毙掉）
     * 找每一个「第」字，取它到行尾的子串重新送进同一个 matcher。
     * 这样用的是<b>和生产代码完全一样</b>的判定规则，不存在两套逻辑漂移的问题。
     *
     * <p>再叠一层验证：把抽到的序号和「已解析出的序号集合」比对。
     * <ul>
     *   <li>序号<b>落在空档里</b>（该序号没被解析到，但不超过最大序号太多）→ 几乎必然是漏切；</li>
     *   <li>序号<b>已存在</b> → 多半是正文里的引述（"第一回合"），不算。</li>
     * </ul>
     * 这层过滤把「第一回合」这类噪声干净地筛掉了。
     */
    private static void scanInlineHeadings(String[] lines, List<Chapter> chapters,
                                           Set<Integer> parsedNumbers,
                                           int maxParsed, List<String> flags) {
        print("   8.2 行内 / 段尾标题（拼接形态）");
        int strict = 0;
        int inline = 0;
        int confirmed = 0;
        int noise = 0;
        List<String> confirmedSamples = new ArrayList<>();
        List<String> noiseSamples = new ArrayList<>();

        for (int li = 0; li < lines.length; li++) {
            String s = lines[li].replace('\r', ' ').strip();
            if (s.isEmpty()) {
                continue;
            }
            if (ChapterTitleMatcher.match(s).isPresent()) {
                strict++;
                continue;
            }
            if (LOOSE_HEADING_AT_START.matcher(s).find()) {
                continue;   // 已在 8.1 统计，不重复
            }
            // 从行尾往前回溯，逐个子串试
            int from = Math.max(0, s.length() - 42);
            ChapterTitleMatcher.Match hit = null;
            for (int i = from; i < s.length(); i++) {
                if (s.charAt(i) != '第') {
                    continue;
                }
                var m = ChapterTitleMatcher.match(s.substring(i));
                if (m.isPresent()) {
                    hit = m.get();
                    break;   // 取最靠前（最完整）的那个
                }
            }
            if (hit == null) {
                continue;
            }
            inline++;
            int n = hit.number();
            boolean inGap = hit.numbered() && n >= 1 && n <= maxParsed + 50
                    && !parsedNumbers.contains(n);
            if (inGap) {
                confirmed++;
                if (confirmedSamples.size() < 8) {
                    confirmedSamples.add(String.format(Locale.ROOT,
                            "            行%-7d 序号=%-5d ... %s", li, n, shorten(tail(s, 52), 52)));
                }
            } else {
                noise++;
                if (noiseSamples.size() < 4) {
                    noiseSamples.add(String.format(Locale.ROOT,
                            "            行%-7d 序号=%-5d（已存在，判为正文引述） ... %s",
                            li, n, shorten(tail(s, 44), 44)));
                }
            }
        }

        print("      行首即标题的行数           = " + strict);
        print("      行内/段尾可构成标题的行数   = " + inline);
        print("      其中序号落在已解析空档里的 = " + confirmed + "   ← 这些几乎必然是漏切的章节");
        print("      其余（序号已存在，判为引述）= " + noise);
        if (!confirmedSamples.isEmpty()) {
            print("      漏切样例：");
            confirmedSamples.forEach(CorpusProbe::print);
        }
        if (!noiseSamples.isEmpty()) {
            print("      被正确排除的引述样例：");
            noiseSamples.forEach(CorpusProbe::print);
        }
        if (confirmed > 0) {
            flags.add("行内/段尾标题 " + confirmed + " 处疑似漏切（已解析章节只有 "
                    + chapters.size() + " 章）");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 从标题里抽章节序号；抽不到返回 -1。 */
    private static int extractNumber(String title) {
        Matcher m = CHAPTER_NUM.matcher(title);
        if (!m.find()) {
            return -1;
        }
        String raw = m.group(1).trim();
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            // 全角数字转半角
            sb.append(c >= '０' && c <= '９' ? (char) ('0' + (c - '０')) : c);
        }
        String s = sb.toString();
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int n = ChineseNumerals.parse(s);
        return n > 0 ? n : -1;
    }

    private static boolean containsAny(String s, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (s.indexOf(chars.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjkOrPunct(int cp) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || b == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }

    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    private static String firstLine(String s) {
        for (String line : s.split("\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "(空)";
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double mb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0 * 100) / 100.0;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private static void print(String s) {
        System.out.println(s);
    }
}
