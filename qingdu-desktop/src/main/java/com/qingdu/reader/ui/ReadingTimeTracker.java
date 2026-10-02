package com.qingdu.reader.ui;

import com.qingdu.store.QingduStore;
import com.qingdu.store.StoreException;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * 阅读时长统计 —— 记"这本书我一共读了多久"。
 *
 * <p><b>为什么要有这么一类？</b>
 * 时长统计看着简单，但它的本质是<b>一段进程的起止</b>：
 * 打开书开始、切书/关窗结束。把这件事塞进 {@code ReaderView} 会有三个问题：
 * <ol>
 *   <li>开始/结束的时机散落在好几个方法里（open / showBookshelf / flushProgress），
 *       漏一处就多算或少算一段时间；</li>
 *   <li>它<b>算错了不报错</b> —— 少算 10 分钟不会抛异常，
 *       所以必须能被单测钉住，而 {@code ReaderView} 依赖 JavaFX 根本没法单测；</li>
 *   <li>换书、关窗、异常退出三条路径都要"把当前这段结掉"，
 *       而它们各自散落在不同地方。</li>
 * </ol>
 * 抽出来之后，"当前正在读哪本、从什么时候开始"就变成两个字段，
 * 而"怎么算"可以用一个注入的时钟来测。
 *
 * <h2>时钟可注入</h2>
 * {@link #start(String, long)} / {@link #finish(long)} 让调用方决定"现在几点"，
 * 于是测试可以随便给出 0、10 分钟、3 小时，而不必真的睡 3 分钟。
 * 这是这类"时间相关逻辑"唯一能测的办法 ——
 * 睡 3 分钟的测试不会有人跑，断言写错了也没人发现。
 */
public final class ReadingTimeTracker {

    /**
     * 一次结算的最小计时段（毫秒）。
     *
     * <p>低于这个值就不记。理由：翻页连点、误触关窗都会产生几百毫秒的"阅读时间"，
     * 而它们会在书架上变成"已读 不足 1 分钟"这种毫无意义的噪声。
     * 30 秒是个能同时挡住误触、又不至于漏掉真实阅读的阈值。
     */
    static final long MIN_COUNTABLE_MILLIS = 30_000L;

    /**
     * 一次结算的上限（毫秒）：24 小时。
     *
     * <p>机器睡了一夜、笔记本合盖了、用户忘了关程序 ——
     * 这些都会让"开始到结束"的间隔变成一整天。
     * 不封顶的话，书架上会出现"已读 47 小时"这种一眼假的数字，
     * 而且它<b>不可信之后整个统计功能就废了</b>。
     * 封顶的含义是"最多算一天"，宁可少算也不显示离谱值。
     */
    static final long MAX_COUNTABLE_MILLIS = 24L * 60 * 60 * 1000L;

    /** 写库回调：(哪本书, 记多少毫秒)。 */
    private final BiConsumer<String, Long> recorder;

    private final LongSupplier clock;

    /** 正在计时的书；{@code null} 表示当前没有在读的书。 */
    private String currentBookId;

    /** 这一段从什么时候开始（{@link #clock} 的值域）。 */
    private long segmentStart;

    public ReadingTimeTracker(BiConsumer<String, Long> recorder) {
        this(recorder, System::currentTimeMillis);
    }

    /**
     * @param recorder 写库回调，参数是 (bookId, 毫秒数)
     * @param clock    "现在几点"，测试可注入假时钟
     */
    public ReadingTimeTracker(BiConsumer<String, Long> recorder, LongSupplier clock) {
        if (recorder == null || clock == null) {
            throw new IllegalArgumentException("recorder 和 clock 不能为空");
        }
        this.recorder = recorder;
        this.clock = clock;
    }

    /**
     * 开始给一本书计时。
     *
     * <p>🔴 <b>先结算上一本</b>（如果还在计）——
     * 换书时忘记结掉上一段是这类逻辑最常见的 bug，
     * 而且它<b>不会报错</b>：漏结算的表现只是"这本书的时长少了几十分钟"，
     * 几个月后没人会发现。所以"先结旧的再开新的"必须写死在这里，
     * 而不是指望每个调用点记得调 {@link #finish}。
     *
     * @return 上一段被记下的毫秒数（没有上一段则 0）
     */
    public long start(String bookId) {
        long settled = finish(clock.getAsLong());
        if (bookId == null || bookId.isBlank()) {
            this.currentBookId = null;
            this.segmentStart = 0;
            return settled;
        }
        this.currentBookId = bookId;
        this.segmentStart = clock.getAsLong();
        return settled;
    }

    /**
     * 结算当前这一段，之后进入"不在读"状态。
     *
     * <p>可以重复调：没有正在计时的书时它就是返回 0 的空操作 ——
     * 关窗、切书、退出三条路径都会调它，让它们能各调各的才是对的。
     *
     * @return 记下的毫秒数（不该记时为 0）
     */
    public long finish() {
        return finish(clock.getAsLong());
    }

    /** 按给定时刻结算。包可见是为了单测能直接控制时间。 */
    long finish(long now) {
        String bookId = currentBookId;
        currentBookId = null;
        if (bookId == null) {
            return 0;
        }
        long elapsed = now - segmentStart;
        // 负数说明时钟被往回拨了（用户改系统时间、NTP 校时）。
        // 这种情况宁可记 0，也不要把"负的阅读时长"写进库。
        if (elapsed < MIN_COUNTABLE_MILLIS) {
            return 0;
        }
        long countable = Math.min(elapsed, MAX_COUNTABLE_MILLIS);
        recorder.accept(bookId, countable);
        return countable;
    }

    /** 当前是否正在计时。 */
    public boolean running() {
        return currentBookId != null;
    }

    /** 正在计时的书；没在计时返回 {@code null}。 */
    public String currentBookId() {
        return currentBookId;
    }

    /**
     * 造一个直接写库的 tracker。
     *
     * <p>写库失败只记日志、绝不打断阅读：统计是锦上添花，
     * 而"因为统计失败所以读不了书"是本末倒置。
     *
     * <p>store 为 null（数据库没打开）时返回一个什么都不记的 tracker ——
     * 不能因为"没地方记时长"就让整个程序崩在这。
     */
    public static ReadingTimeTracker backedBy(QingduStore store) {
        if (store == null) {
            return new ReadingTimeTracker((bookId, millis) -> { });
        }
        return new ReadingTimeTracker((bookId, millis) -> {
            try {
                store.books().addReadingTime(bookId, millis);
            } catch (StoreException e) {
                System.err.println("[轻读] 记录阅读时长失败（不影响阅读）：" + e.getMessage());
            }
        });
    }
}
