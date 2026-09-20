package com.qingdu.core.parser.txt;

import com.qingdu.common.util.ChineseNumerals;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节标题识别器 —— 判断"这一行是不是章节标题"。
 *
 * <p><b>这是整个分章算法的第一道也是最难的一道关。</b><br>
 * 难点在于：{@code 第一章} 这三个字既可能出现在标题行，也可能出现在正文里：
 * <pre>
 *   第一章 星尘之始                  ← 标题，要认出来
 *   "第三章的内容我早就看过了。"        ← 正文里提到章节，绝不能当成标题
 *   # 第一章 星尘之始                ← 有些书用 Markdown 语法
 * </pre>
 * 只靠正则必然误伤，所以本类只负责<b>前两重校验</b>，第三重（序列校验）
 * 交给 {@link TxtChapterSplitter} 统一做。职责分开的好处是：
 * "这一行长什么样"和"这一行在全书里的位置合不合理"是两个独立问题。
 *
 * <p><b>前两重校验</b>
 * <ol>
 *   <li><b>格式校验</b>：整行必须匹配章节标题的正则结构
 *       （第 + 数字 + 量词，或楔子/番外这类特殊章名）</li>
 *   <li><b>形态校验</b>：标题行的"体态"要像标题 ——
 *       短、不含句末标点、不以虚词开头</li>
 * </ol>
 */
public final class ChapterTitleMatcher {

    /** 整行（含章节号）的最大字符数。超过这个长度基本可以断定是正文句子。 */
    public static final int MAX_LINE_LENGTH = 40;

    /** 章节名部分的最大字符数，例如"星尘之始"是 4 个字符。 */
    public static final int MAX_TITLE_LENGTH = 20;

