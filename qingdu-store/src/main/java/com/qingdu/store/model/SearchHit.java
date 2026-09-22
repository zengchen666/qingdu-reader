package com.qingdu.store.model;

/**
 * 一条搜索命中。
 *
 * <p><b>只给"第几章 + 摘要"，不给正文。</b>
 * 一章 6 KB 左右，一个常见词能命中上千章，全部塞进结果列表就是几十 MB 对象；
 * 而界面上真正要显示的只是命中词周围那一小段。
 *
 * @param chapterIndex 章节序号（0 起），点击结果时靠它跳转
 * @param hitCount     本章命中次数（同一章里出现几次），用于结果排序和"共 N 处"提示
 * @param snippet      命中处上下文摘要，已经把换行压成空格，可直接显示
 */
public record SearchHit(int chapterIndex, int hitCount, String snippet) {

    public SearchHit {
        if (chapterIndex < 0) {
            throw new IllegalArgumentException("章节序号不能为负数: " + chapterIndex);
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("命中次数不能为负数: " + hitCount);
        }
        snippet = snippet == null ? "" : snippet;
    }
}
