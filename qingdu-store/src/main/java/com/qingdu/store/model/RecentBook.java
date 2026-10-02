package com.qingdu.store.model;

import com.qingdu.common.domain.Book;

/**
 * 一本书 + 它上次读到哪 + 它被归到了哪 + 累计读了多久。
 *
 * <p>两个地方在用：菜单里的「最近打开」（{@code BookStore.recent}）和
 * 书架（{@code BookStore.list}）—— 它们要的是同一份数据，
 * 差别只在"取前 N 条"还是"全都要"。
 *
 * <p>刻意复用 {@link Book} 而不是重新定义一堆字段：
 * 数据库里 {@code book} 表的前半部分（id/path/title/author/format/chapter_count/added_at）
 * 本来就是一本书的元信息，后半部分才是阅读位置。
 * 这种"一张表存了两种东西"的设计叫<b>宽表</b>，代价是偶尔要拆开用，
 * 好处是这类查询<b>零成本</b> —— 不用 JOIN 就能一次拿到
 * "书名 + 读到哪 + 归了哪组 + 读了多久"，而这正是启动时最常跑的查询。
 *
 * <p>🔴 <b>为什么 {@code groupName} 和 {@code readingMillis} 不放进 {@link Book}？</b>
 * {@code Book} 是<b>领域模型</b>，描述"这本书是什么"——书名、作者、格式、章数，
 * 全部能从文件本身推出来。而"用户把它归到哪一组""用户读了它多久"
 * 是<b>用户行为</b>，只存在于书库里，文件里查不到。
 * 把用户行为塞进领域模型，解析引擎就得为它准备默认值，
 * 而解析引擎根本不关心用户怎么整理书架 —— 职责会糊在一起。
 * 所以这两个字段只存在于这一层。
 *
 * @param book           图书元信息
 * @param progress       上次的阅读位置
 * @param groupName      所属分组名；{@code null} 表示"未分组"
 * @param readingMillis  累计阅读时长（毫秒）；0 表示还没读过
 */
public record RecentBook(Book book, ReadingProgress progress, String groupName, long readingMillis) {

    public RecentBook {
        // 分组名可能是空串（用户清了输入框），一律归一成 null：
        // 让"未分组"只有一种表示，否则筛选时 "" 和 null 要分别处理，
        // 迟早会在某个 if 上漏掉一种。
        if (groupName != null && groupName.isBlank()) {
            groupName = null;
        }
        groupName = groupName == null ? null : groupName.strip();
        readingMillis = Math.max(0L, readingMillis);
    }

    /** 兼容只需要"书 + 进度"的旧调用点（分组与时长按未分组 / 未读过处理）。 */
    public RecentBook(Book book, ReadingProgress progress) {
        this(book, progress, null, 0L);
    }

    /** 是否归了组。界面上用它决定要不要显示分组标签。 */
    public boolean grouped() {
        return groupName != null;
    }

    /** 界面上显示"最近阅读"时用的一行文字。 */
    public String describe() {
        String author = book.authorName().orElse("作者未知");
        if (!progress.started()) {
            return author + "    ·    尚未开始阅读";
        }
        return author + "    ·    " + progress.chapterTitle()
                + "    ·    章内 " + progress.percentLabel();
    }

    /**
     * 把毫秒格式化成"3 小时 12 分"这种一眼能读的说法。
     *
     * <p>{@link String#formatted} 之外不用 {@code java.time}：
     * 时长的最大量级是"读了一本书几十小时"，{@code Duration} 那套
     * 带日期的格式在小时数超过 24 时会变成"PT73H"之类，反而更难读。
     */
    public String readingTimeLabel() {
        if (readingMillis <= 0L) {
            return "";
        }
        long totalMinutes = readingMillis / 60_000L;
        if (totalMinutes < 1L) {
            return "不足 1 分钟";
        }
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours <= 0L) {
            return minutes + " 分钟";
        }
        return minutes == 0L ? (hours + " 小时") : (hours + " 小时 " + minutes + " 分");
    }
}
