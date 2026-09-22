package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.core.parser.BookParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChapterTextBatch} 的测试。
 *
 * <p>它存在的意义是"建索引的性能"，而性能这件事在单元测试里测不出来。
 * 所以这里只钉死<b>正确性</b>：按字节偏移切出来的每一章，
 * 必须和逐章 {@code loadChapter} 读出来的内容一致。
 * 一旦哪天改成流式读取、或者偏移量算错，这个测试会立刻红。
 */
@DisplayName("整本批量读章")
class ChapterTextBatchTest {

    private static final String BOOK_TEXT = """
            第一章 星尘之始

            夜空中的星光落下来的时候，没有人注意到。

            他站在窗边，看着远处的城市灯火。

            第二章 远行

            第二天清晨，他收拾好行李。

            母亲站在门口，什么也没说。

            第三章 夜访

            夜里有人敲门，他起身去开。
            """;

    @TempDir
    Path tempDir;

    private final TxtBookParser parser = new TxtBookParser();

    private Path writeBook() throws IOException {
        Path file = tempDir.resolve("book.txt");
        Files.writeString(file, BOOK_TEXT, StandardCharsets.UTF_8);
        return file;
    }

    private List<Chapter> chaptersOf(Path file) throws BookParseException {
        Book book = parser.parseMetadata(file);
        return parser.parseChapters(file, book.id());
    }

    @Test
    @DisplayName("按偏移切出来的每一章，内容要和逐章读取一致")
    void sliceMatchesPerChapterRead() throws Exception {
        Path file = writeBook();
        List<Chapter> chapters = chaptersOf(file);
        assertTrue(chapters.size() >= 3, "样本书至少要切出 3 章，否则这条用例没意义");

        ChapterTextBatch batch = ChapterTextBatch.load(file, chapters);

        for (int i = 0; i < chapters.size(); i++) {
            Chapter loaded = parser.loadChapter(file, chapters.get(i));
            String fromBatch = batch.textOf(i);
            String fromParser = loaded.blocks().stream()
                    .map(ChapterTextBatchTest::blockText)
                    .reduce("", (a, b) -> a + b);
            // 逐章读取会把空行和行尾空格清掉，比较时统一去掉空白
            assertEquals(strip(fromParser), strip(fromBatch), "第 " + i + " 章内容不一致");
        }
    }

    @Test
    @DisplayName("越界的章号返回空串，不抛异常")
    void outOfRangeReturnsEmpty() throws Exception {
        Path file = writeBook();
        ChapterTextBatch batch = ChapterTextBatch.load(file, chaptersOf(file));

        assertEquals("", batch.textOf(-1));
        assertEquals("", batch.textOf(9999));
    }

    @Test
    @DisplayName("allTexts 会按顺序回调进度，最后一章是 size-1")
    void allTextsReportsProgress() throws Exception {
        Path file = writeBook();
        List<Chapter> chapters = chaptersOf(file);
        ChapterTextBatch batch = ChapterTextBatch.load(file, chapters);

        List<Integer> progress = new ArrayList<>();
        List<String> texts = batch.allTexts(progress::add);

        assertEquals(chapters.size(), texts.size());
        assertEquals(chapters.size(), batch.size());
        assertEquals(chapters.size(), progress.size());
        assertEquals(chapters.size() - 1, progress.get(progress.size() - 1));
        assertTrue(texts.get(0).contains("星尘之始"));
    }

    @Test
    @DisplayName("整本书的字节数要和文件一致（用于体积诊断）")
    void byteLengthMatchesFile() throws Exception {
        Path file = writeBook();
        ChapterTextBatch batch = ChapterTextBatch.load(file, chaptersOf(file));

        assertEquals(Files.size(file), batch.byteLength());
    }

    @Test
    @DisplayName("文件不存在或没有章节时明确报错")
    void rejectsInvalidInput() throws Exception {
        Path missing = tempDir.resolve("nope.txt");

        assertThrows(BookParseException.class,
                () -> ChapterTextBatch.load(missing, List.of(chaptersOf(writeBook()).get(0))));
        assertThrows(BookParseException.class,
                () -> ChapterTextBatch.load(writeBook(), List.of()));
    }

    /** 把内容块还原成一行文本，用来和批量读取的结果对比。 */
    private static String blockText(com.qingdu.common.domain.ChapterBlock block) {
        return switch (block) {
            case com.qingdu.common.domain.ChapterBlock.Heading h -> h.text();
            case com.qingdu.common.domain.ChapterBlock.Paragraph p -> p.text();
            case com.qingdu.common.domain.ChapterBlock.Image img -> img.resourcePath();
        };
    }

    private static String strip(String text) {
        return text.replaceAll("[\\s\\u3000]+", "");
    }
}
