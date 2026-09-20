package com.qingdu.store.model;

/**
 * 一条书签。
 *
 * <p>书签和"阅读进度"的区别在于：<b>进度只有一条，书签可以有很多条</b>，
 * 而且书签是用户主动标记的，不该被自动覆盖。
 * 所以它们虽然在数据结构上很像（都是"哪一章 + 章内什么位置"），
 * 但存在两张表里，生命周期也完全不同 —— 关掉一本书会更新进度，
 * 但不该动书签。
 *
 * @param id           自增主键，删除时用它定位；{@code 0} 表示还没入库
 * @param bookId       所属图书
 * @param chapterIndex 章节序号
 * @param chapterTitle 章节标题（冗余存一份，书签列表要显示它，不必回头解析文件）
 * @param scrollRatio  章内滚动位置，0~1
 * @param note         备注，可以为空
 * @param createdAt    创建时间（epoch 毫秒）
 */
public record Bookmark(
        long id,
        String bookId,
        int chapterIndex,
        String chapterTitle,
        double scrollRatio,
        String note,
        long createdAt
) {

    public Bookmark {
        if (note != null && note.isBlank()) {
            note = null;
        }
        scrollRatio = Math.max(0, Math.min(1, scrollRatio));
        chapterIndex = Math.max(0, chapterIndex);
    }

    /** 新建一条还没入库的书签（id 由数据库分配）。 */
    public static Bookmark newOne(String bookId, int chapterIndex, String chapterTitle,
                                  double scrollRatio, String note) {
        return new Bookmark(0, bookId, chapterIndex, chapterTitle, scrollRatio, note,
                System.currentTimeMillis());
    }

    /** 界面上显示的一句话摘要。 */
    public String summary() {
        String position = Math.round(scrollRatio * 100) + "%";
        String title = (chapterTitle == null || chapterTitle.isBlank()) ? "第 " + (chapterIndex + 1) + " 章"
                : chapterTitle;
        return title + "  ·  章内 " + position;
    }
}
