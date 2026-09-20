package com.qingdu.common.domain;

/**
 * 章节内容块 —— 本项目的核心抽象之一。
 *
 * <p><b>为什么需要它？</b><br>
 * 一本小说的正文不是一整块字符串，而是"段落 / 标题 / 图片"的序列。
 * 把这个序列抽象出来，阅读界面就只需要认识这几种块，不用关心
 * 内容到底来自 TXT 还是 EPUB。
 *
 * <p><b>为什么用 sealed（密封）接口？</b><br>
 * {@code sealed} 是 Java 17 引入的特性，它限定了"只允许这几个类实现我"。
 * 带来的好处是编译器能校验穷举：
 * <pre>{@code
 * switch (block) {
 *     case ChapterBlock.Paragraph p -> renderParagraph(p);
 *     case ChapterBlock.Heading   h -> renderHeading(h);
 *     case ChapterBlock.Image     i -> renderImage(i);
 * }   // 不需要 default，编译器知道已经覆盖全部情况
 * }</pre>
 * 以后新增一种块类型，所有 switch 会立刻编译报错提醒你补上处理逻辑 ——
 * 这比运行时才发现漏处理要安全得多。
 */
public sealed interface ChapterBlock
        permits ChapterBlock.Paragraph, ChapterBlock.Heading, ChapterBlock.Image {

    /**
     * 普通正文段落。
     *
     * @param text 段落文字（已去除首尾空白）
     */
    record Paragraph(String text) implements ChapterBlock {
        public Paragraph {
            if (text == null) {
                text = "";
            }
        }
    }

    /**
     * 标题（章内的小标题，不是章节名本身）。
     *
     * @param level 标题层级，1 表示一级标题
     * @param text  标题文字
     */
    record Heading(int level, String text) implements ChapterBlock {
    }

    /**
     * 插图。
     *
     * <p>这里只存"资源路径"而不存图片字节数组，是为了避免把整本书的图片
     * 一次性读进内存 —— 图片按需从缓存目录加载即可。
     *
     * @param resourcePath 图片资源的相对路径
     */
    record Image(String resourcePath) implements ChapterBlock {
    }
}
