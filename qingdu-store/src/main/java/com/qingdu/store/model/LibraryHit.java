package com.qingdu.store.model;

import java.util.List;

/**
 * 跨书检索的一条命中 —— 比单书的 {@link SearchHit} 多了"哪本书"。
 *
 * @param bookId       图书 ID，点击结果时用它决定跳到哪本书
 * @param bookTitle    书名，冗余存一份：结果列表要显示它，
 *                     而让界面每行都回查一次数据库是 N+1 查询
 * @param chapterIndex 章节序号（0 起）
 * @param hitCount     本章命中次数
 * @param snippet      命中处上下文摘要
 */
public record LibraryHit(String bookId, String bookTitle, int chapterIndex, int hitCount,
                         String snippet) {

    public LibraryHit {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        if (chapterIndex < 0) {
            throw new IllegalArgumentException("章节序号不能为负数: " + chapterIndex);
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("命中次数不能为负数: " + hitCount);
        }
        bookTitle = (bookTitle == null || bookTitle.isBlank()) ? "（未知书名）" : bookTitle;
        snippet = snippet == null ? "" : snippet;
    }

    /** 界面上分组显示时用的键：同一本书的命中会挨在一起。 */
    public String bookKey() {
        return bookId;
    }
}
