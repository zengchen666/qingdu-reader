package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Chapter;
import com.qingdu.core.text.ByteLine;
import com.qingdu.core.text.LineScanner;
import com.qingdu.core.text.TextCodec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>第三重校验包含三条规则</b>
 * <ol>
 *   <li><b>目录区过滤</b>：连续 4 个以上候选标题之间几乎没有正文，判定为目录，整段丢弃。</li>
 *   <li><b>序号单调递增</b>：同一类单位（章 / 卷）的序号必须严格递增，
 *       否则说明是正文里的"引述"，不是真标题。</li>
 *   <li><b>卷边界重置</b>：出现分卷标题，或序号从大于 1 跳回 1 时，
 *       视为新一卷开始，重置计数 —— 很多小说每卷都从"第一章"重新数。</li>
 * </ol>
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
     * @param candidateCount     通过前两重校验的候选标题数
     * @param sequenceRejected   被序号单调性规则拒绝的数量
     * @param tocDropped         被目录过滤丢弃的数量
     * @param volumeResets       触发卷边界重置的次数
     * @param fallback           是否退化成"单章全文"
     */
    public record Report(
            List<Chapter> chapters,
            int candidateCount,
            int sequenceRejected,
            int tocDropped,
            int volumeResets,
            boolean fallback
    ) {
        /** 一句话摘要，方便打日志。 */
        public String summary() {
            return String.format(
                    "候选 %d 个 → 目录丢弃 %d → 序号拒绝 %d → 卷重置 %d → 最终 %d 章%s",
                    candidateCount, tocDropped, sequenceRejected, volumeResets,
                    chapters.size(), fallback ? "（退化为全文）" : "");
        }
    }

    /** 一个候选标题：行号 + 行本身 + 匹配结果。 */
    private record Hit(int lineIndex, ByteLine line, ChapterTitleMatcher.Match match) {
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
        for (int i = 0; i < lines.size(); i++) {
            ByteLine line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            ChapterTitleMatcher.Verdict verdict = ChapterTitleMatcher.inspect(line.trimmed());
            if (verdict.accepted()) {
                candidates.add(new Hit(i, line, verdict.match()));
            }
        }
        int candidateCount = candidates.size();
        if (candidates.isEmpty()) {
            return singleChapterReport(data, codec, bookId, candidateCount, 0, 0, 0);
        }

        // ---------- 第三重校验 · 规则 1：目录区过滤 ----------
        List<Hit> afterToc = options.filterToc() ? dropTableOfContents(candidates, lines) : candidates;
        int tocDropped = candidateCount - afterToc.size();

        // ---------- 第三重校验 · 规则 2 & 3：序号单调递增 + 卷边界重置 ----------
        List<Hit> kept = new ArrayList<>();
        Map<ChapterTitleMatcher.Kind, Integer> lastNumber = new EnumMap<>(ChapterTitleMatcher.Kind.class);
        int sequenceRejected = 0;
        int volumeResets = 0;

        for (Hit hit : afterToc) {
            ChapterTitleMatcher.Match m = hit.match();

            // 分卷标题：接受，并重置章节计数（新一卷的章节号会从 1 重新开始）
            if (m.kind() == ChapterTitleMatcher.Kind.VOLUME) {
                kept.add(hit);
                lastNumber.clear();
                volumeResets++;
                continue;
            }
            // 特殊章名（楔子、番外）：没有编号，不参与序列校验
            if (!m.numbered()) {
                kept.add(hit);
                continue;
            }

            Integer last = lastNumber.get(m.kind());
            if (last != null) {
                if (m.number() <= last) {
                    // 序号回退 → 判定为正文里的引述
                    // 唯一的例外：从大于 1 跳回 1，这是"新一卷从第一章开始"的常见写法
                    if (m.number() == 1 && last > 1) {
                        volumeResets++;
                        lastNumber.put(m.kind(), 1);
                        kept.add(hit);
                        continue;
                    }
                    sequenceRejected++;
                    continue;
                }
            }
            lastNumber.put(m.kind(), m.number());
            kept.add(hit);
        }

        // ---------- 都没剩下或太少：退化为单章 ----------
        if (kept.size() < options.minChapters()) {
            return singleChapterReport(data, codec, bookId,
                    candidateCount, sequenceRejected, tocDropped, volumeResets);
        }

        // ---------- 生成章节列表 ----------
        List<Chapter> chapters = new ArrayList<>(kept.size() + 1);

        // 首个标题之前的文字：如果足够长，单独作为"开篇"
        Chapter prefix = buildPrefixChapter(data, codec, bookId, kept.get(0).line().start());
        int index = 0;
        if (prefix != null) {
            chapters.add(withIndex(prefix, index++));
        }

        for (int i = 0; i < kept.size(); i++) {
            Hit hit = kept.get(i);
            long start = hit.line().start();
            long end = (i + 1 < kept.size()) ? kept.get(i + 1).line().start() : data.length;
            chapters.add(Chapter.indexOnly(bookId, index++, hit.match().displayText(), start, end));
        }

        return new Report(chapters, candidateCount, sequenceRejected, tocDropped, volumeResets, false);
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
                                       int candidateCount, int sequenceRejected,
                                       int tocDropped, int volumeResets) {
        int bom = TextCodec.bomLength(data);
        Chapter whole = Chapter.indexOnly(bookId, 0, WHOLE_BOOK_TITLE, bom, data.length);
        return new Report(List.of(whole), candidateCount, sequenceRejected,
                tocDropped, volumeResets, true);
    }

    /** 重新指定章节序号。record 是不可变的，所以要造一个新对象。 */
    private Chapter withIndex(Chapter chapter, int index) {
        return Chapter.indexOnly(chapter.bookId(), index, chapter.title(),
                chapter.startOffset(), chapter.endOffset());
    }
}
