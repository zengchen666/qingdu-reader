package com.qingdu.common.domain;

import java.util.List;

/**
 * 统一的章节模型 —— 本项目最重要的一个设计。
 *
 * <p>TXT 和 EPUB 的解析路径完全不同，但对阅读器来说都归结为一件事：
 * <b>按章节读正文</b>。所以中间抽象出这个模型，把两种格式的差异挡在解析层，
 * 渲染层只认 {@code Chapter}。
 *
 * <p><b>startOffset / endOffset 的双重含义</b>（这是理解本项目性能优化的关键）：
 * <ul>
 *   <li>TXT：它们是<b>文件里的字节偏移量</b>。靠这两个数字，我们不必把
 *       100MB 的文件读进内存，只要 seek 到对应位置读那一小段就行。</li>
 *   <li>EPUB：它们是<b>章节在 spine 中的序号</b>（转成 long 存放），
 *       因为 EPUB 每章独立成文件，不需要字节偏移。</li>
 * </ul>
 *
 * @param bookId      所属图书 ID
 * @param index       章节序号，从 0 开始
 * @param title       章节标题，例如 "第三章 夜访"
 * @param volumeTitle 所属分卷的标题，没有分卷时为 null。
 *                    <b>分卷标题不单独占一个章节</b> —— 它只是分组信息
 *                    （详见 {@code TxtChapterSplitter} 里"卷标题独立成章"的说明）
 * @param startOffset 起始位置（TXT 为字节偏移）
 * @param endOffset   结束位置（不含）
 * @param blocks      内容块列表。可能为空 —— 表示"已建索引但正文尚未加载"
 */
public record Chapter(
        String bookId,
        int index,
        String title,
        String volumeTitle,
        long startOffset,
        long endOffset,
        List<ChapterBlock> blocks
) {

    /**
     * 紧凑构造器：对入参做校验和防御性拷贝。
     *
     * <p>把 {@code blocks} 包一层 {@code List.copyOf()} 是为了不可变 ——
     * record 本身是浅不可变的，如果直接持有外部传入的可变 List，
     * 外部代码仍能往里加元素。这是 record 使用时的常见坑。
     *
     * <p>{@code volumeTitle} 统一归一化：空白串一律存成 null，
     * 免得调用方要同时判 {@code != null} 和 {@code !isBlank()}。
     */
    public Chapter {
        if (index < 0) {
            throw new IllegalArgumentException("章节序号不能为负数: " + index);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "结束位置不能小于起始位置: start=" + startOffset + ", end=" + endOffset);
        }
        volumeTitle = (volumeTitle == null || volumeTitle.isBlank()) ? null : volumeTitle.strip();
        blocks = (blocks == null) ? List.of() : List.copyOf(blocks);
    }

    /** 创建一个"只有目录信息、正文还没加载"的章节骨架（无分卷信息）。 */
    public static Chapter indexOnly(String bookId, int index, String title,
                                    long startOffset, long endOffset) {
        return indexOnly(bookId, index, title, null, startOffset, endOffset);
    }

    /** 创建一个带分卷归属的章节骨架。 */
    public static Chapter indexOnly(String bookId, int index, String title, String volumeTitle,
                                    long startOffset, long endOffset) {
        return new Chapter(bookId, index, title, volumeTitle, startOffset, endOffset, List.of());
    }

    /** 本章是否属于某个分卷。目录侧栏据此渲染分组头。 */
    public boolean hasVolume() {
        return volumeTitle != null;
    }

    /**
     * 相对上一章，本章是否"翻开了一卷"。
     *
     * <p>卷标题不单独占章节（只有几十个字节，点进去是一片空白），
     * 所以目录侧栏要靠这个判断在哪一章上方插卷头：
     * <b>本章带卷名、而上一章没带（或换了一个卷名）</b>时为真。
     * 同一卷的后续章节都返回 false，卷头因此只出现一次。
     *
     * <p>放在模型上而不是写在界面代码里，是为了让它能被单元测试直接覆盖 ——
     * 界面本身跑不起来的地方，至少判断逻辑是验证过的。
     *
     * @param previous 目录里的上一章；本章是第一章时传 {@code null}
     */
    public boolean startsNewVolume(Chapter previous) {
        if (volumeTitle == null) {
            return false;
        }
        return previous == null || !volumeTitle.equals(previous.volumeTitle());
    }

    /** 正文是否已加载。为 false 时说明需要按偏移量去读取真正的正文。 */
    public boolean hasContent() {
        return !blocks.isEmpty();
    }

    /** 本章的字节跨度，用于统计和性能分析。 */
    public long byteLength() {
        return endOffset - startOffset;
    }
}
