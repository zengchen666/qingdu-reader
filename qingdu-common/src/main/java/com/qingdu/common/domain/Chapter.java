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
 * @param startOffset 起始位置（TXT 为字节偏移）
 * @param endOffset   结束位置（不含）
 * @param blocks      内容块列表。可能为空 —— 表示"已建索引但正文尚未加载"
 */
public record Chapter(
        String bookId,
        int index,
        String title,
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
     */
    public Chapter {
        if (index < 0) {
            throw new IllegalArgumentException("章节序号不能为负数: " + index);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "结束位置不能小于起始位置: start=" + startOffset + ", end=" + endOffset);
        }
        blocks = (blocks == null) ? List.of() : List.copyOf(blocks);
    }

    /** 创建一个"只有目录信息、正文还没加载"的章节骨架。 */
    public static Chapter indexOnly(String bookId, int index, String title,
                                    long startOffset, long endOffset) {
        return new Chapter(bookId, index, title, startOffset, endOffset, List.of());
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
