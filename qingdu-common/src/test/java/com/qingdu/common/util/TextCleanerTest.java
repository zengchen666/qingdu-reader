package com.qingdu.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TextCleaner} 的单元测试。
 *
 * <p>这个类存在的意义，是证明一件事：<b>核心逻辑可以脱离界面单独测试</b>。
 * 你不需要启动 JavaFX 窗口、不需要准备一本小说，只要跑
 * {@code mvn test} 就能验证文本处理是否正确。
 *
 * <p>这也是"分层架构"最实际的收益 —— common 和 core 模块不依赖界面，
 * 所以它们天生可测。
 */
@DisplayName("文本清洗工具")
class TextCleanerTest {

    @Test
    @DisplayName("应该把 Windows 换行符统一成 \\n")
    void shouldNormalizeWindowsLineEndings() {
        assertEquals("第一行\n第二行", TextCleaner.normalizeLineEndings("第一行\r\n第二行"));
    }

    @Test
    @DisplayName("应该把老 Mac 换行符统一成 \\n")
    void shouldNormalizeOldMacLineEndings() {
        assertEquals("第一行\n第二行", TextCleaner.normalizeLineEndings("第一行\r第二行"));
    }

    @Test
    @DisplayName("混合换行符不应该产生多余空行")
    void shouldNotCreateExtraBlankLineForMixedEndings() {
        // 如果先替换 \r 再替换 \r\n，这里会变成 "A\n\nB"，这个用例就是防这个坑的
        assertEquals("A\nB", TextCleaner.normalizeLineEndings("A\r\nB"));
    }

    @Test
    @DisplayName("应该去掉开头的 BOM")
    void shouldStripBom() {
        assertEquals("第一章", TextCleaner.stripBom("\uFEFF第一章"));
    }

    @Test
    @DisplayName("没有 BOM 时应该原样返回")
    void shouldKeepTextWithoutBom() {
        assertEquals("第一章", TextCleaner.stripBom("第一章"));
    }

    @Test
    @DisplayName("应该去掉每行末尾的空格与制表符")
    void shouldTrimTrailingSpaces() {
        assertEquals("段落一\n段落二", TextCleaner.trimTrailingSpaces("段落一   \n段落二\t"));
    }

    @Test
    @DisplayName("去掉行尾空格时不应该丢掉末尾换行")
    void shouldPreserveTrailingNewline() {
        assertEquals("段落一\n", TextCleaner.trimTrailingSpaces("段落一   \n"));
    }

    @Test
    @DisplayName("应该把连续空行压缩成一个")
    void shouldCollapseBlankLines() {
        assertEquals("开头\n\n结尾", TextCleaner.collapseBlankLines("开头\n\n\n\n\n结尾"));
    }

    @Test
    @DisplayName("单个空行应该保留")
    void shouldKeepSingleBlankLine() {
        assertEquals("开头\n\n结尾", TextCleaner.collapseBlankLines("开头\n\n结尾"));
    }

    @Test
    @DisplayName("完整清洗流程应该同时处理 BOM、换行和空行")
    void shouldCleanEverythingAtOnce() {
        String raw = "\uFEFF第一章 夜访\r\n正文第一段   \r\n\r\n\r\n\r\n正文第二段\r\n";
        String expected = "第一章 夜访\n正文第一段\n\n正文第二段\n";
        assertEquals(expected, TextCleaner.clean(raw));
    }

    @Test
    @DisplayName("传入 null 不应该抛异常")
    void shouldHandleNullGracefully() {
        assertEquals("", TextCleaner.clean(null));
        assertEquals("", TextCleaner.normalizeLineEndings(null));
        assertEquals("", TextCleaner.stripBom(null));
    }

    @Test
    @DisplayName("传入空字符串不应该抛异常")
    void shouldHandleEmptyString() {
        assertEquals("", TextCleaner.clean(""));
    }
}
