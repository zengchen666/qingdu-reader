package com.qingdu.store.model;

import com.qingdu.common.domain.Book;

/**
 * "最近打开"列表里的一项 = 一本书 + 它上次读到哪。
 *
 * <p>刻意复用 {@link Book} 而不是重新定义一堆字段：
 * 数据库里 {@code book} 表的前半部分（id/path/title/author/format/chapter_count/added_at）
 * 本来就是一本书的元信息，后半部分才是阅读位置。
 * 这种"一张表存了两种东西"的设计叫<b>宽表</b>，代价是偶尔要拆开用，
 * 好处是「最近打开」这种查询<b>零成本</b> —— 不用 JOIN 就能一次拿到
 * "书名 + 读到哪"，而这正是启动时最常跑的查询。
 *
 * @param book     图书元信息
 * @param progress 上次的阅读位置
 */
public record RecentBook(Book book, ReadingProgress progress) {

    /** 界面上显示"最近阅读"时用的一行文字。 */
    public String describe() {
        String author = book.authorName().orElse("作者未知");
        if (!progress.started()) {
            return author + "    ·    尚未开始阅读";
        }
        return author + "    ·    " + progress.chapterTitle()
                + "    ·    章内 " + progress.percentLabel();
    }
}
