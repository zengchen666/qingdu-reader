package com.qingdu.core.parser.txt;

import com.qingdu.core.parser.txt.ChapterTitleMatcher.Kind;
import com.qingdu.core.parser.txt.ChapterTitleMatcher.Match;
import com.qingdu.core.parser.txt.ChapterTitleMatcher.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChapterTitleMatcher} 的单元测试。
 *
 * <p>分成两组：<b>该认出来的</b> 和 <b>绝不能认出来的</b>。
 * 后一组比前一组更重要 —— 漏认一个章节只是少一个目录项，
 * 错认一行正文却会在目录里插进一条莫名其妙的条目。
 */
@DisplayName("章节标题识别")
class ChapterTitleMatcherTest {

    @Nested
    @DisplayName("该认出来的写法")
    class Accepted {

        @ParameterizedTest(name = "\"{0}\" → 第 {1} 章")
        @CsvSource({
                "'第一章 星尘之始', 1",
                "'第1章 星尘之始', 1",
                "'第 5 章', 5",
                "'第十章', 10",
                "'第十三章 远行', 13",
                "'第一百二十三章 尾声', 123",
                "'第０７章 全角数字', 7",
                "'第一百章', 100",
                "'第1001章 千年之后', 1001",
                "'  第三章   夜访  ', 3"
        })
        @DisplayName("章 / 节的常见写法")
        void chapterForms(String line, int expectedNumber) {
            Verdict v = ChapterTitleMatcher.inspect(line);

            assertTrue(v.accepted(), () -> "应该被接受，但被拒绝：" + v.reason());
            assertEquals(expectedNumber, v.match().number());
            assertEquals(Kind.CHAPTER, v.match().kind());
        }

        @Test
        @DisplayName("分卷写法识别为 VOLUME")
        void volumeForm() {
            Verdict v = ChapterTitleMatcher.inspect("第二卷 风起云涌");

            assertTrue(v.accepted());
            assertEquals(Kind.VOLUME, v.match().kind());
            assertEquals(2, v.match().number());
        }

        @ParameterizedTest
        @ValueSource(strings = {"楔子", "序章", "引子", "前言", "尾声", "后记", "终章", "番外"})
        @DisplayName("没有编号的特殊章名")
        void specialForms(String line) {
            Verdict v = ChapterTitleMatcher.inspect(line);

            assertTrue(v.accepted(), () -> "应该被接受，但被拒绝：" + v.reason());
            assertEquals(Kind.SPECIAL, v.match().kind());
            assertFalse(v.match().numbered(), "特殊章名不带编号");
        }

        @Test
        @DisplayName("带编号的特殊章名，如「番外三」")
        void numberedSpecialForm() {
            Verdict v = ChapterTitleMatcher.inspect("番外三");

            assertTrue(v.accepted());
            assertEquals(3, v.match().number());
        }

        @Test
        @DisplayName("被【】包裹的标题要先剥掉括号再识别")
        void bracketedTitle() {
            Verdict v = ChapterTitleMatcher.inspect("【第三章 觉醒】");

            assertTrue(v.accepted());
            assertEquals(3, v.match().number());
            assertEquals("第三章 觉醒", v.match().displayText());
        }

        @Test
        @DisplayName("displayText 保留原文排版，不重新拼接")
        void displayTextKeepsOriginal() {
            Match m = ChapterTitleMatcher.match("第 3 章 - 觉醒").orElseThrow();

            assertEquals("第 3 章 - 觉醒", m.displayText());
            assertEquals("觉醒", m.title());
        }
    }

    @Nested
    @DisplayName("绝不能认出来的正文")
    class Rejected {

        @Test
        @DisplayName("正文里引述章节号：以虚词开头，要拒绝")
        void bodyTextQuotingChapterNumber() {
            Verdict v = ChapterTitleMatcher.inspect("第三章的内容我早就看过了");

            assertFalse(v.accepted());
            assertTrue(v.reason().contains("虚词"), "拒绝原因应指明是虚词开头：" + v.reason());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "第一章写得很精彩。",
                "第三章，结局是什么？",
                "第二章 他到底想说什么！",
                "第四章……后来呢"
        })
        @DisplayName("含句末标点的行要拒绝")
        void bodyTextWithSentenceEnding(String line) {
            Verdict v = ChapterTitleMatcher.inspect(line);

            assertFalse(v.accepted(), () -> "不该接受：" + line);
        }

        @Test
        @DisplayName("逗号不能被当作章号与章名的分隔符")
        void commaIsNotASeparator() {
            // 如果允许逗号分隔，"第三章，他离开了"就会被误判成标题
            Verdict v = ChapterTitleMatcher.inspect("第三章，他离开了这座城");

            assertFalse(v.accepted());
        }

        @Test
        @DisplayName("超过长度的行要拒绝")
        void tooLongLine() {
            Verdict v = ChapterTitleMatcher.inspect(
                    "第一章 这是一个非常非常非常非常非常长的章节名字超过二十字了吧");

            assertFalse(v.accepted());
            assertTrue(v.reason().contains("过长"), "拒绝原因应指明是过长：" + v.reason());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "今天天气不错",
                "他推开门，说道：第一章已经写完了",
                "（本章完）",
                "正文",
                "..."
        })
        @DisplayName("完全不像标题的行")
        void notATitleAtAll(String line) {
            assertFalse(ChapterTitleMatcher.inspect(line).accepted());
        }

        @Test
        @DisplayName("空行与 null 要安全返回拒绝，不能抛异常")
        void blankInput() {
            assertFalse(ChapterTitleMatcher.inspect("").accepted());
            assertFalse(ChapterTitleMatcher.inspect("   ").accepted());
            assertFalse(ChapterTitleMatcher.inspect(null).accepted());
        }
    }
}
