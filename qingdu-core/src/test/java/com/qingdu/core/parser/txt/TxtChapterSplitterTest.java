package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Chapter;
import com.qingdu.core.text.TextCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TxtChapterSplitter} 的单元测试。
 *
 * <p>这里用的是"整本书"级别的测试数据 —— 因为三重校验的本质是
 * <b>行与行之间的位置关系</b>，只测单行是测不出问题的。
 * 每个测试都对应一种在真实 TXT 小说里确实会遇到的排版情况。
 */
@DisplayName("TXT 章节切分")
class TxtChapterSplitterTest {

    private static final String HEADER = "书名：测试之书\n作者：测试作者\n";

    private final TxtChapterSplitter splitter = new TxtChapterSplitter();

    private static List<Chapter> split(String text, TxtChapterSplitter splitter) {
        return splitter.split(text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");
    }

    /** 取出某一章的实际文本内容，用来验证字节偏移量算得对不对。 */
    private static String contentOf(String text, Chapter chapter) {
        return TextCodec.utf8().decode(text.getBytes(StandardCharsets.UTF_8),
                (int) chapter.startOffset(), (int) chapter.byteLength());
    }

    @Test
    @DisplayName("标准四章小说：章节数、序号、偏移量都正确")
    void normalBook() {
        String text = HEADER + """

                第一章 星尘之始

                夜空中的星光落下来的时候，没有人注意到。

                他站在窗边，看着远处的城市灯火。

                第二章 远行

                第二天清晨，他收拾好行李。

                母亲站在门口，什么也没说。

                第三章 夜访

                夜里有人敲门，他去开了。

                门外站着一个陌生人。

                第四章 终局

                一切都在这一天结束了。

                他终于可以休息了。
                """;

        List<Chapter> chapters = split(text, splitter);

        assertEquals(4, chapters.size());
        assertEquals("第一章 星尘之始", chapters.get(0).title());
        assertEquals("第二章 远行", chapters.get(1).title());
        assertEquals("第四章 终局", chapters.get(3).title());

        // 序号必须从 0 开始连续
        for (int i = 0; i < chapters.size(); i++) {
            assertEquals(i, chapters.get(i).index());
        }
        // 偏移量必须递增，且首尾相接（前一章的结束就是后一章的开始）
        for (int i = 0; i + 1 < chapters.size(); i++) {
            assertEquals(chapters.get(i).endOffset(), chapters.get(i + 1).startOffset(),
                    "章节之间不能有空洞或重叠");
        }
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length,
                chapters.get(chapters.size() - 1).endOffset());
    }

    @Test
    @DisplayName("按偏移量切出来的内容，确实属于对应章节")
    void offsetsPointToTheRightText() {
        String text = HEADER + """

                第一章 甲

                这是甲章的正文。

                第二章 乙

                这是乙章的正文。

                第三章 丙

                这是丙章的正文。
                """;

        List<Chapter> chapters = split(text, splitter);

        assertEquals(3, chapters.size());
        String first = contentOf(text, chapters.get(0));
        String second = contentOf(text, chapters.get(1));

        assertTrue(first.startsWith("第一章 甲"), "第一章内容应以自己的标题开头");
        assertTrue(first.contains("这是甲章的正文"), "第一章应包含甲章正文");
        assertFalse(first.contains("这是乙章的正文"), "第一章不该混入乙章内容");
        assertTrue(second.startsWith("第二章 乙"));
        assertTrue(second.contains("这是乙章的正文"));
    }

    @Test
    @DisplayName("带目录页：目录条目要被丢弃，正文第一章要保留")
    void tableOfContentsIsDropped() {
        String text = HEADER + """

                目录

                第一章 目录条目一
                第二章 目录条目二
                第三章 目录条目三
                第四章 目录条目四
                第五章 目录条目五
                第六章 目录条目六

                第一章 真正的开始

                真正的第一章正文第一段。

                真正的第一章正文第二段。

                第二章 远行

                真正的第二章正文。

                第三章 夜访

                真正的第三章正文。

                第四章 终局

                真正的第四章正文。
                """;

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        assertEquals(6, report.tocDropped(), "六个目录条目都应该被识别出来");

        List<String> titles = report.chapters().stream().map(Chapter::title).toList();
        assertTrue(titles.contains("第一章 真正的开始"), "正文第一章必须保留：" + titles);
        assertTrue(titles.contains("第四章 终局"), "正文最后一章必须保留：" + titles);
        for (String title : titles) {
            assertFalse(title.contains("目录条目"), "目录条目不该出现在结果里：" + title);
        }
    }

    @Test
    @DisplayName("正文里引述章节号，不能被当成分章")
    void bodyTextQuotingChapterNumberIsIgnored() {
        String text = HEADER + """

                第一章 真正的开始

                他忽然想起，第三章的内容他其实完全没有看懂。

                第三章的内容他其实完全没有看懂。

                这句话在正文里出现了，但它不是标题。

                第二章 远行

                第二章的正文在这里。

                还有一行普通的正文。

                第三章 夜访

                第三章的正文在这里。

                再补一行正文凑数。
                """;

        List<Chapter> chapters = split(text, splitter);

        assertEquals(3, chapters.size());
        assertEquals("第一章 真正的开始", chapters.get(0).title());
        // 引述句必须落在第一章的正文范围内，而不是自成章节
        assertTrue(contentOf(text, chapters.get(0)).contains("第三章的内容他其实完全没有看懂"));
    }

