package com.qingdu.store.model;

/**
 * 一本书的阅读位置。
 *
 * <p><b>{@code scrollRatio} 到底是什么？</b><br>
 * 它是 JavaFX {@code ScrollPane} 的 {@code vvalue} —— 当前滚动位置占
 * "可滚动范围"的比例，取值 0~1。
 *
 * <p>为什么不存"第几个字"？因为按字节偏移定位到章之后，再往下就精确不到字了：
 * 一条长段落被自动换行成多少行，取决于窗口宽度、字体、字号 ——
 * 这些一变，字符位置和屏幕位置的对应关系就变了。
 * 存比例的好处是<b>它对排版变化是"相对稳定"的</b>：
 * 换个字号复原时不会跳到章节开头，只会在附近小幅偏移。
 *
 * <p>顺带一提，{@code vvalue} 在"内容比视口还短"时恒为 0 ——
 * 这种章节本来就一屏装得下，回到顶部正好是想要的效果。
 *
 * @param bookId       所属图书
 * @param chapterIndex 章节序号，从 0 开始
 * @param chapterTitle 章节标题（冗余存一份，为了在"最近打开"列表里显示而不必去解析原文件）
 * @param scrollRatio  章内滚动位置，0~1
 * @param updatedAt    最后阅读时间（epoch 毫秒）
 */
public record ReadingProgress(
        String bookId,
        int chapterIndex,
        String chapterTitle,
        double scrollRatio,
        long updatedAt
) {

    public ReadingProgress {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        chapterIndex = Math.max(0, chapterIndex);
        // 钳到 0~1：数据库里的值可能来自旧版本或被手改过
        scrollRatio = Math.max(0, Math.min(1, scrollRatio));
    }

    /**
     * 有没有真正开始读。
     *
     * <p>刚打开还没翻页时也会存一条"第 0 章、进度 0"的记录，
     * 这种记录在界面上显示成"尚未开始阅读"比显示"0%"更自然。
     */
    public boolean started() {
        return chapterIndex > 0 || scrollRatio > 0.001;
    }

    /** 给界面显示的百分比，例如 "37%"。 */
    public String percentLabel() {
        return Math.round(scrollRatio * 100) + "%";
    }
}
