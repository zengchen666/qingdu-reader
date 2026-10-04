package com.qingdu.core.parser.epub;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.core.parser.BookParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EpubBookParser} 的测试。EPUB 样例由 {@link EpubTestBooks} 现造。 */
class EpubBookParserTest {

    @TempDir
    Path tempDir;

    /**
     * 必须在 {@code @BeforeEach} 里建，不能写成字段初始化器：
     * {@code @TempDir} 是构造完实例之后才注入的，字段初始化器跑的时候 tempDir 还是 null。
     */
    private EpubBookParser parser;

    @BeforeEach
    void setUp() {
        parser = new EpubBookParser(tempDir.resolve("cache"));
    }

    // ==================== 元信息 ====================

    @Test
    @DisplayName("读得出书名和作者，格式标成 EPUB")
    void readsMetadata(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);

        Book book = parser.parseMetadata(file);

        assertEquals("星尘纪", book.title());
        assertEquals("佚名", book.author());
        assertEquals(BookFormat.EPUB, book.format());
        assertNotNull(book.id(), "ID 必须由路径推导，否则进度和书签存不住");
    }

    @Test
    @DisplayName("supportedFormat 报 EPUB")
    void declaresFormat() {
        assertEquals(BookFormat.EPUB, parser.supportedFormat());
    }

    // ==================== 章节索引 ====================

    @Test
    @DisplayName("EPUB 3：目录标题来自 nav 文档，spine 里的导航文档不算一章")
    void chapterTitlesFromNav(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);

        List<Chapter> chapters = parser.parseChapters(file, "id1");

        assertEquals(2, chapters.size(), "nav 那一项标了 linear=no，不该算进正文");
        assertEquals("第一章 落星", chapters.get(0).title());
        assertEquals("第二章 远行", chapters.get(1).title());
    }

    @Test
    @DisplayName("目录文件缺失时，回退用文档内的第一个标题")
    void fallsBackToDocumentHeading(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", false);

        List<Chapter> chapters = parser.parseChapters(file, "id1");

        assertEquals(2, chapters.size());
        assertEquals("第一章 落星", chapters.get(0).title());
        assertEquals("第二章 远行", chapters.get(1).title());
    }

    @Test
    @DisplayName("EPUB 2：目录标题来自 toc.ncx")
    void chapterTitlesFromNcx(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub2(dir, "old.epub");

        List<Chapter> chapters = parser.parseChapters(file, "id2");

        assertEquals(1, chapters.size());
        assertEquals("序章", chapters.get(0).title(), "ncx 里的标题优先于文档内的 h1");
    }

    @Test
    @DisplayName("偏移量存的是 spine 序号（EPUB 没有字节偏移可用）")
    void offsetsAreSpineIndexes(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);

        List<Chapter> chapters = parser.parseChapters(file, "id1");

        for (int i = 0; i < chapters.size(); i++) {
            assertEquals(i, chapters.get(i).startOffset());
            assertEquals(i, chapters.get(i).endOffset());
        }
    }

    // ==================== 正文 ====================

    @Test
    @DisplayName("正文按块提取：标题成 Heading，p 成 Paragraph")
    void loadsChapterBlocks(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);
        Chapter skeleton = parser.parseChapters(file, "id1").get(0);

        Chapter loaded = parser.loadChapter(file, skeleton);

        assertTrue(loaded.hasContent());
        assertEquals(3, loaded.blocks().size(), "一个 h1 + 两个 p");
        assertTrue(loaded.blocks().get(0) instanceof ChapterBlock.Heading);
        assertEquals("第一章 落星",
                ((ChapterBlock.Heading) loaded.blocks().get(0)).text());
        assertEquals("夜色像一块浸了水的布。",
                ((ChapterBlock.Paragraph) loaded.blocks().get(1)).text());
    }

    @Test
    @DisplayName("藏在 div 里的段落也要提出来，不能漏内容")
    void nestedParagraphIsExtracted(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub2(dir, "old.epub");
        Chapter skeleton = parser.parseChapters(file, "id2").get(0);

        Chapter loaded = parser.loadChapter(file, skeleton);

        String joined = loaded.blocks().stream()
                .filter(b -> b instanceof ChapterBlock.Paragraph)
                .map(b -> ((ChapterBlock.Paragraph) b).text())
                .reduce("", (a, b) -> a + b);
        assertTrue(joined.contains("这是第二段"), "div > p 这种嵌套不能丢");
    }

    @Test
    @DisplayName("安全：<script> 里的内容不会变成正文")
    void scriptIsDropped(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub2(dir, "old.epub");
        Chapter skeleton = parser.parseChapters(file, "id2").get(0);

        Chapter loaded = parser.loadChapter(file, skeleton);

        String all = loaded.blocks().stream()
                .map(this::blockText)
                .reduce("", (a, b) -> a + b);
        assertTrue(!all.contains("alert"), "脚本内容绝不能出现在正文里");
    }

    @Test
    @DisplayName("插图被抽出来，resourcePath 指向真实存在的文件")
    void imageIsExtracted(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);
        Chapter skeleton = parser.parseChapters(file, "id1").get(1);

        Chapter loaded = parser.loadChapter(file, skeleton);

        ChapterBlock.Image image = loaded.blocks().stream()
                .filter(b -> b instanceof ChapterBlock.Image)
                .map(b -> (ChapterBlock.Image) b)
                .findFirst()
                .orElse(null);
        assertNotNull(image, "第二章里有一张图");
        assertTrue(Files.isRegularFile(Path.of(image.resourcePath())),
                "抽出来的图必须真的落在磁盘上，渲染器才加载得到");
        assertTrue(Files.size(Path.of(image.resourcePath())) > 0);
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("不是 EPUB 时给出清楚的报错，而不是抛一堆底层异常")
    void rejectsNonEpub(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.notAnEpub(dir, "fake.epub");

        BookParseException e = assertThrows(BookParseException.class,
                () -> parser.parseMetadata(file));

        assertTrue(e.getMessage().contains("EPUB"), "报错要说到点子上：" + e.getMessage());
    }

    @Test
    @DisplayName("正文 XML 坏掉时退化成纯文本，不整章丢掉")
    void brokenXmlFallsBackToText(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.brokenChapter(dir, "broken.epub");
        Chapter skeleton = parser.parseChapters(file, "id3").get(0);

        Chapter loaded = parser.loadChapter(file, skeleton);

        assertTrue(loaded.blocks().stream().anyMatch(b -> b instanceof ChapterBlock.Paragraph),
                "解析失败也要把能读出的文字给出来");
    }

    @Test
    @DisplayName("章节序号越界时返回空章，不抛异常")
    void outOfRangeIndexYieldsEmptyChapter(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);
        Chapter bogus = Chapter.indexOnly("id1", 0, "第 N 章", null, 99, 99);

        Chapter loaded = parser.loadChapter(file, bogus);

        assertEquals(List.of(), loaded.blocks());
    }

    @Test
    @DisplayName("文件不存在时报 BookParseException")
    void missingFile() {
        assertThrows(BookParseException.class,
                () -> parser.parseMetadata(tempDir.resolve("没有这本.epub")));
    }

    @Test
    @DisplayName("没有资源泄漏：连续打开同一本书多次都正常（每次都新开 zip）")
    void repeatedOpenIsSafe(@TempDir Path dir) throws Exception {
        Path file = EpubTestBooks.epub3(dir, "book.epub", true);

        for (int i = 0; i < 3; i++) {
            List<Chapter> chapters = parser.parseChapters(file, "id1");
            for (Chapter chapter : chapters) {
                assertNotNull(parser.loadChapter(file, chapter));
            }
        }
    }

    // ==================== 辅助 ====================

    private String blockText(ChapterBlock block) {
        return switch (block) {
            case ChapterBlock.Paragraph p -> p.text();
            case ChapterBlock.Heading h -> h.text();
            case ChapterBlock.Image i -> i.resourcePath();
        };
    }
}