    @Test
    @DisplayName("没有分章结构的文件：退化成单章「全文」")
    void bookWithoutChaptersFallsBackToSingleChapter() {
        String text = "这是一本没有章节划分的散文集。\n\n"
                + "第一段文字，用来测试兜底逻辑是否生效。\n\n"
                + "第二段文字，继续测试。\n\n"
                + "第三段文字，结束。\n";

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        assertEquals(1, report.chapters().size());
        assertEquals(TxtChapterSplitter.WHOLE_BOOK_TITLE, report.chapters().get(0).title());
        assertTrue(report.fallback());
        assertEquals(0, report.chapters().get(0).startOffset());
    }

    @Test
    @DisplayName("章节数太少（不足 3 章）：同样退化成单章")
    void tooFewChaptersFallBack() {
        String text = HEADER + """

                第一章 甲

                这是甲章正文，只有两章不足以说明有稳定的分章结构。

                第二章 乙

                这是乙章正文。
                """;

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        assertEquals(1, report.chapters().size());
        assertTrue(report.fallback());
    }

    @Test
    @DisplayName("分卷小说：卷标题只作分组不占章节，每卷重新计数不被序号规则误杀")
    void volumeResetIsHandled() {
        String text = HEADER + """

                第一卷 启程

                第一章 出山

                甲章正文。

                第二章 入城

                乙章正文。

                第二卷 风起

                第一章 重逢

                丙章正文。

                第二章 分别

                丁章正文。
                """;

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        // 卷标题不再是章节：全书只剩 4 个真章节（原先会把 2 个卷头也算进去）
        assertEquals(4, report.chapters().size(), "卷标题不占章节，只剩 4 个真章节");
        assertTrue(report.volumeResets() >= 1, "应该识别出卷边界重置");
        assertFalse(report.fallback());

        List<Chapter> chapters = report.chapters();
        assertEquals("第一章 出山", chapters.get(0).title());
        assertEquals("第二章 分别", chapters.get(3).title());

        // 卷标题挂到「它之后、下一卷之前」的每一章上
        assertEquals("第一卷 启程", chapters.get(0).volumeTitle());
        assertEquals("第一卷 启程", chapters.get(1).volumeTitle());
        assertEquals("第二卷 风起", chapters.get(2).volumeTitle());
        assertEquals("第二卷 风起", chapters.get(3).volumeTitle());
        assertTrue(chapters.get(0).hasVolume(), "第一章应能查到所属卷");

        // 不变量：卷标题的字节没被丢弃，它落在「上一章末尾」这个间隙里
        assertTrue(contentOf(text, chapters.get(1)).contains("第二卷 风起"),
                "卷标题那行字节应归入上一章末尾");
    }

    @Test
    @DisplayName("卷标题出现在全书最开头：不占章节，也不会被当成「开篇」")
    void leadingVolumeTitleDoesNotBecomeChapter() {
        String text = HEADER + """

                第一卷 启程

                第一章 出山

                甲章正文。

                第二章 入城

                乙章正文。

                第三章 归乡

                丙章正文。
                """;

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        List<Chapter> chapters = report.chapters();
        assertFalse(report.fallback(), "3 章已够数，不该退化成单章");
        assertEquals(3, chapters.size(), "卷标题和它前后的空行都不该变成章节");
        // 第一章即开篇位置，说明前置的卷标题没被当成「开篇」独立成章
        assertEquals("第一章 出山", chapters.get(0).title());
        assertEquals("第一卷 启程", chapters.get(0).volumeTitle());
        assertEquals("第一卷 启程", chapters.get(1).volumeTitle());
        assertEquals("第一卷 启程", chapters.get(2).volumeTitle());
        assertTrue(contentOf(text, chapters.get(0)).startsWith("第一章 出山"),
                "第一章的起点应是标题那一行，卷标题不计入正文头");
    }

    @Test
    @DisplayName("首个标题之前的文字够长时，单独作为「开篇」")
    void prefixBecomesItsOwnChapter() {
        String text = "作者的话：这本书写了三年，感谢每一位读过它的人，也感谢那个没有放弃的自己。\n\n"
                + "第一章 开始\n\n甲章正文。\n\n"
                + "第二章 继续\n\n乙章正文。\n\n"
                + "第三章 结束\n\n丙章正文。\n";

        List<Chapter> chapters = split(text, splitter);

        assertEquals(4, chapters.size());
        assertEquals(TxtChapterSplitter.PREFIX_TITLE, chapters.get(0).title());
        assertEquals(0, chapters.get(0).startOffset());
        assertTrue(contentOf(text, chapters.get(0)).contains("这本书写了三年"));
    }

    @Test
    @DisplayName("候选标题太少时不启用目录过滤，避免误伤")
    void tocFilterIsSkippedWhenFewCandidates() {
        // 三个标题紧挨着，但总数不足 TOC_RUN_MIN+1，不该被判成目录
        String text = "第一章 甲\n第二章 乙\n第三章 丙\n";

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        assertEquals(0, report.tocDropped());
    }

    @Test
    @DisplayName("过程报告能说清每一步丢了多少")
    void reportSummarisesPipeline() {
        String text = HEADER + """

                目录

                第一章 甲
                第二章 乙
                第三章 丙
                第四章 丁

                第一章 甲

                第一段的正文内容在这里，够长。

                第二章 乙

                第二段的正文内容在这里，也够长。

                第三章 丙

                第三段的正文内容在这里，还是够长。
                """;

        TxtChapterSplitter.Report report = splitter.analyze(
                text.getBytes(StandardCharsets.UTF_8), TextCodec.utf8(), "book-1");

        assertEquals(7, report.candidateCount(), "4 个目录条目 + 3 个真章节");
        assertEquals(4, report.tocDropped());
        assertTrue(report.summary().contains("最终"), "摘要应该是可读的一句话：" + report.summary());
    }
}