    /**
     * 章节标题的正则。
     *
     * <p>拆开看是三段：
     * <pre>
     *   段 1（章号）：第 + 数字 + 量词
     *       数字可以是阿拉伯数字（1 / 13 / １２３全角）、中文数字（一百二十三）
     *       量词包含 章/节/回（章节单位）与 卷/部/篇（分卷单位）
     *
     *   段 2（特殊章名）：楔子 / 序章 / 尾声 / 番外 … 这些没有章号
     *
     *   段 3（章名）：可选的分隔符 + 标题文字
     * </pre>
     *
     * <p><b>为什么分隔符里不包含顿号和逗号？</b><br>
     * 因为"第三章，他离开了"这种正文句子会因此被误判成标题 ——
     * 而中文小说的章节名几乎不会用逗号分隔章号。宁可少认几个怪写法，
     * 也不要让正文混进目录。这是一个刻意的取舍。
     */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^[\\s\\u3000]*"
                    + "(?:"
                    // ---- 段 1：第 X 章 / 第 X 卷 ----
                    + "第[\\s\\u3000]*(?<num>[0-9]{1,7}|[０-９]{1,7}"
                    + "|[〇○零一二三四五六七八九两壹贰叁肆伍陆柒捌玖十拾百佰千仟万萬亿億]{1,12})"
                    + "[\\s\\u3000]*(?<unit>[章节回卷部篇集幕])"
                    + "|"
                    // ---- 段 2：特殊章名 ----
                    + "(?<special>楔子|序章|序言|序幕|引子|前言|后记|尾声|终章|终篇|后序|番外|外传)"
                    + "[\\s\\u3000]*(?<specialNum>[0-9０-９]{1,3}|[〇○零一二三四五六七八九十]{1,4})?"
                    + ")"
                    + "[\\s\\u3000]*[:：.·\\-—－]?[\\s\\u3000]*"
                    + "(?<title>[^\\r\\n]{0,40})"
                    + "$");

    /**
     * 句末标点。标题行里出现这些字符，基本可以断定是正文。
     * 注意中文省略号是单独一个字符 {@code …}，不 ban 掉它的话
     * "第一章…"这种拖长音的正文会被误收。
     */
    private static final String SENTENCE_ENDINGS = "。！？；…‥!?;";

    /** 成对括号，用于剥掉 {@code 【第一章 觉醒】} 这种包裹写法。 */
    private static final String BRACKET_PAIRS = "【】[]{}（）()《》";

    /** 不能作为章节名开头的虚词。 */
    private static final String FORBIDDEN_TITLE_PREFIX = "的了着过吧呢吗啊嘛哦呀是";

    /**
     * 不能作为章节名开头的标点。
     *
     * <p>这条规则专门堵住"第三章，他离开了这座城"这类正文句子：
     * 逗号不在句末标点集合里，正则又允许"章号后面直接跟标题"，
     * 于是「，他离开了这座城」会被整段当成章节名。
     * 而真正的章节名几乎不可能以标点开头。
     */
    private static final String PUNCTUATION_PREFIX = "，。、；：！？…‥—～·「」『』【】《》（）()\"'“”‘’";

    /** 章节单位类型。 */
    public enum Kind {
        /** 章 / 节 / 回 / 集 / 幕 —— 正文的常规切分单位 */
        CHAPTER,
        /** 卷 / 部 / 篇 —— 更大粒度的分卷单位 */
        VOLUME,
        /** 楔子 / 番外 / 尾声 —— 没有编号的特殊章名 */
        SPECIAL
    }

    /**
     * 匹配成功的章节标题。
     *
     * @param number    解析出的章节号。特殊章名为 0
     * @param rawNumber 原始章号文本，如"一百二十三"、"13"
     * @param title     章节名（可能是空字符串，例如"第一章"后面什么都没写）
     * @param kind      章节单位类型
     * @param rawLine   归一化后的完整标题行，例如"第一百二十三章 星尘之始"
     */
    public record Match(int number, String rawNumber, String title, Kind kind, String rawLine) {

        /** 是否有编号。特殊章名（楔子、番外）没有编号，不参与序列校验。 */
        public boolean numbered() {
            return kind != Kind.SPECIAL;
        }

        /**
         * 用于界面显示的章节标题。
         *
         * <p>直接返回原始标题行，而不是重新拼接"第X章 + 章名"。
         * 原因是原书里"第 3 章"还是"第三章"、中间有没有空格，
         * 都属于作者或校对者的排版意图，重新拼接反而会和原文对不上。
         */
        public String displayText() {
            return rawLine;
        }
    }

    /**
     * 识别结论。
     *
     * <p>把"拒绝原因"也一起返回，是为了让调试和测试都能看到"为什么没认出来"。
     * 只返回 boolean 的接口在排查分章问题时几乎没用。
     *
     * @param accepted 是否认定为章节标题
     * @param match    认定成功时的匹配结果，失败时为 null
     * @param reason   人类可读的判定说明
     */
    public record Verdict(boolean accepted, Match match, String reason) {

        static Verdict accept(Match match) {
            return new Verdict(true, match, "接受");
        }

        static Verdict reject(String reason) {
            return new Verdict(false, null, reason);
        }
    }

    private ChapterTitleMatcher() {
        // 工具类不允许实例化
    }

    /** 便捷入口：只关心"是不是标题"，不关心原因。 */
    public static Optional<Match> match(String line) {
        Verdict verdict = inspect(line);
        return verdict.accepted() ? Optional.of(verdict.match()) : Optional.empty();
    }

    /**
     * 完整识别：返回是否接受以及原因。这是主入口。
     *
     * @param line 一行文本（可以带首尾空白，内部会处理）
     */
    public static Verdict inspect(String line) {
        if (line == null || line.isBlank()) {
            return Verdict.reject("空行");
        }

        String normalized = normalizeBrackets(line.strip());
        if (normalized.isEmpty()) {
            return Verdict.reject("剥掉括号后为空");
        }

        // ---- 形态校验（一）：整行长度 ----
        if (normalized.length() > MAX_LINE_LENGTH) {
            return Verdict.reject("整行过长（" + normalized.length() + " 字），判定为正文");
        }

        // ---- 格式校验：正则结构 ----
        Matcher m = CHAPTER_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return Verdict.reject("不符合章节标题结构");
        }

        String numText = m.group("num");
        String unit = m.group("unit");
        String special = m.group("special");
        String specialNum = m.group("specialNum");
        String title = m.group("title") == null ? "" : m.group("title").strip();
        // 标题里可能还拖着一个分隔符，例如"第1章 - 觉醒"剥完是"- 觉醒"
        title = stripLeadingSeparators(title);

        Kind kind;
        String rawNumber;
        if (unit != null) {
            kind = switch (unit) {
                case "卷", "部", "篇" -> Kind.VOLUME;
                default -> Kind.CHAPTER;
            };
            rawNumber = numText;
        } else {
            kind = Kind.SPECIAL;
            rawNumber = (specialNum == null || specialNum.isEmpty()) ? special : special + specialNum;
        }

        // ---- 形态校验（二）：标题部分 ----
        if (title.length() > MAX_TITLE_LENGTH) {
            return Verdict.reject("章节名过长（" + title.length() + " 字）");
        }
        if (containsSentenceEnding(normalized)) {
            return Verdict.reject("含句末标点，判定为正文");
        }
        if (!title.isEmpty() && FORBIDDEN_TITLE_PREFIX.indexOf(title.charAt(0)) >= 0) {
            return Verdict.reject("章节名以虚词「" + title.charAt(0) + "」开头，判定为正文");
        }
        if (!title.isEmpty() && PUNCTUATION_PREFIX.indexOf(title.charAt(0)) >= 0) {
            return Verdict.reject("章节名以标点「" + title.charAt(0) + "」开头，判定为正文");
        }

        // ---- 章号解析 ----
        int number = 0;
        if (kind != Kind.SPECIAL) {
            try {
                number = ChineseNumerals.parse(rawNumber);
            } catch (NumberFormatException e) {
                return Verdict.reject("章节号无法解析: " + rawNumber);
            }
        } else if (specialNum != null && !specialNum.isEmpty()) {
            number = ChineseNumerals.parseOrDefault(specialNum, 0);
        }

        return Verdict.accept(new Match(number, rawNumber, title, kind, normalized));
    }

    /**
     * 剥掉包裹整行的成对括号。
     *
     * <p>有些书源把章节标题写成 {@code 【第一章 星尘之始】}，
     * 不处理的话括号会让正则匹配失败，整本书变成"未分章"。
     * 这里做的是"逐层剥离"，处理 {@code 【[第一章]】} 这类嵌套写法。
     */
    private static String normalizeBrackets(String line) {
        String result = line;
        boolean changed = true;
        while (changed && result.length() >= 2) {
            changed = false;
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            for (int i = 0; i < BRACKET_PAIRS.length(); i += 2) {
                if (first == BRACKET_PAIRS.charAt(i) && last == BRACKET_PAIRS.charAt(i + 1)) {
                    result = result.substring(1, result.length() - 1).strip();
                    changed = true;
                    break;
                }
            }
        }
        return result;
    }

    /** 去掉标题开头的分隔符残留，如"- 觉醒" → "觉醒"。 */
    private static String stripLeadingSeparators(String title) {
        int i = 0;
        while (i < title.length()) {
            char c = title.charAt(i);
            if (c == ':' || c == '：' || c == '.' || c == '·' || c == '-'
                    || c == '—' || c == '－' || c == ' ' || c == '\u3000') {
                i++;
            } else {
                break;
            }
        }
        return title.substring(i).strip();
    }

    private static boolean containsSentenceEnding(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
