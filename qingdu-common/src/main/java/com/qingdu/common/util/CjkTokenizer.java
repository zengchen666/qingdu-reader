package com.qingdu.common.util;

import java.util.Locale;

/**
 * 中文分词器：把文本切成适合 SQLite FTS5 检索的 token 串。
 *
 * <p><b>为什么需要它</b>（这一段是选型的依据，实测过，别改成想当然的写法）：
 *
 * <ul>
 *   <li>FTS5 默认的 {@code unicode61} 会把连续的一串汉字当成<b>一个</b> token，
 *       所以「石符」这种子串根本搜不出来 —— 中文检索直接用不了。</li>
 *   <li>FTS5 3.34+ 内置的 {@code trigram} 按<b>三个字</b>滑窗切分，
 *       于是<b>两字查询必然返回 0 条</b>（「石符」「林动」「小貂」实测全 0）。
 *       而中文里两字词、两字人名恰恰是最常见的搜法，这是硬伤。</li>
 * </ul>
 *
 * <p>所以这里自己做 <b>bigram（二字滑窗）</b>：对连续的汉字串切出重叠的二字组，
 * 用空格分开后交给 {@code unicode61}。这样「石符」是独立 token，两字可查；
 * 「云岚宗」切成 {@code 云岚 岚宗}，默认 AND 语义下也能命中。
 *
 * <pre>
 * 原文   林动手中的石符，忽然散发出一阵微弱的光。
 * 切分   林动 动手 手中 中的 的石 石符 忽然 然散 散发 发出 出一 一阵 阵微 微弱 弱的 的光
 * </pre>
 *
 * <p><b>两处刻意的取舍：</b>
 *
 * <ol>
 *   <li><b>标点与空白直接丢掉。</b>早期版本把全角标点也算进"连续串"，
 *       于是产生 {@code ，忽然}、{@code 光。} 这类噪音 token，切分后体积多 10%。
 *       标点对"搜某个词"没有意义，丢掉更省。</li>
 *   <li><b>连续的 ASCII 字母/数字整体保留为一个 token。</b>
 *       小说里偶尔会出现 {@code JavaFX}、{@code 3D}、章节号 {@code 100} 这类内容，
 *       如果连它们一起丢掉，用户搜这些就永远搜不到。</li>
 * </ol>
 *
 * <p><b>配套的坑（不在本类解决，但必须知道）：</b>
 * bigram 只保证两个 token 都出现，<b>不保证相邻</b>。
 * 「云岚宗」会被「云岚山中有个岚宗派」命中，这是假阳性。
 * 但因为索引表用了 {@code detail='none'}（省一半体积），
 * FTS5 的短语查询（本来能要求相邻）不可用：
 * {@code fts5: phrase queries are not supported (detail!=full)}。
 * 所以调用方<b>必须</b>拿原文再做一次精确子串校验。
 *
 * <p>本类是无状态工具类，构造方法私有，不允许实例化。
 */
public final class CjkTokenizer {

    private CjkTokenizer() {
        // 工具类不允许实例化
    }

    /**
     * 把文本切成供 FTS5 索引的 token 串（空格分隔）。
     *
     * @param text 原文，允许为 null（返回空串）
     * @return token 串；末尾带一个空格，便于调用方拼接
     */
    public static String tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() * 2);
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);

            if (isHan(cp)) {
                i = appendHanRun(text, i, out);
            } else if (isAsciiWordChar(cp)) {
                i = appendAsciiRun(text, i, out);
            } else {
                // 标点、空白、其它符号：直接跳过
                i += width;
            }
        }
        return out.toString();
    }

    /**
     * 把用户输入的查询串切成查询用的 token 串。
     *
     * <p>目前与 {@link #tokenize(String)} 完全等价，仍然单独开一个方法，
     * 是因为两者的语义不同、以后可能分叉：索引侧要"尽可能多地覆盖"，
     * 查询侧则可能要处理 FTS5 的保留字符（{@code " * - ( )} 等）转义。
     * 现在合成一个方法也能跑，但以后改起来容易改错另一边。
     */
    public static String tokenizeQuery(String query) {
        return tokenize(query);
    }

    /**
     * 判断一个码点是不是汉字。
     *
     * <p>只认{@code CJK_UNIFIED_IDEOGRAPHS}（含扩展 A）与兼容汉字。
     * <b>刻意不认</b> {@code CJK_SYMBOLS_AND_PUNCTUATION}（全角标点）——
     * 把它们算进来会产生 {@code 光。} 这种噪音 token，实测多占 10% 体积。
     */
    public static boolean isHan(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /** ASCII 字母或数字。用来保留 {@code JavaFX}、{@code 100} 这类非中文词。 */
    private static boolean isAsciiWordChar(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }

    /**
     * 从 {@code start} 开始吃掉一段连续汉字，按二字滑窗写入 {@code out}。
     *
     * <p>单字（前后都不是汉字时）也要单独输出，否则搜「我」这种单字会永远搜不到。
     *
     * @return 这段连续汉字结束后的下标
     */
    private static int appendHanRun(String text, int start, StringBuilder out) {
        int n = text.length();
        int end = start;
        while (end < n) {
            int cp = text.codePointAt(end);
            if (!isHan(cp)) {
                break;
            }
            end += Character.charCount(cp);
        }
        String run = text.substring(start, end);
        if (run.length() == 1) {
            out.append(run).append(' ');
        } else {
            // 二字滑窗：最后一个字符不够凑一组时，它已经包含在上一组里了
            for (int k = 0; k + 1 < run.length(); k++) {
                out.append(run, k, k + 2).append(' ');
            }
        }
        return end;
    }

    /**
     * 从 {@code start} 开始吃掉一段连续的 ASCII 字母/数字，整体作为一个 token。
     *
     * @return 这段结束后的下标
     */
    private static int appendAsciiRun(String text, int start, StringBuilder out) {
        int n = text.length();
        int end = start;
        while (end < n) {
            int cp = text.codePointAt(end);
            if (!isAsciiWordChar(cp)) {
                break;
            }
            end += Character.charCount(cp);
        }
        // 统一小写：FTS5 的 unicode61 默认会做大小写折叠，这里先折叠掉
        // 是为了让"索引侧"和"查询侧"的字符串在入库前就一致，排查问题时更直观
        out.append(text.substring(start, end).toLowerCase(Locale.ROOT)).append(' ');
        return end;
    }
}
