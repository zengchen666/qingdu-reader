package com.qingdu.store;

/**
 * 查询时"按章号取原文"的回调 —— 存储层拿原文做精确校验的入口。
 *
 * <p><b>为什么非要这个回调，而不是把原文存进索引表？</b>
 * 两个理由，都是实测出来的：
 * <ol>
 *   <li><b>体积</b>：把章节原文一起写进 FTS 表（哪怕标了 {@code UNINDEXED}），
 *       一本 10 MB 的书索引会膨胀到 69 MB；不存原文是 33 MB。差了一倍多。</li>
 *   <li><b>没必要</b>：原文本来就能靠字节偏移从文件里读回来，整本 1636 章
 *       一次性读完只要 83 毫秒 —— 反正展示搜索结果也要读原文生成摘要，几乎白送。</li>
 * </ol>
 *
 * <p><b>为什么是回调而不是让 store 自己去读文件？</b>
 * 存储层不依赖解析层（见 {@code qingdu-store/pom.xml} 的说明），
 * 它不知道文件在哪、什么编码。回调把"怎么读"交还给调用方，
 * 存储层只负责"拿到文本后怎么判断"。
 *
 * <p><b>调用方的性能责任</b>：一次查询可能会回调几百次（搜"萧炎"有 1622 个候选章）。
 * 所以实现方必须<b>先把整本书读进内存再按章切片</b>（{@code ChapterTextBatch} 就是干这个的），
 * 不要在这里现开文件 —— 那样一次查询会退化成 20 多秒。
 */
@FunctionalInterface
public interface ChapterTextSource {

    /**
     * 取指定章节的原文。
     *
     * @param chapterIndex 章节序号（0 起）
     * @return 该章文本；读不到时返回 {@code null} 或空串（该章会被当成不命中）
     */
    String textOf(int chapterIndex);
}
