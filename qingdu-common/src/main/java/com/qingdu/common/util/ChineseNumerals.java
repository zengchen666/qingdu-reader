package com.qingdu.common.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 中文数字转换工具。
 *
 * <p><b>为什么需要它？</b><br>
 * 中文小说的章节名有三种写法，而且经常混在同一本书里：
 * <pre>
 *   第一章 夜访          ← 汉字数字
 *   第1章 夜访           ← 阿拉伯数字
 *   第一百二十三章 尾声   ← 汉字数字 + 位值（百、十）
 * </pre>
 * 分章时要判断"章节序号是否在递增"，就必须把这些写法统一还原成 {@code int}。
 * 否则「第一百二十三章」会被当成乱码一样的字符串，校验规则全部失效。
 *
 * <p><b>算法思路（位值制）</b><br>
 * 汉语数字是"位值制"而不是"逐位拼接"，关键差别在于缺少位值时要用乘法而不是加法：
 * <pre>
 *   "一百二十三" = 1×100 + 2×10 + 3          ← 每个数字后面跟着一个"单位"
 *   "十三"       = 1×10  + 3                 ← 省略了开头的"一"，要补 1
 *   "一千零二十"  = 1×1000 + 2×10            ← 中间的"零"只表示占位，跳过
 *   "一万二千"    = 1×10000 + 2×1000         ← "万"是分节单位，先把前面的小节结算掉
 * </pre>
 * 所以代码里维护三个变量：
 * <ul>
 *   <li>{@code current} —— 当前还没结算的数字（如"一百"里的 1）</li>
 *   <li>{@code section} —— 当前小节的累计值（如"一百二十三"累计到 123）</li>
 *   <li>{@code result}  —— 已结算的大节（"万"之前的全部内容）</li>
 * </ul>
 *
 * <p>本类是无状态工具类，只做纯计算，因此可以直接在单元测试里穷举验证。
 */
public final class ChineseNumerals {

    /** 数字字符 → 数值。繁体、大写（壹贰叁）都要收，因为版权页和正文经常混用。 */
    private static final Map<Character, Integer> DIGITS = new HashMap<>();

    /** 位值单位 → 倍数。注意"万"和"亿"是分节单位，单独处理，不放进这张表。 */
    private static final Map<Character, Integer> UNITS = new HashMap<>();

    private static final int SECTION_TEN_THOUSAND = 10_000;
    private static final int SECTION_HUNDRED_MILLION = 100_000_000;

    static {
        // 〇 ○ 零 三种写法都表示 0
        DIGITS.put('〇', 0);
        DIGITS.put('○', 0);
        DIGITS.put('零', 0);
        DIGITS.put('一', 1);
        DIGITS.put('二', 2);
        DIGITS.put('两', 2);   // "两百章"和"二百章"都有人写
        DIGITS.put('三', 3);
        DIGITS.put('四', 4);
        DIGITS.put('五', 5);
        DIGITS.put('六', 6);
        DIGITS.put('七', 7);
        DIGITS.put('八', 8);
        DIGITS.put('九', 9);
        // 大写数字（票据体），部分校对版小说会这样写
        DIGITS.put('壹', 1);
        DIGITS.put('贰', 2);
        DIGITS.put('叁', 3);
        DIGITS.put('肆', 4);
        DIGITS.put('伍', 5);
        DIGITS.put('陆', 6);
        DIGITS.put('柒', 7);
        DIGITS.put('捌', 8);
        DIGITS.put('玖', 9);

        UNITS.put('十', 10);
        UNITS.put('拾', 10);
        UNITS.put('百', 100);
        UNITS.put('佰', 100);
        UNITS.put('千', 1000);
        UNITS.put('仟', 1000);
    }

    private ChineseNumerals() {
        // 工具类不允许实例化
    }

    /**
     * 把中文数字（或阿拉伯数字）解析成 int。
     *
     * @param text 待解析文本，例如 "一百二十三"、"13"、"〇"
     * @return 解析结果，最小为 0
     * @throws NumberFormatException 文本为空、含非法字符或数值超出 int 范围时抛出
     */
    public static int parse(String text) {
        if (text == null) {
            throw new NumberFormatException("待解析的文本不能为 null");
        }
        // 去掉空白：有些书会写成"第 一 章"
        String s = stripWhitespace(text);
        if (s.isEmpty()) {
            throw new NumberFormatException("待解析的文本不能为空");
        }
        // 快速通道：全是阿拉伯数字（含全角）时直接交给 Integer
        String arabic = toHalfWidthDigits(s);
        if (isAllAsciiDigits(arabic)) {
            long value = Long.parseLong(arabic);
            return checkRange(value, text);
        }

        long result = 0;   // 已结算的大节（"万"之前的部分）
        long section = 0;  // 当前小节累计值
        long current = 0;  // 当前待结算的数字

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            Integer digit = DIGITS.get(c);
            if (digit != null) {
                current = digit;
                continue;
            }

            Integer unit = UNITS.get(c);
            if (unit != null) {
                if (current == 0) {
                    // "十三"这种省略写法：单位前面没写数字，默认是 1
                    // 但要排除"零十"这种非法写法（current 为 0 且上一字符是零）
                    if (i > 0 && DIGITS.getOrDefault(s.charAt(i - 1), -1) == 0) {
                        throw new NumberFormatException("非法数字写法: " + text);
                    }
                    current = 1;
                }
                section += current * unit;
                current = 0;
                continue;
            }

            if (c == '万' || c == '萬') {
                section = (section + current) * SECTION_TEN_THOUSAND;
                result += section;
                section = 0;
                current = 0;
                continue;
            }

            if (c == '亿' || c == '億') {
                result = (result + section + current) * SECTION_HUNDRED_MILLION;
                section = 0;
                current = 0;
                continue;
            }

            throw new NumberFormatException("无法识别的数字字符 '" + c + "': " + text);
        }

        return checkRange(result + section + current, text);
    }

    /**
     * 尝试解析，失败时返回一个兜底值而不抛异常。
     *
     * <p>分章扫描是跑在整本书上的循环，里面出现一两个畸形标题很常见。
     * 为了不让整本书解析失败，这类地方用"宽松版"更合适。
     *
     * @param fallback 解析失败时返回的值
     */
    public static int parseOrDefault(String text, int fallback) {
        try {
            return parse(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 判断字符串是否完全由中文数字字符组成（含量词单位，如"一百二十"）。
     *
     * <p>注意"十"、"百"这种<b>只有单位没有数字</b>的写法也算合法 ——
     * "第十章"是常见写法，它等价于"第 10 章"。
     */
    public static boolean isChineseNumeral(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean hasNumberChar = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (DIGITS.containsKey(c) || UNITS.containsKey(c)
                    || c == '万' || c == '萬' || c == '亿' || c == '億') {
                hasNumberChar = true;
            } else {
                return false;
            }
        }
        return hasNumberChar;
    }

    /** 去掉所有空白字符（半角、全角都算）。 */
    private static String stripWhitespace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\u3000' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 把全角数字 ０-９ 转成半角 0-9，其余字符原样保留。 */
    private static String toHalfWidthDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF10' && c <= '\uFF19') {
                sb.append((char) ('0' + (c - '\uFF10')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isAllAsciiDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** 统一做范围检查，避免"第99999999999章"这种脏数据把 int 撑爆。 */
    private static int checkRange(long value, String origin) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("数值超出 int 范围: " + origin);
        }
        return (int) value;
    }
}
