package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.core.parser.BookParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TxtBookParser} 的集成测试。
 *
 * <p>前面几组测试都是在内存里构造数据，这一组则真的<b>把文件写到磁盘上再读回来</b>，
 * 因为这一步覆盖了几个只有在真实文件上才成立的行为：
 * <ul>
 *   <li>{@code RandomAccessFile.seek()} 按字节偏移量定位</li>
 *   <li>不同编码（UTF-8 / GBK / UTF-16）写进文件后能否正确读回</li>
 *   <li>文件不存在时的错误处理</li>
 * </ul>
 */
@DisplayName("TXT 解析器（真实文件）")
class TxtBookParserTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final String BOOK_TEXT = """
            第一章 星尘之始

            夜空中的星光落下来的时候，没有人注意到。

            他站在窗边，看着远处的城市灯火，心里想着一些很久以前的事情。

            第二章 远行

            第二天清晨，他收拾好行李，准备离开这座生活了二十年的城市。

            母亲站在门口，什么也没说。

            第三章 夜访

            夜里有人敲门，他起身去开。

            门外站着一个陌生人，手里拿着一个旧信封。

            第四章 终局

            一切都在这一天结束了。

            他终于可以好好休息了。
            """;

    private final TxtBookParser parser = new TxtBookParser();

    // ==================== 第一步：元信息 ====================

    @Test
    @DisplayName("书名取自文件名，并清掉营销后缀")
    void titleFromFileName(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "斗破苍穹（完结）TXT下载.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        Book book = parser.parseMetadata(file);

        assertEquals("斗破苍穹", book.title());
        assertEquals(BookFormat.TXT, book.format());
        assertEquals(0, book.chapterCount(), "还没建索引，章节数应为 0");
        assertFalse(book.isIndexed());
    }

    @Test
    @DisplayName("文件名里有「书名：」时以内容为准")
    void titleFromContentBeatsFileName(@TempDir Path dir) throws Exception {
        String content = "书名：星辰彼岸\n作者：某位作者\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "1.txt", content, StandardCharsets.UTF_8);

        Book book = parser.parseMetadata(file);

        assertEquals("星辰彼岸", book.title());
    }

    @Test
    @DisplayName("能提取作者，且在作者名后的分隔符处截断")
    void extractAuthor(@TempDir Path dir) throws Exception {
        String content = "书名：测试之书\n作者：天蚕土豆   类别：玄幻\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "test.txt", content, StandardCharsets.UTF_8);

        Book book = parser.parseMetadata(file);

        assertEquals("天蚕土豆", book.author());
        assertTrue(book.authorName().isPresent());
    }

    @Test
    @DisplayName("没有作者信息时返回空，而不是空字符串")
    void authorMayBeAbsent(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "无作者.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        Book book = parser.parseMetadata(file);

        assertTrue(book.author() == null);
        assertTrue(book.authorName().isEmpty());
    }

    @Test
    @DisplayName("作者名后的闭书名号不会残留（真实文件头写成『书名/作者:xxx』）")
    void authorNameStopsAtClosingBracket(@TempDir Path dir) throws Exception {
        // 《武动乾坤》的文件头原文，闭书名号曾经被一起吃进作者名里
        String content = "『测试之书/作者:天蚕土豆』\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "test.txt", content, StandardCharsets.UTF_8);

        Book book = parser.parseMetadata(file);

        assertEquals("天蚕土豆", book.author());
    }

    @Test
    @DisplayName("包裹整个书名的书名号会被剥掉")
    void wrappingBookTitleMarksAreStripped(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "《元尊》.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        assertEquals("元尊", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("文件名里「作者xxx」的尾巴会被清掉，且书名号一并剥掉")
    void authorTailInFileNameIsRemoved(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "《斗破苍穹》（精校版全本）作者天蚕土豆.txt",
                BOOK_TEXT, StandardCharsets.UTF_8);

        assertEquals("斗破苍穹", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("书名本身以「作者」开头时，不能整条被当成作者尾巴删掉")
    void bookNameStartingWithAuthorIsKept(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "作者之死.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        assertEquals("作者之死", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("文件头里的《书名》短行会被认作书名，而不是退化成文件名")
    void titleComesFromMarkedLineInHeader(@TempDir Path dir) throws Exception {
        // 《元尊》的真实文件头：书名号单独成行，没有"书名："前缀
        String content = "\n\n《元尊》\n作者：天蚕土豆\n内容简介：\n    " + BOOK_TEXT;
        Path file = writeBook(dir, "01.txt", content, StandardCharsets.UTF_8);

        assertEquals("元尊", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("同一行里混着括号注释和作者名时，只取书名号里的那截")
    void titleTakesOnlyMarkedPartOfHeaderLine(@TempDir Path dir) throws Exception {
        // 《全职高手》的真实文件头：书名号后面还跟着（精校版全本）作者蝴蝶蓝
        String content = "\n《全职高手》（精校版全本）作者蝴蝶蓝\n\n全职高手\n作者：蝴蝶蓝\n\n"
                + BOOK_TEXT;
        Path file = writeBook(dir, "02.txt", content, StandardCharsets.UTF_8);

        assertEquals("全职高手", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("『书名/作者:xxx』式的文件头，书名不会被作者部分污染")
    void titleFromQuotedHeaderLine(@TempDir Path dir) throws Exception {
        // 《武动乾坤》的真实文件头
        String content = "『武动乾坤/作者:天蚕土豆』\n『状态:已完结』\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "04.txt", content, StandardCharsets.UTF_8);

        assertEquals("武动乾坤", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("元数据行『状态:已完结』不会被当书名（它在书名之后）")
    void metadataLineIsNotTakenAsTitle(@TempDir Path dir) throws Exception {
        // 顺序故意倒过来：状态行在前，靠"含冒号就否决"把第一行挡掉
        String content = "『状态:已完结』\n『武动乾坤/作者:天蚕土豆』\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "04.txt", content, StandardCharsets.UTF_8);

        assertEquals("武动乾坤", parser.parseMetadata(file).title());
    }

    @Test
    @DisplayName("正文里引述的书名不会被当书名 —— 那一行太长")
    void longBodyLineWithBookMarkIsNotTitle(@TempDir Path dir) throws Exception {
        String content = "他翻开那本已经读了很多年的旧书，封面上写着《星辰变》，作者是当年红极一时的作家，"
                + "书页已经泛黄发脆，边角都卷起来了，可他还是舍不得扔。\n\n" + BOOK_TEXT;
        Path file = writeBook(dir, "无名.txt", content, StandardCharsets.UTF_8);

        assertEquals("无名", parser.parseMetadata(file).title(), "长行是正文，书名应退回文件名");
    }

    @Test
    @DisplayName("文件不存在时抛出带路径的业务异常")
    void missingFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("不存在.txt");

        BookParseException e = assertThrows(BookParseException.class,
                () -> parser.parseMetadata(missing));

        assertTrue(e.filePath().contains("不存在.txt"));
    }

    @Test
    @DisplayName("传目录而不是文件时也要拒绝")
    void directoryThrows(@TempDir Path dir) {
        assertThrows(BookParseException.class, () -> parser.parseMetadata(dir));
    }

    // ==================== 第二步：章节索引 ====================

    @Test
    @DisplayName("解析出四章，序号连续")
    void parseChapters(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "测试.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        List<Chapter> chapters = parser.parseChapters(file, "book-1");

        assertEquals(4, chapters.size());
        assertEquals("第一章 星尘之始", chapters.get(0).title());
        assertEquals("book-1", chapters.get(0).bookId());
        for (Chapter c : chapters) {
            assertFalse(c.hasContent(), "建索引阶段不该读正文，blocks 必须为空");
        }
    }

    @Test
    @DisplayName("GBK 编码的文件也能正确分章")
    void parseChaptersInGbk(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "gbk小说.txt", BOOK_TEXT, GBK);

        List<Chapter> chapters = parser.parseChapters(file, "book-gbk");

        assertEquals(4, chapters.size());
        assertEquals("第二章 远行", chapters.get(1).title());
    }

    @Test
    @DisplayName("建索引的诊断报告可用")
    void indexReportIsAvailable(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "测试.txt", BOOK_TEXT, StandardCharsets.UTF_8);

        TxtChapterSplitter.Report report = parser.index(file, "book-1");

        assertEquals(4, report.candidateCount());
        assertFalse(report.fallback());
        assertEquals(4, report.chapters().size());
    }

    // ==================== 第三步：按需读正文 ====================

    @Test
    @DisplayName("按字节偏移读回正文，且不夹带相邻章节")
    void loadChapterReadsExactRange(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "测试.txt", BOOK_TEXT, StandardCharsets.UTF_8);
        List<Chapter> chapters = parser.parseChapters(file, "book-1");

        Chapter loaded = parser.loadChapter(file, chapters.get(1));

        assertTrue(loaded.hasContent());
        assertTrue(loaded.title().equals("第二章 远行"));
        String all = loaded.blocks().stream()
                .map(TxtBookParserTest::textOf)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("他收拾好行李"), "应该读到第二章的正文");
        assertFalse(all.contains("夜空中的星光"), "不该夹带第一章的内容");
        assertFalse(all.contains("夜里有人敲门"), "不该夹带第三章的内容");
    }

    @Test
    @DisplayName("章节第一行标题被转成 Heading 块，其余是 Paragraph 块")
    void blocksAreTyped(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "测试.txt", BOOK_TEXT, StandardCharsets.UTF_8);
        List<Chapter> chapters = parser.parseChapters(file, "book-1");

        Chapter loaded = parser.loadChapter(file, chapters.get(0));

        assertEquals(3, loaded.blocks().size(), "1 个标题块 + 2 个段落块");
        ChapterBlock first = loaded.blocks().get(0);
        assertInstanceOf(ChapterBlock.Heading.class, first);
        assertEquals("第一章 星尘之始", textOf(first));
        assertInstanceOf(ChapterBlock.Paragraph.class, loaded.blocks().get(1));
    }

    @Test
    @DisplayName("GBK 文件读回正文时不出现乱码")
    void loadChapterDecodesGbkCorrectly(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "gbk小说.txt", BOOK_TEXT, GBK);
        List<Chapter> chapters = parser.parseChapters(file, "book-gbk");

        Chapter loaded = parser.loadChapter(file, chapters.get(0));

        String all = loaded.blocks().stream()
                .map(TxtBookParserTest::textOf)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("夜空中的星光落下来的时候"), "GBK 正文应正确解码：" + all);
        assertFalse(all.contains("\uFFFD"), "不该出现替换字符");
    }

    @Test
    @DisplayName("UTF-16LE 带 BOM 的文件整条链路可用")
    void utf16BookWorks(@TempDir Path dir) throws Exception {
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] body = BOOK_TEXT.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        Path file = dir.resolve("utf16.txt");
        Files.write(file, bytes);

        List<Chapter> chapters = parser.parseChapters(file, "book-utf16");
        Chapter loaded = parser.loadChapter(file, chapters.get(0));

        assertEquals(4, chapters.size());
        assertEquals("第一章 星尘之始", chapters.get(0).title());
        assertTrue(loaded.blocks().stream()
                .map(TxtBookParserTest::textOf)
                .anyMatch(t -> t.contains("夜空中的星光落下来的时候")));
    }

    @Test
    @DisplayName("空章节不会抛异常，返回空的 blocks")
    void emptyChapterReturnsNoBlocks(@TempDir Path dir) throws Exception {
        Path file = writeBook(dir, "测试.txt", BOOK_TEXT, StandardCharsets.UTF_8);
        Chapter empty = Chapter.indexOnly("book-1", 0, "空章", 0, 0);

        Chapter loaded = parser.loadChapter(file, empty);

        assertFalse(loaded.hasContent());
        assertEquals(0, loaded.blocks().size());
    }

    @Test
    @DisplayName("supportedFormat 声明自己处理 TXT")
    void declaresOwnFormat() {
        assertEquals(BookFormat.TXT, parser.supportedFormat());
    }

    // ==================== 工具 ====================

    private static Path writeBook(Path dir, String fileName, String text, Charset charset)
            throws IOException {
        Path file = dir.resolve(fileName);
        Files.write(file, text.getBytes(charset));
        return file;
    }

    private static String textOf(ChapterBlock block) {
        return switch (block) {
            case ChapterBlock.Paragraph p -> p.text();
            case ChapterBlock.Heading h -> h.text();
            case ChapterBlock.Image i -> i.resourcePath();
        };
    }
}
