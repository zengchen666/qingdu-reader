package com.qingdu.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChineseNumerals} 的单元测试。
 *
 * <p>这一组测试的特点是"用数据说话"：把小说里真实出现过的章节号写法
 * 一条条列成表格，跑一遍就知道规则对不对。以后发现新的畸形写法，
 * 直接往 {@code @CsvSource} 里加一行即可，不需要改代码结构。
 */
@DisplayName("中文数字解析")
class ChineseNumeralsTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            // ---- 个位 ----
            "一, 1",
            "二, 2",
            "九, 9",
            "〇, 0",
            "零, 0",
            // ---- 十位：注意"十三"省略了开头的一 ----
            "十, 10",
            "十三, 13",
            "二十, 20",
            "二十一, 21",
            "九十, 90",
            "九十九, 99",
            // ---- 百位：注意"一百零一"里的零只是占位 ----
            "一百, 100",
            "一百零一, 101",
            "一百一十, 110",
            "一百一十一, 111",
            "一百二十三, 123",
            "三百六十, 360",
            "九百九十九, 999",
            // ---- 千位 ----
            "一千, 1000",
            "一千零一, 1001",
            "一千零二十, 1020",
            "一千二百三十四, 1234",
            "两千, 2000",
            // ---- 万位：分节单位，先把前面小节结算掉 ----
            "一万, 10000",
            "一万二千, 12000",
            "一万零一, 10001",
            "十万, 100000",
            "十二万三千四百五十六, 123456",
            // ---- 阿拉伯数字与全角数字 ----
            "1, 1",
            "13, 13",
            "1024, 1024",
            "０７, 7",
            "１２３, 123",
            // ---- 大写数字（校对版小说偶见） ----
            "壹, 1",
            "贰拾, 20",
            "壹佰零壹, 101",
            // ---- 带空格的脏数据 ----
            "一 百 二 十 三, 123"
    })
    @DisplayName("各种写法都能正确还原")
    void parseVariousForms(String input, int expected) {
        assertEquals(expected, ChineseNumerals.parse(input));
    }

    @Test
    @DisplayName("空文本抛出异常")
    void parseEmptyThrows() {
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse(""));
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse("   "));
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse(null));
    }

    @Test
    @DisplayName("非法字符抛出异常")
    void parseIllegalCharThrows() {
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse("abc"));
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse("一章"));
    }

    @Test
    @DisplayName("数值过大时抛出异常而不是静默溢出")
    void parseOutOfRangeThrows() {
        // 十亿 = 10 亿，仍然在 int 范围内（21 亿），所以不报错
        assertEquals(1_000_000_000, ChineseNumerals.parse("十亿"));
        // 百亿 = 100 亿，超过 int 上限
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse("百亿"));
        assertThrows(NumberFormatException.class, () -> ChineseNumerals.parse("99999999999"));
    }

    @Test
    @DisplayName("parseOrDefault 解析失败时返回兜底值，不抛异常")
    void parseOrDefaultNeverThrows() {
        assertEquals(0, ChineseNumerals.parseOrDefault("乱码", 0));
        assertEquals(-1, ChineseNumerals.parseOrDefault(null, -1));
        assertEquals(42, ChineseNumerals.parseOrDefault("四十二", -1));
    }

    @Test
    @DisplayName("判断是否为中文数字")
    void isChineseNumeralCheck() {
        assertTrue(ChineseNumerals.isChineseNumeral("一百二十三"));
        assertTrue(ChineseNumerals.isChineseNumeral("十"));
        assertTrue(ChineseNumerals.isChineseNumeral("一万"));
        assertFalse(ChineseNumerals.isChineseNumeral("13"));      // 阿拉伯数字不算
        assertFalse(ChineseNumerals.isChineseNumeral("一百章"));   // 混入量词
        assertFalse(ChineseNumerals.isChineseNumeral(""));
        assertFalse(ChineseNumerals.isChineseNumeral(null));
    }
}
