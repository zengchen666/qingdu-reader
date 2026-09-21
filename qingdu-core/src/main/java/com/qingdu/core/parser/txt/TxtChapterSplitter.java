package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Chapter;
import com.qingdu.core.text.ByteLine;
import com.qingdu.core.text.LineScanner;
import com.qingdu.core.text.TextCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TXT 章节切分器 —— 项目的核心算法。
 *
 * <p><b>"三重校验"到底在防什么？</b><br>
 * 前两重（格式、形态）在 {@link ChapterTitleMatcher} 里，能挡掉绝大多数正文误判。
 * 但还有一类特别麻烦的情况，必须靠"位置关系"才能识破：<b>目录页</b>。
 * <pre>
 *   目录                      ← 有些 TXT 开头带一份目录
 *   第一章 星尘之始
 *   第二章 远行                ← 这些行长得和真标题一模一样
 *   ...
 *   第二百章 终局
 *   （空行）
 *   第一章 星尘之始            ← 这里才是真正的正文开始
 *   夜空中的星光落下来……
 * </pre>
 * 如果只做正则匹配，目录会被当成分章结果 —— 而且更糟：因为目录里已经出现到
 * "第二百章"，后面真正的"第一章"序号比它小，会被序列校验判为误判并全部丢弃，
 * 最终整本书只剩下一份目录。
 *
 * <p>所以顺序很讲究：<b>先过滤目录，再做序列校验</b>。反过来就全错了。
 *
 * <p><b>第三重校验包含两条规则</b>
 * <ol>
 *   <li><b>目录区过滤</b>：连续 4 个以上候选标题之间几乎没有正文，判定为目录，整段丢弃。</li>
 *   <li><b>序号全局对齐</b>：见 {@link #alignByNumber}。先把候选按"换卷 / 重新从第一
 *       章开始"切成若干<b>段</b>，再在<b>每一段内求最长严格递增子序列</b>，
 *       子序列之外的候选（正文引述、重复标题、错位的高序号）全部丢弃。</li>
 * </ol>
 *
 * <p><b>为什么不是"逐个比较、遇到回退就扔"？</b><br>
 * 早期版本用的是最朴素的贪心规则：维护一个"上一个序号"，谁不大于它谁就被判为引述。
 * 这个规则在真书上有致命的<b>连坐效应</b> —— 只要冒出一个错位的高序号并被接受，
 * 它后面<b>所有</b>合法章节（序号都比它小）会被一路误杀，直到序号重新超过它为止。
 * 语料实测：《武动乾坤》源文件里在 248 章之后混进了一个"第四百四十九章"的标题，
 * 于是 249~448 共 200 章的候选被全部连坐丢弃，全书只切出 936 章（真书 1333 章）。
 * 换成"求最长递增子序列"后，那一个错位序号会被自然排除在外，而后面 200 章完整保留
 * （936 → 1264 章，序号缺口从 372 个降到 42 个）。
 *
 * <p>子序列而不是"最长连续递增段"，是因为源文件本身就常有缺章、错字章号，
 * 要求连续会把整段一起丢掉。
 *
 * <p><b>加在候选收集阶段的第四条通道：行内标题切分。</b>
 * 有些书源把作者感言和章节标题拼成了同一行（标题在行尾），整行超长，
 * 会被形态校验当正文毙掉。所以整行被拒时，还会再试一次"从行尾切出标题"，
 * 见 {@link #findInlineHeading}。它产出的是候选，所以<b>同样要过第三重校验</b>。
 */
public final class TxtChapterSplitter {

    /** 候选标题数量少于这个值，就认为"这本书没有可靠的分章结构"，退化成单章。 */
    public static final int DEFAULT_MIN_CHAPTERS = 3;

    /** 目录区判定：连续多少个候选标题之间没有正文，才算目录。 */
    public static final int TOC_RUN_MIN = 4;

    /**
     * 两个候选标题之间的非空行少于等于这个数，视为"紧挨着"。
     *
     * <p>取 0 而不是 1，是因为空行不算数 —— 目录条目之间就算隔了空行，
     * 非空行数依然是 0。而正文段落之间哪怕只隔一行，也说明中间有内容。
     * 阈值定得越紧，越不容易把真正的章节误判成目录。
     */
    public static final int TOC_MAX_CONTENT_LINES = 0;

    /** 首个章节标题之前的文字超过这个字数，才单独抽成一章"开篇"。 */
    public static final int PREFIX_MIN_CHARS = 30;

    /** 开篇章节的默认标题。 */
    public static final String PREFIX_TITLE = "开篇";

    /** 全书没有可靠分章时的兜底章节标题。 */
    public static final String WHOLE_BOOK_TITLE = "全文";

    /**
     * 切分参数。
     *
     * @param minChapters   有效章节数下限
     * @param filterToc     是否启用目录区过滤
     * @param prefixMinChars 开篇章节的最小字数
     */
    public record Options(int minChapters, boolean filterToc, int prefixMinChars) {

        public static Options defaults() {
            return new Options(DEFAULT_MIN_CHAPTERS, true, PREFIX_MIN_CHARS);
        }

        /** 关闭目录过滤，用于测试和对照实验。 */
        public static Options withoutTocFilter() {
            return new Options(DEFAULT_MIN_CHAPTERS, false, PREFIX_MIN_CHARS);
        }
    }

    /**
     * 切分结果报告。
     *
     * <p>把过程数据一并返回，是这一版特意加的设计。
     * 分章算法最怕"结果不对但不知道为什么"，有了这些计数器，
     * 一眼就能看出是候选太少、还是被目录过滤吃掉了、还是被序列校验毙了。
     *
     * @param chapters           最终章节列表
     * @param candidateCount     通过前两重校验的候选标题数（含行内切出来的）
     * @param inlineHeadings     其中来自"行内标题切分"的数量
     * @param sequenceRejected   被序号全局对齐丢弃的数量（正文引述 / 重复标题 / 错位序号）
     * @param tocDropped         被目录过滤丢弃的数量
     * @param volumeResets       段边界次数（换卷，或序号回到 1 重新计数）
     * @param fallback           是否退化成"单章全文"
     */
    public record Report(
            List<Chapter> chapters,
            int candidateCount,
            int inlineHeadings,
            int sequenceRejected,
            int tocDropped,
            int volumeResets,
            boolean fallback
    ) {
        /** 一句话摘要，方便打日志。 */
        public String summary() {
            return String.format(
                    "候选 %d 个（含行内切出 %d）→ 目录丢弃 %d → 序号对齐丢弃 %d → 分段 %d 处 → 最终 %d 章%s",
                    candidateCount, inlineHeadings, tocDropped, sequenceRejected, volumeResets,
                    chapters.size(), fallback ? "（退化为全文）" : "");
        }
    }

    /**
     * 一个候选标题。
     *
     * @param lineIndex   所在行号，用来数"两个候选之间隔了多少正文行"（目录过滤要用）
     * @param line        所在行
     * @param match       标题匹配结果
     * @param startOffset 本章正文在文件里的<b>起始字节偏移</b>。
     *                    绝大多数情况等于 {@code line.start()}；但<b>行内标题</b>例外 ——
     *                    它指向标题子串的起点，于是这一行前半段的作者感言自然归到
     *                    上一章末尾（语义上正是这样）。
     */
    private record Hit(int lineIndex, ByteLine line, ChapterTitleMatcher.Match match, long startOffset) {
    }

    /** 从一行正文里"切"出来的标题。 */
    private record InlineHeading(ChapterTitleMatcher.Match match, long startOffset) {
    }

    private final Options options;

    public TxtChapterSplitter() {
        this(Options.defaults());
    }

    public TxtChapterSplitter(Options options) {
        this.options = options == null ? Options.defaults() : options;
    }

    /** 只要章节列表的便捷方法。 */
    public List<Chapter> split(byte[] data, TextCodec codec, String bookId) {
        return analyze(data, codec, bookId).chapters();
    }

    /**
     * 完整分析：切出章节并返回过程报告。
     *
     * @param data   文件全部字节
     * @param codec  编解码器（决定换行符怎么算）
     * @param bookId 图书 ID，会写进每个 Chapter
     */
    public Report analyze(byte[] data, TextCodec codec, String bookId) {
        List<ByteLine> lines = LineScanner.scan(data, codec);

        // ---------- 第一重 + 第二重校验（在 ChapterTitleMatcher 内完成） ----------
        List<Hit> candidates = new ArrayList<>();
        int inlineHeadings = 0;
        for (int i = 0; i < lines.size(); i++) {
            ByteLine line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            ChapterTitleMatcher.Verdict verdict = ChapterTitleMatcher.inspect(line.trimmed());
            if (verdict.accepted()) {
                candidates.add(new Hit(i, line, verdict.match(), line.start()));
                continue;
            }
            // ---- 行内标题：整行不像标题，但标题可能被拼在段落末尾 ----
            InlineHeading inline = findInlineHeading(line, codec);
            if (inline != null) {
                candidates.add(new Hit(i, line, inline.match(), inline.startOffset()));
                inlineHeadings++;
            }
        }
        int candidateCount = candidates.size();
        if (candidates.isEmpty()) {
            return singleChapterReport(data, codec, bookId, candidateCount, inlineHeadings, 0, 0, 0);
        }

        // ---------- 第三重校验 · 规则 1：目录区过滤 ----------
        List<Hit> afterToc = options.filterToc() ? dropTableOfContents(candidates, lines) : candidates;
        int tocDropped = candidateCount - afterToc.size();

        // ---------- 第三重校验 · 规则 2 & 3：序号全局对齐 + 分段 ----------
        Alignment alignment = alignByNumber(afterToc);
        List<Hit> kept = alignment.kept();
        int sequenceRejected = alignment.dropped();
        int volumeResets = alignment.boundaries();

        // ---------- 都没剩下或太少：退化为单章 ----------
        if (kept.size() < options.minChapters()) {
            return singleChapterReport(data, codec, bookId,
                    candidateCount, inlineHeadings, sequenceRejected, tocDropped, volumeResets);
        }

        // ---------- 生成章节列表 ----------
        List<Chapter> chapters = new ArrayList<>(kept.size() + 1);

        // 首个标题之前的文字：如果足够长，单独作为"开篇"
        Chapter prefix = buildPrefixChapter(data, codec, bookId, kept.get(0).startOffset());
        int index = 0;
        if (prefix != null) {
            chapters.add(withIndex(prefix, index++));
        }

        for (int i = 0; i < kept.size(); i++) {
            Hit hit = kept.get(i);
            long start = hit.startOffset();
            long end = (i + 1 < kept.size()) ? kept.get(i + 1).startOffset() : data.length;
            chapters.add(Chapter.indexOnly(bookId, index++, hit.match().displayText(), start, end));
        }

        return new Report(chapters, candidateCount, inlineHeadings,
                sequenceRejected, tocDropped, volumeResets, false);
    }

    /**
     * 目录区过滤。
     *
     * <p><b>判据是"候选标题后面紧跟着又一个候选标题"。</b><br>
     * 真章节的标题后面一定跟着大段正文，而目录条目后面立刻就是下一条。
     * 所以对每个候选标题，只看它与<b>下一个候选</b>之间有多少正文行：
     * <pre>
     *   目录条目  第一章 甲          后面 0 行正文    → 紧挨着
     *   目录条目  第二章 乙          后面 0 行正文    → 紧挨着
     *   ...
     *   目录条目  第六章 己          后面 0 行正文    → 紧挨着
     *   真章节    第一章 真正的开始    后面 200 行正文  → 不紧挨
     * </pre>
     * 于是"紧挨着的连续段"正好就是目录本身，真章节天然被排除在外。
     *
     * <p><b>为什么不用"与前一个候选的间隙"来判断？</b><br>
     * 因为目录最后一条与正文第一章之间的间隙同样很小，
     * 按前向间隙会把正文第一章一起划进目录。只看后向间隙就避开了这个陷阱 ——
     * 正文第一章后面是大段正文，它自己就把自己"摘"出去了。
     *
     * <p>只有连续 {@link #TOC_RUN_MIN} 个以上候选都满足"紧挨着"，
     * 才认定整段是目录。个别空章节后面碰巧没内容，不会被误杀。
     */
    private List<Hit> dropTableOfContents(List<Hit> candidates, List<ByteLine> lines) {
        int n = candidates.size();

        // dense[k] = 第 k 个候选与下一个候选之间没有正文
        boolean[] dense = new boolean[n];
        for (int k = 0; k + 1 < n; k++) {
            dense[k] = contentLinesBetween(candidates.get(k), candidates.get(k + 1), lines)
                    <= TOC_MAX_CONTENT_LINES;
        }
        // dense[n-1] 保持 false：最后一个候选后面没有可比对象，一律保留。
        // 这样"整本书只有目录、没有正文"时也不会把章节丢光。

        List<Hit> result = new ArrayList<>(n);
        int i = 0;
        while (i < n) {
            if (!dense[i]) {
                result.add(candidates.get(i));
                i++;
                continue;
            }
            int j = i;
            while (j < n && dense[j]) {
                j++;
            }
            if (j - i < TOC_RUN_MIN) {
                // 太短，不足以断定是目录，原样保留
                for (int k = i; k < j; k++) {
                    result.add(candidates.get(k));
                }
            }
            // 否则整段丢弃
            i = j;
        }
        return result;
    }

    /**
     * 数一数两个候选标题之间有多少"非空行"。
     *
     * <p>只看非空行，是因为目录条目之间常夹着空行，把空行算进去会掩盖
     * "紧挨着"这个特征。而正文段落之间虽然也有空行，但正文行本身数量巨大，
     * 非空行计数会立刻拉开差距。
     */
    private int contentLinesBetween(Hit left, Hit right, List<ByteLine> lines) {
        int count = 0;
        for (int i = left.lineIndex() + 1; i < right.lineIndex(); i++) {
            if (!lines.get(i).isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 序号全局对齐的结果。
     *
     * @param kept       对齐后保留下来的候选
     * @param dropped    被丢弃的候选数
     * @param boundaries 段边界次数（换卷，或序号回到 1 重新计数）
     */
    private record Alignment(List<Hit> kept, int dropped, int boundaries) {
    }

    /**
     * 序号全局对齐 —— 第三重校验的核心。
     *
     * <p><b>两步走</b>：
     * <ol>
     *   <li><b>分段</b>：遇到分卷标题，或"序号重新回到 1"，就在这里断开。
     *       分段的意义是把"每卷从第一章重新数"的书隔离开，
     *       否则求递增子序列会把后面每一卷都当成序号倒退而丢光。</li>
     *   <li><b>段内求最长严格递增子序列</b>：子序列之外的候选全部丢弃。
     *       正文引述的章号、重复贴的标题、错位的高序号，
     *       都无法成为最长递增链的一部分，于是被自动排除；
     *       而合法的章节序列无论中间缺了多少章，都会被完整保留下来。</li>
     * </ol>
     *
     * <p><b>为什么这比"逐个比较"强？</b>贪心规则一旦接受了错位的高序号，
     * 它后面所有更小的合法序号都会被连坐误杀。最长递增子序列是<b>全局最优</b>的视角，
     * 单个异常值只会被排除，不会波及无辜。语料实测：该项改动让《武动乾坤》
     * 从 936 章恢复到 1264 章，《元尊》序号缺口从 66 个降到 24 个。
     */
    private Alignment alignByNumber(List<Hit> candidates) {
        List<Hit> kept = new ArrayList<>(candidates.size());
        int boundaries = 0;
        int segmentStart = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (!startsNewSegment(candidates.get(i), i > segmentStart)) {
                continue;
            }
            kept.addAll(alignSegment(candidates.subList(segmentStart, i)));
            // 段首本身（卷标题，或重新计数的第一章）保留下来
            kept.add(candidates.get(i));
            segmentStart = i + 1;
            boundaries++;
        }
        kept.addAll(alignSegment(candidates.subList(segmentStart, candidates.size())));
        return new Alignment(kept, candidates.size() - kept.size(), boundaries);
    }

    /**
     * 这个候选是否开启新的一段。
     *
     * @param hit               候选
     * @param notFirstInSegment 它是不是本段里的第一个候选。
     *                          段落开头的"第一章"属于正常起点，不算新的段。
     */
    private boolean startsNewSegment(Hit hit, boolean notFirstInSegment) {
        if (!notFirstInSegment) {
            return false;
        }
        ChapterTitleMatcher.Match m = hit.match();
        if (m.kind() == ChapterTitleMatcher.Kind.VOLUME) {
            return true;
        }
        // 序号从大于 1 跳回 1：很多小说每卷都从"第一章"重新数
        return m.numbered() && m.number() == 1;
    }

    /**
     * 段内求最长严格递增子序列（O(n log n)，耐心排序 + 前驱回溯）。
     *
     * <p>特殊章名（楔子 / 番外）没有编号，不参与比较，一律保留。
     * 同一序号重复出现时<b>保留最早的那一条</b> —— 真书里"标题贴两遍"
     * （行尾黏一次、独立成行一次）非常常见，留最早的能让上一章正文
     * 干净地结束在标题之前。
     */
    private List<Hit> alignSegment(List<Hit> segment) {
        int n = segment.size();
        if (n == 0) {
            return List.of();
        }
        boolean[] keep = new boolean[n];
        List<Integer> numbered = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ChapterTitleMatcher.Match m = segment.get(i).match();
            if (m.kind() == ChapterTitleMatcher.Kind.SPECIAL || !m.numbered()) {
                keep[i] = true;
            } else {
                numbered.add(i);
            }
        }

        int size = numbered.size();
        if (size > 0) {
            int[] tails = new int[size];
            int[] tailPos = new int[size];
            int[] prev = new int[size];
            Arrays.fill(prev, -1);
            int length = 0;
            for (int p = 0; p < size; p++) {
                int value = segment.get(numbered.get(p)).match().number();
                int lo = 0;
                int hi = length;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (tails[mid] < value) {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
                if (lo < length && tails[lo] == value) {
                    continue;
                }
                if (lo == length) {
                    length++;
                }
                tails[lo] = value;
                tailPos[lo] = p;
                prev[p] = lo > 0 ? tailPos[lo - 1] : -1;
            }
            for (int p = tailPos[length - 1]; p >= 0; p = prev[p]) {
                keep[numbered.get(p)] = true;
            }
        }

        List<Hit> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result.add(segment.get(i));
            }
        }
        return result;
    }

    /**
     * 行内标题切分 —— 从一行正文里把章节标题"切"出来。
     *
     * <p><b>为什么需要它？</b><br>
     * 下载站抓取或转码产出的 TXT 里，章节标题经常被粘在作者感言的末尾，
     * 整行变成一句超长的正文：
     * <pre>
     *   （早...麻烦投一张推荐票吧，谢谢第七章淬体第四重
     *   （求收藏，求推荐票，麻烦大家了，谢谢第八章冲突
     *   正文 第两百七十三章 九龙典之威
     * </pre>
     * 这种行过不了形态校验（整行开头是"（"或"正文"，不是"第"），于是那一章
     * 被并进了前一章。用四本真书做的语料审计里，单《武动乾坤》一本就有
     * <b>上千章</b>因此被漏切（全书 1333 章只分出了 295 章）。
     *
     * <p><b>做法</b>：从行尾往前找「第」字，取它到行尾的子串重新送进
     * {@link ChapterTitleMatcher}。用的是<b>和生产代码同一个</b>判定函数，
     * 不存在两套规则各走各的。命中后这一章的起点就是标题子串的字节位置，
     * 前缀那段感言自然归到上一章末尾 —— 语义上正是这样。
     *
     * <p><b>注意不能按"整行够长吗"来预筛。</b>
     * 曾经想当然地加了"整行超过标题上限才尝试切分"的前置判断，结果像
     * {@code （求收藏，求推荐票，麻烦大家了，谢谢第八章冲突} 这种只有 24 字的
     * 拼接行全被跳过 —— 那是实打实的标题，只是前缀短而已。
     * 现在一律尝试，靠两层机制控制误收：
     * <ol>
     *   <li>matcher 自身的规则（正文引述常带句末标点或有超长子串）；</li>
     *   <li><b>切出来的标题里不能有逗号/分号</b> —— 真实章节名几乎不用这些标点，
     *       而"第一回合的交手中，便是败得如此的凄惨？"这类句子尾巴一定有。
     *       这条判据来自实测：它精确挡住了唯一一类误收。</li>
     * </ol>
     * 最后还有第三重"序号单调性"校验兜底。<b>语料实测</b>：四本书切出的行内候选里，
     * 绝大多数序号都落在真实序号空档上。
     *
     * <p><b>宽字符编码直接放弃。</b>{@link TextCodec#encode} 在 UTF-16 / UTF-32 下
     * 长度不可靠（会带 BOM、字节序也可能不同）。宁可少认几个标题，
     * 也不能给出错误的字节偏移 —— 那会让正文整体错位。
     */
    private InlineHeading findInlineHeading(ByteLine line, TextCodec codec) {
        if (codec.isWideUnit()) {
            return null;
        }
        String text = line.text();
        if (text == null || text.length() < 2) {
            return null;
        }
        // 回溯窗口取"标题长度上限"：再往前找的话子串一定超长、必然被拒，白算
        int from = Math.max(0, text.length() - ChapterTitleMatcher.MAX_LINE_LENGTH);
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) != '第') {
                continue;
            }
            var match = ChapterTitleMatcher.match(text.substring(i));
            if (match.isEmpty() || hasClausePunctuation(match.get().title())) {
                continue;
            }
            int prefixBytes = codec.encode(text.substring(0, i)).length;
            if (prefixBytes <= 0) {
                // 标题顶在行首 —— 那整行本来就该被接受，轮不到这里
                continue;
            }
            return new InlineHeading(match.get(), line.start() + prefixBytes);
        }
        return null;
    }

    /**
     * 标题里有没有"分句标点"。
     *
     * <p>逗号、顿号、分号是"这句话还没说完"的标志。真实章节名极少用它们，
     * 而句子尾巴上的引述一定有 —— 这是区分"拼接标题"和"正文引述"最锋利的一刀。
     * 冒号（{@code :}）不算：它常出现在章节名里（{@code 玄阶高级斗技:八极崩}）。
     */
    private boolean hasClausePunctuation(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        for (int i = 0; i < title.length(); i++) {
            if ("，,、；;".indexOf(title.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首个章节标题之前的文字，够长就单独成一章。
     *
     * <p>很多小说开头有作者的话、免责声明、人物介绍，直接丢掉会让用户觉得"书少了内容"；
     * 但只有几个字的话（比如残留下来的空行）又不值得占一个目录项。
     */
    private Chapter buildPrefixChapter(byte[] data, TextCodec codec, String bookId, long firstStart) {
        int bom = TextCodec.bomLength(data);
        if (firstStart <= bom) {
            return null;
        }
        int chars = countNonBlankChars(data, codec, bom, (int) firstStart);
        if (chars < options.prefixMinChars()) {
            return null;
        }
        return Chapter.indexOnly(bookId, 0, PREFIX_TITLE, bom, firstStart);
    }

    private int countNonBlankChars(byte[] data, TextCodec codec, int from, int to) {
        String text = codec.decode(data, from, to - from);
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\u3000') {
                count++;
            }
        }
        return count;
    }

    /** 构造"整本书只有一章"的兜底结果。 */
    private Report singleChapterReport(byte[] data, TextCodec codec, String bookId,
                                       int candidateCount, int inlineHeadings, int sequenceRejected,
                                       int tocDropped, int volumeResets) {
        int bom = TextCodec.bomLength(data);
        Chapter whole = Chapter.indexOnly(bookId, 0, WHOLE_BOOK_TITLE, bom, data.length);
        return new Report(List.of(whole), candidateCount, inlineHeadings, sequenceRejected,
                tocDropped, volumeResets, true);
    }

    /** 重新指定章节序号。record 是不可变的，所以要造一个新对象。 */
    private Chapter withIndex(Chapter chapter, int index) {
        return Chapter.indexOnly(chapter.bookId(), index, chapter.title(),
                chapter.startOffset(), chapter.endOffset());
    }
}
