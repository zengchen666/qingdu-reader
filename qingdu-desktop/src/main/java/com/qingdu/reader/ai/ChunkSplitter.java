package com.qingdu.reader.ai;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.reader.ai.AiModels.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

/**
 * 把章节切成送给 AI 的片段。
 *
 * <h2>🔴 为什么不自己按空行再切一遍</h2>
 * 段落是 {@link ChapterBlock.Paragraph}——<b>轻读自己的既有概念</b>，
 * 解析引擎已经把它从 TXT 里切出来了。如果这里再用「按空行 split」切一次，
 * 就会出现两套互不知情的切分标准：解析引擎认为是一段的，
 * 这里可能切成三段；反过来也一样。**引用的段落号会与界面显示的对不上**，
 * 而这种错位极难排查（看起来一切正常，只是点跳过去的位置不对）。
 *
 * <h2>为什么不排序</h2>
 * 排序是 Python 侧 {@code retrieval.select()} 的责任。
 * 轻读再排一次就是<b>两处真理</b>——迟早有一处忘了改，两边顺序不一致，
 * 引用编号对不上。所以本类<b>严格保持章节与段落的原始顺序</b>，
 * 只做"把命中的章切好、按顺序摆出来"。
 *
 * <h2>片段长度上限</h2>
 * T TXT 小说里存在"整章就是一行"的极端情况。切出来的段可能上万字，
 * 送进模型会直接撑爆上下文。这里按 {@link #MAX_CHARS} 截断，
 * 与 Python 侧 {@code retrieval.MAX_CHARS} 保持一致。
 * 截断而不是丢弃：内容再长也总比没有强，且引用仍能定位到那一段。
 */
public final class ChunkSplitter {

    /**
     * 单片段字符上限，与 Python 侧 {@code retrieval.MAX_CHARS} 一致。
     * 1200 字 ≈ 1800 token，{@code topK=8} 满打满算约 1.4 万 token。
     */
    public static final int MAX_CHARS = 1200;

    private ChunkSplitter() {
    }

    /**
     * 把一个命中章节切成片段。
     *
     * @param book       书（提供 bookId / 书名）
     * @param chapter    章节骨架
     * @param blocks     章节内容块
     * @return 片段列表；该章没有正文段落时返回空 List（<b>不返回 null</b>）
     */
    public static List<Chunk> splitChapter(Book book, Chapter chapter,
                                           List<ChapterBlock> blocks) {
        List<Chunk> out = new ArrayList<>();
        if (book == null || chapter == null || blocks == null || blocks.isEmpty()) {
            return out;
        }
        String bookTitle = book.title() == null ? "" : book.title();

        // 只取 Paragraph —— Heading 是章内小标题、Image 是插图，
        // 两者都不是"正文段落"，混进去会让引用指到标题上。
        int paragraphIndex = 0;
        for (ChapterBlock block : blocks) {
            if (!(block instanceof ChapterBlock.Paragraph p)) {
                continue;
            }
            String text = p.text() == null ? "" : p.text().trim();
            if (text.isEmpty()) {
                // 空段不占编号：编号连续比"忠实反映 blocks 位置"更重要，
                // 用户读「第 3 段」时不会莫名指向一个空行。
                continue;
            }
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "…";
            }
            out.add(new Chunk(
                    book.id(),
                    bookTitle,
                    chapter.index(),
                    chapter.title() == null ? "" : chapter.title(),
                    paragraphIndex,
                    text));
            paragraphIndex++;
        }
        return out;
    }

    /**
     * 多章片段合并成一个列表，<b>按章节序号升序</b>。
     *
     * <p><b>🔴 参数类型必须是 {@link java.util.SortedMap}，不能是 {@link Map}。</b>
     * 这一点是被测试逼出来的：原来签名收 {@code Map}，而
     * {@code Map.of(0, …, 1, …)} 是<b>无序的</b>，两章的先后随机变化，
     * 同一个输入会产出不同顺序的片段 —— 这与本类"保持原始顺序"的契约直接冲突，
     * 而且会让引用编号在两次运行间对不上。
     *
     * <p>用 {@link java.util.TreeMap} 之类的有序 Map 传入即可，
     * 顺序由<b>章节序号</b>决定，不依赖调用方的插入顺序。
     *
     * @param chapterTexts 章节序号 → 该章内容块
     * @param maxChunks    最多产出多少片段。**这只是保护上限**：
     *                    Python 侧还会做去重、打散、截断，
     *                    这里不用替它做决策
     */
    public static List<Chunk> collect(Book book, SortedMap<Integer, List<ChapterBlock>> chapterTexts,
                                      int maxChunks) {
        return collect(book, chapterTexts, maxChunks, null);
    }

    /**
     * 同 {@link #collect}，但能把章节标题一起带进片段。
     *
     * <p><b>为什么标题要单独传</b>：{@code chapterTexts} 的 value 只有内容块
     * —— {@link ChapterBlock} 里没有章名（章名在 {@link Chapter#title()} 上）。
     * 而引用列表显示的是「[1] 第十二章 xxx」，
     * 缺了标题就只剩一个孤零零的编号，用户核对时还得自己去猜是哪一章。
     *
     * @param titleOf 章节序号 → 章节标题；可以为 {@code null}（标题留空）
     */
    public static List<Chunk> collect(Book book, SortedMap<Integer, List<ChapterBlock>> chapterTexts,
                                      int maxChunks,
                                      java.util.function.IntFunction<String> titleOf) {
        List<Chunk> out = new ArrayList<>();
        if (book == null || chapterTexts == null || chapterTexts.isEmpty() || maxChunks <= 0) {
            return out;
        }
        for (var entry : chapterTexts.entrySet()) {
            if (out.size() >= maxChunks) {
                break;
            }
            int chapterIndex = entry.getKey();
            List<ChapterBlock> blocks = entry.getValue();
            String title = titleOf == null ? "" : titleOf.apply(chapterIndex);
            // 偏移传 -1：这里只要 index 与 title，偏移由上层按真实章读原文时用。
            Chapter skeleton = Chapter.indexOnly(book.id(), chapterIndex,
                    title == null ? "" : title, -1L, -1L);
            out.addAll(splitChapter(book, skeleton, blocks));
        }
        // 截断到上限（上面的 break 只在章边界生效，一章内可能已超）
        return out.size() > maxChunks ? out.subList(0, maxChunks) : out;
    }
}
