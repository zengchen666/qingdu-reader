package com.qingdu.core.parser;

import com.qingdu.common.domain.BookFormat;
import com.qingdu.core.parser.spi.BookParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookParsers} 的测试。
 *
 * <p>测的是"挑得出对的那个"和"挑不出就老实说没有"这两件事。
 * 后者尤其值得钉住：{@link BookFormat} 里列了 MOBI / AZW3 / PDF / CBZ，
 * 但没有任何解析器实现它们 —— 所以"格式枚举认识"不等于"能打开"，
 * 这两件事一旦搞混，界面上就会出现"选得到、打开却崩"。
 */
class BookParsersTest {

    @Test
    @DisplayName("TXT 交给 TXT 解析器")
    void txtGoesToTxtParser() {
        BookParser parser = BookParsers.forFile(Path.of("斗破苍穹.txt"));

        assertNotNull(parser);
        assertEquals(BookFormat.TXT, parser.supportedFormat());
    }

    @Test
    @DisplayName("EPUB 交给 EPUB 解析器")
    void epubGoesToEpubParser() {
        BookParser parser = BookParsers.forFile(Path.of("星尘纪.epub"));

        assertNotNull(parser);
        assertEquals(BookFormat.EPUB, parser.supportedFormat());
    }

    @Test
    @DisplayName("扩展名大写也认（Windows 上很常见）")
    void extensionIsCaseInsensitive() {
        assertEquals(BookFormat.TXT, BookParsers.forFile(Path.of("BOOK.TXT")).supportedFormat());
    }

    @Test
    @DisplayName("枚举里列了但没实现解析器的格式 → 返回 null，而不是抛异常")
    void declaredButUnimplementedFormatYieldsNull() {
        assertNull(BookParsers.forFile(Path.of("书.mobi")), "MOBI 枚举里有，但没有解析器");
        assertNull(BookParsers.forFile(Path.of("书.azw3")));
        assertNull(BookParsers.forFile(Path.of("书.pdf")));
        assertNull(BookParsers.forFile(Path.of("书.cbz")));
    }

    @Test
    @DisplayName("完全不认识的扩展名 → null")
    void unknownExtensionYieldsNull() {
        assertNull(BookParsers.forFile(Path.of("说明.md")));
        assertNull(BookParsers.forFile(Path.of("备份.txt.bak")));
        assertNull(BookParsers.forFile(Path.of("没有扩展名")));
    }

    @Test
    @DisplayName("null 和空路径不炸，返回 null")
    void nullSafe() {
        assertNull(BookParsers.forFile(null));
        assertNull(BookParsers.forFile(Path.of("")));
    }

    @Test
    @DisplayName("每个对外声称支持的扩展名，都真的找得到解析器")
    void advertisedExtensionsAllHaveParsers() {
        Set<String> extensions = BookParsers.supportedExtensions();

        assertTrue(extensions.contains(".txt"));
        assertTrue(extensions.contains(".epub"));
        // 这条是防"选择器里列了 .pdf 但点开打不了"这类不一致
        for (String extension : extensions) {
            assertNotNull(BookParsers.forFile(Path.of("x" + extension)),
                    "声称支持 " + extension + "，就得有解析器接得住");
        }
    }

    @Test
    @DisplayName("isSupported 与 forFile 口径一致")
    void isSupportedMatchesForFile() {
        assertTrue(BookParsers.isSupported(Path.of("a.txt")));
        assertTrue(BookParsers.isSupported(Path.of("a.epub")));
        assertEquals(false, BookParsers.isSupported(Path.of("a.pdf")));
        assertEquals(false, BookParsers.isSupported(Path.of("a.md")));
    }
}
