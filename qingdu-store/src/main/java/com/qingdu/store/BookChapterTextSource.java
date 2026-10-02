package com.qingdu.store;

import java.util.List;

/**
 * 跨书检索时"给我这一本书某一章的原文"—— {@link ChapterTextSource} 的跨书版本。
 *
 * <p><b>为什么不直接用 {@code Map<String, ChapterTextSource>}？</b>
 * 因为那样调用方必须<b>先把所有书都读进内存</b>才能开始查 ——
 * 几十本书就是几百 MB，一次搜索就能把堆撑爆。
 * 而跨书检索的候选章分布在几十本书里，绝大多数<b>根本不会被校验到</b>
 * （条数上限一集满就停）。所以这个接口是<b>按需回调</b>的：
 * 存储层查到哪本书的第几章，才向实现方要那一章。
 *
 * <p><b>实现方的性能责任（与 {@link ChapterTextSource} 相反）：</b>
 * 同一个 {@code (bookId, chapterIndex)} 可能被回调很多次
 * （一个常见词在 50 本书里能命中上万章），而且相邻候选可能落在同一本书里。
 * 所以实现方至少要做到：
 * <ol>
 *   <li><b>按 {@code bookId} 缓存</b>：连续几章大概率在同一本书里，
 *       命中缓存能省掉反复打开文件、反复解码；</li>
 *   <li><b>随机读而不是整本读</b>：见 {@link ChapterTextRandomAccess}。</li>
 * </ol>
 * 但<b>不能</b>像单书检索那样"整本读进内存"——
 * 那是 {@code ChapterTextBatch} 的做法，在跨书场景下会随书库规模线性增长。
 *
 * <p>存储层不解析文件、不知道编码，所以只给"要哪一章"，
 * 怎么定位那一章是实现方的事。
 */
@FunctionalInterface
public interface BookChapterTextSource {

    /**
     * 取某本书里某一章的原文。
     *
     * @param bookId       图书 ID
     * @param chapterIndex 章节序号（0 起）
     * @return 该章文本；该书打不开 / 该章不存在时返回 {@code null} 或空串
     *         （会被当成"不命中"，而不是报错 —— 一本书的源文件被移走
     *         不该让整个跨书查询失败）
     */
    String textOf(String bookId, int chapterIndex);

    /**
     * 释放缓存。
     *
     * <p>默认什么也不做：只读一次不缓存的实现方不需要任何清理动作。
     * 带缓存的实现方<b>必须</b>覆盖它，否则几十本书的字节会一直留在堆里，
     * 直到下一次 GC —— 而"搜一次就把内存吃满"正是这个接口要避免的事。
     */
    default void close() {
        // 默认无操作
    }

    /**
     * 把多个"单书来源"拼成一个跨书来源，只用于测试和"确定只有一本书"的场景。
     *
     * <p><b>真实代码不要用它做跨书检索</b>：传进来的每个来源通常都持有整本书的字节，
     * 而这个组合不会去"用到才加载"，等于把内存问题原样保留下来。
     */
    static BookChapterTextSource ofFixed(java.util.Map<String, ? extends ChapterTextSource> byBook) {
        return (bookId, chapterIndex) -> {
            ChapterTextSource source = byBook.get(bookId);
            return source == null ? null : source.textOf(chapterIndex);
        };
    }

    /**
     * 从一份"书 ID → 该书全部章节文本"的快照构造。
     *
     * <p>用途是让测试和 {@code SearchProbe} 这类离线工具能立刻跑起来，
     * 不必真的去读文件。生产路径请用按需随机读的实现。
     */
    static BookChapterTextSource ofSnapshot(java.util.Map<String, List<String>> chaptersByBook) {
        return (bookId, chapterIndex) -> {
            List<String> chapters = chaptersByBook.get(bookId);
            if (chapters == null || chapterIndex < 0 || chapterIndex >= chapters.size()) {
                return null;
            }
            return chapters.get(chapterIndex);
        };
    }
}
