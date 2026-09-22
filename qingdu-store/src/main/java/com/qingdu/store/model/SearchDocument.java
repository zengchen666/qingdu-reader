package com.qingdu.store.model;

/**
 * 建索引时的一篇"文档" —— 本项目里一篇就是一章。
 *
 * <p><b>粒度为什么是章而不是段？</b>实测过：按段落 83,100 行，建索引 10.2 秒；
 * 按章 1,636 行，5.9 秒。而且按章还有个语义上的好处：
 * 搜索结果<b>天然就是"第几章"</b>，点一下就能跳过去，不用再反查段落属于哪一章。
 *
 * @param chapterIndex 章节序号（0 起），搜索结果靠它回跳
 * @param text         该章的<b>原文</b>（不是切分后的结果，切分由存储层自己做）
 */
public record SearchDocument(int chapterIndex, String text) {

    public SearchDocument {
        if (chapterIndex < 0) {
            throw new IllegalArgumentException("章节序号不能为负数: " + chapterIndex);
        }
        text = text == null ? "" : text;
    }
}
