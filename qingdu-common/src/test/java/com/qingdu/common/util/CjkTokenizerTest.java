package com.qingdu.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CjkTokenizer} 的单元测试。
 *
 * <p>这批用例的全部意义，是**把选型时的实测结论钉死在代码里**。
 * 分词方案是拿真书数据一轮轮试出来的（详见
 * {@code docs/2026-09-22-fulltext-search-design.md}），
 * 结论很容易在"顺手重构"时被改坏 —— 尤其是下面这几条：
 *
 * <ul>
 *   <li>两字词必须能成为独立 token（否则 {@code trigram} 那个坑会悄悄回来）；</li>
 *   <li>标点必须被丢掉（否则索引体积无谓地大 10%）；</li>
 *   <li>ASCII 字母/数字必须保留（否则搜 {@code JavaFX} 永远搜不到）。</li>
 * </ul>
 */
@DisplayName("中文分词器")
class CjkTokenizerTest {

    /** 把 token 串拆成集合，避免断言受顺序和空白影响。 */
    private static java.util.List<String> tokens(String text) {
        String s = CjkTokenizer.tokenize(text).trim();
        return s.isEmpty() ? java.util.List.of() : Arrays.asList(s.split(" "));
    }

    @Nested
    @DisplayName("边界输入")
    class EdgeCases {

        @Test
        @DisplayName("null 应该返回空串而不是抛异常")
        void shouldReturnEmptyForNull() {
            assertEquals("", CjkTokenizer.tokenize(null));
        }

        @Test
        @DisplayName("空串应该返回空串")
        void shouldReturnEmptyForEmpty() {
            assertEquals("", CjkTokenizer.tokenize(""));
        }

        @Test
        @DisplayName("纯标点应该切不出任何 token")
        void shouldReturnEmptyForPunctuationOnly() {
            assertEquals("", CjkTokenizer.tokenize("。，！？、；："));
            assertEquals("", CjkTokenizer.tokenize("   \t\n  "));
        }

        @Test
        @DisplayName("单个汉字也应该产出一个 token（否则搜单字永远搜不到）")
        void shouldKeepSingleHanChar() {
            assertEquals(java.util.List.of("我"), tokens("我"));
            assertEquals(java.util.List.of("的"), tokens("。的。"));
        }
    }

    @Nested
    @DisplayName("二字滑窗")
    class Bigram {

        @Test
        @DisplayName("两字应该切出一个二元组")
        void shouldProduceOneBigramForTwoChars() {
            assertEquals(java.util.List.of("石符"), tokens("石符"));
        }

        @Test
        @DisplayName("三字应该切出两个重叠的二元组")
        void shouldProduceOverlappingBigramsForThreeChars() {
            assertEquals(java.util.List.of("云岚", "岚宗"), tokens("云岚宗"));
        }

        @Test
        @DisplayName("连续汉字应该切出 n-1 个重叠二元组（不产生单字尾巴）")
        void shouldProduceNMinusOneBigrams() {
            // 5 个字 -> 4 个二元组。最后一个字不单独成组，因为它已包含在上一组里
            assertEquals(java.util.List.of("林动", "动手", "手中", "中的"), tokens("林动手中的"));
            // 4 个字 -> 3 个二元组
            assertEquals(java.util.List.of("石头", "头符", "符号"), tokens("石头符号"));
        }

        @Test
        @DisplayName("两字人名必须能成为独立 token —— 这是 trigram 方案的死穴")
        void shouldSupportTwoCharNameQuery() {
            // trigram 按三字切分，两字查询必然 0 命中；这条就是防它回来的
            assertTrue(tokens("药老").contains("药老"));
            assertTrue(tokens("萧炎").contains("萧炎"));
        }
    }

    @Nested
    @DisplayName("标点与空白")
    class Punctuation {

        @Test
        @DisplayName("全角标点不应该混入 token")
        void shouldNotMixPunctuationIntoToken() {
            // 早期版本把标点算进"连续串"，切出「，忽然」「光。」这类噪音 token
            java.util.List<String> t = tokens("林动手中的石符，忽然散发出一阵微弱的光。");
            assertFalse(t.contains("，忽然"));
            assertFalse(t.contains("光。"));
            assertTrue(t.contains("石符"));
            assertTrue(t.contains("忽然"));
        }

        @Test
        @DisplayName("标点隔断的两段汉字不应该跨段组词")
        void shouldNotBridgeAcrossPunctuation() {
            java.util.List<String> t = tokens("石头。符号");
            assertFalse(t.contains("头符"), "被句号隔开的「头」和「符」不该组成 token");
            assertTrue(t.contains("石头"));
            assertTrue(t.contains("符号"));
        }
    }

    @Nested
    @DisplayName("非中文内容")
    class NonChinese {

        @Test
        @DisplayName("连续的 ASCII 字母应该整体保留为一个 token")
        void shouldKeepAsciiWordAsOneToken() {
            assertEquals(java.util.List.of("javafx"), tokens("JavaFX"));
        }

        @Test
        @DisplayName("字母与数字连写时整体保留为一个 token，并统一小写")
        void shouldKeepDigits() {
            // GB18030 是"字母+数字"的连续 ASCII 串，按设计整体保留并折叠成小写。
            // 连带说明一个已知取舍：搜「18030」搜不到「GB18030」，
            // 因为索引里根本没有单独的 18030 这个 token —— 这是保住 JavaFX/3D 这类词必须付的代价。
            java.util.List<String> t = tokens("GB18030 用的编码");
            assertEquals(java.util.List.of("gb18030", "用的", "的编", "编码"), t);
        }

        @Test
        @DisplayName("中英混排应该各自成 token")
        void shouldSplitMixedText() {
            java.util.List<String> t = tokens("用 JavaFX 做界面");
            assertTrue(t.contains("javafx"));
            assertTrue(t.contains("界面"));
        }
    }

    @Nested
    @DisplayName("查询侧")
    class QuerySide {

        @Test
        @DisplayName("同一句话在索引侧和查询侧必须产出相同的 token")
        void shouldMatchBetweenIndexAndQuery() {
            // 这是检索能命中的前提：两边切法不同就永远搜不到
            String s = "云岚宗的炼药师大会";
            assertEquals(CjkTokenizer.tokenize(s), CjkTokenizer.tokenizeQuery(s));
        }

        @Test
        @DisplayName("查询里的标点不应该影响切分结果")
        void shouldIgnorePunctuationInQuery() {
            assertEquals(CjkTokenizer.tokenizeQuery("云岚宗"),
                    CjkTokenizer.tokenizeQuery("「云岚宗」"));
        }
    }

    @Nested
    @DisplayName("汉字判定")
    class HanDetection {

        @Test
        @DisplayName("常用汉字应该被判定为汉字")
        void shouldDetectHan() {
            assertTrue(CjkTokenizer.isHan('中'));
            assertTrue(CjkTokenizer.isHan('文'));
        }

        @Test
        @DisplayName("全角标点不算汉字")
        void shouldNotTreatPunctuationAsHan() {
            assertFalse(CjkTokenizer.isHan('。'));
            assertFalse(CjkTokenizer.isHan('，'));
        }

        @Test
        @DisplayName("ASCII 不算汉字")
        void shouldNotTreatAsciiAsHan() {
            assertFalse(CjkTokenizer.isHan('A'));
            assertFalse(CjkTokenizer.isHan('1'));
        }
    }
}
