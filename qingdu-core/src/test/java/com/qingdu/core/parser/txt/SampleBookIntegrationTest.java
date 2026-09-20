package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Chapter;
import com.qingdu.core.text.CharsetDetector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 示例文件的端到端测试。
 *
 * <p>{@code samples/} 目录下那两份「星尘纪」是特意造的"刁钻样本"，
 * 里面埋了三个坑：
 * <ol>
 *   <li><b>开头的目录页</b> —— 七个目录条目长得和真章节一模一样</li>
 *   <li><b>正文里引述章节号</b> —— "参考书里说，第三章的内容是全篇的关键转折。"</li>
 *   <li><b>两种编码</b> —— 同一份内容分别存成 GBK 和 UTF-8</li>
 * </ol>
 * 这个测试把"真实文件确实能被正确处理"这件事固定下来，
 * 以后改动分章算法时，它是第一道防线。
 *
 * <p>找不到 samples 目录时测试会自动跳过（用 {@code assumeTrue}），
 * 这样别人单独把 qingdu-core 模块拿去跑测试也不会失败。
 */
@DisplayName("示例小说端到端")
class SampleBookIntegrationTest {

    private static final String GBK_SAMPLE = "星尘纪-示例-GBK.txt";
    private static final String UTF8_SAMPLE = "星尘纪-示例-UTF8.txt";

    private static Path samplesDir;

    @BeforeAll
    static void locateSamples() {
        samplesDir = findSamplesDir().orElse(null);
    }

    /** 从当前工作目录向上找 4 层，定位 samples 目录。 */
    private static Optional<Path> findSamplesDir() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && cursor != null; i++) {
            Path candidate = cursor.resolve("samples");
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    private Path requireSample(String name) {
        assumeTrue(samplesDir != null, "找不到 samples 目录，跳过示例测试");
        Path file = samplesDir.resolve(name);
        assumeTrue(Files.isRegularFile(file), "找不到示例文件 " + name + "，跳过");
        return file;
    }

    @Test
    @DisplayName("GBK 示例：编码识别正确，目录页被丢弃，七个章节全部保留")
    void gbkSampleIsParsedCorrectly() throws Exception {
        Path file = requireSample(GBK_SAMPLE);

        // 第一步：编码
        CharsetDetector.Detection detection = CharsetDetector.detect(file);
        assertEquals(CharsetDetector.GB18030, detection.charset(),
                "GBK 文件应被识别为 GB18030：" + detection.reason());

        // 第二步：元信息
        TxtBookParser parser = new TxtBookParser();
        var book = parser.parseMetadata(file);
        assertEquals("星尘纪", book.title(), "书名应来自文件里的「书名：」字段");
        assertEquals("示例作者", book.author());

        // 第三步：分章
        TxtChapterSplitter.Report report = parser.index(file, book.id());
        assertEquals(7, report.tocDropped(), "七个目录条目都应被识别并丢弃");
        assertFalse(report.fallback(), "不该退化成单章");

        List<String> titles = report.chapters().stream().map(Chapter::title).toList();
        assertEquals(8, titles.size(), "开篇 + 7 章：" + titles);
        assertEquals(TxtChapterSplitter.PREFIX_TITLE, titles.get(0));
        assertEquals("第一章 星光落下", titles.get(1));
        assertEquals("第六章 终局", titles.get(6));
        assertEquals("番外 那年夏天", titles.get(7));
    }

    @Test
    @DisplayName("正文里引述章节号不会自成章节")
    void bodyQuoteDoesNotBecomeChapter() throws Exception {
        Path file = requireSample(GBK_SAMPLE);
        TxtBookParser parser = new TxtBookParser();
        var book = parser.parseMetadata(file);
        List<Chapter> chapters = parser.parseChapters(file, book.id());

        // 第一章里那句"参考书里说，第三章的内容是全篇的关键转折。"必须留在第一章内
        Chapter first = chapters.stream()
                .filter(c -> c.title().equals("第一章 星光落下"))
                .findFirst()
                .orElseThrow();
        Chapter loaded = parser.loadChapter(file, first);
        String text = loaded.blocks().stream()
                .map(b -> switch (b) {
                    case com.qingdu.common.domain.ChapterBlock.Paragraph p -> p.text();
                    case com.qingdu.common.domain.ChapterBlock.Heading h -> h.text();
                    case com.qingdu.common.domain.ChapterBlock.Image i -> i.resourcePath();
                })
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(text.contains("参考书里说，第三章的内容是全篇的关键转折"),
                "引述句应留在第一章正文里");
        assertFalse(text.contains("第二章"), "不该夹带第二章的内容");
    }

    @Test
    @DisplayName("UTF-8 示例：同一份内容，分章结果应与 GBK 版完全一致")
    void utf8SampleMatchesGbkSample() throws Exception {
        Path gbk = requireSample(GBK_SAMPLE);
        Path utf8 = requireSample(UTF8_SAMPLE);
        TxtBookParser parser = new TxtBookParser();

        List<String> fromGbk = parser.parseChapters(gbk, "b").stream().map(Chapter::title).toList();
        List<String> fromUtf8 = parser.parseChapters(utf8, "b").stream().map(Chapter::title).toList();

        assertEquals(fromGbk, fromUtf8, "换一种编码不该改变分章结果");
        assertEquals(CharsetDetector.GB18030, CharsetDetector.detect(gbk).charset());
        assertEquals(StandardCharsets.UTF_8, CharsetDetector.detect(utf8).charset());
    }

    @Test
    @DisplayName("每一章的偏移量都能读出非空正文，且不互相重叠")
    void everyChapterReadsBackNonEmptyContent() throws Exception {
        Path file = requireSample(GBK_SAMPLE);
        TxtBookParser parser = new TxtBookParser();
        var book = parser.parseMetadata(file);
        List<Chapter> chapters = parser.parseChapters(file, book.id());

        long previousEnd = -1;
        long fileSize = Files.size(file);
        for (Chapter chapter : chapters) {
            assertTrue(chapter.startOffset() >= previousEnd, "章节区间不能重叠");
            assertTrue(chapter.endOffset() <= fileSize, "章节区间不能越界");
            previousEnd = chapter.endOffset();

            Chapter loaded = parser.loadChapter(file, chapter);
            assertTrue(loaded.hasContent(), "章节应能读出内容：" + chapter.title());
            assertTrue(loaded.blocks().stream().anyMatch(b -> !textOf(b).isBlank()),
                    "章节内容不该是空白：" + chapter.title());
        }
        assertEquals(fileSize, chapters.get(chapters.size() - 1).endOffset(),
                "最后一章应一直读到文件末尾");
    }

    private static String textOf(com.qingdu.common.domain.ChapterBlock block) {
        return switch (block) {
            case com.qingdu.common.domain.ChapterBlock.Paragraph p -> p.text();
            case com.qingdu.common.domain.ChapterBlock.Heading h -> h.text();
            case com.qingdu.common.domain.ChapterBlock.Image i -> i.resourcePath();
        };
    }

    /** 顺便确保示例文件本身没被误删或改坏。 */
    @Test
    @DisplayName("两份示例文件都存在且非空")
    void samplesExist() {
        Stream.of(GBK_SAMPLE, UTF8_SAMPLE).forEach(name -> {
            Path file = requireSample(name);
            try {
                assertTrue(Files.size(file) > 500, name + " 内容过短，可能已损坏");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }
}
