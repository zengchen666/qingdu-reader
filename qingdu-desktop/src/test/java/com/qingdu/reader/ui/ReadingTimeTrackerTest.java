package com.qingdu.reader.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReadingTimeTracker} 的测试。
 *
 * <p>为什么这类东西必须单测：<b>它算错了不会报错</b>。
 * 少算 20 分钟的表现仅仅是书架上少了一行字，
 * 用户和开发者都不会注意到，等到发现时数据已经错了几个月。
 * 而且 {@code ReaderView} 要 JavaFX 才能实例化，逻辑混在里面就<b>根本没法测</b>。
 *
 * <p>用假时钟：每个用例都直接给出"过了多少毫秒"，
 * 不需要真的睡 —— 睡 3 分钟的测试不会有人跑，断言写错了也没人发现。
 */
class ReadingTimeTrackerTest {

    /** 假时钟：{@code now} 由测试自己推进。 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long now;

        FakeClock(long start) {
            this.now = start;
        }

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** 记下来的每一笔：(书, 毫秒)。 */
    private record Entry(String bookId, long millis) { }

    private final List<Entry> recorded = new ArrayList<>();
    private final FakeClock clock = new FakeClock(1_000_000L);

    private ReadingTimeTracker newTracker() {
        return new ReadingTimeTracker((book, millis) -> recorded.add(new Entry(book, millis)), clock);
    }

    private long totalRecorded() {
        return recorded.stream().mapToLong(Entry::millis).sum();
    }

    @Test
    @DisplayName("读了一段就记一段")
    void recordsOneSegment() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(5 * 60_000L);
        long settled = tracker.finish();

        assertEquals(5 * 60_000L, settled);
        assertEquals(1, recorded.size());
        assertEquals("b1", recorded.get(0).bookId());
        assertEquals(5 * 60_000L, recorded.get(0).millis());
    }

    @Test
    @DisplayName("换书时上一段自动结算（不结算是这类逻辑最常见的 bug）")
    void switchingBookSettlesPrevious() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(10 * 60_000L);

        tracker.start("b2");

        assertEquals(1, recorded.size());
        assertEquals("b1", recorded.get(0).bookId());
        assertEquals(10 * 60_000L, recorded.get(0).millis(),
                "换书那一刻必须把上一段结掉，否则 b1 的时长会一直少 10 分钟");
    }

    @Test
    @DisplayName("连续读三本书，每本的时间归各自的书")
    void threeBooksInARow() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(10 * 60_000L);
        tracker.start("b2");
        clock.advance(20 * 60_000L);
        tracker.start("b3");
        clock.advance(30 * 60_000L);
        tracker.finish();

        assertEquals(3, recorded.size());
        assertEquals(List.of("b1", "b2", "b3"),
                recorded.stream().map(Entry::bookId).toList());
        assertEquals(60 * 60_000L, totalRecorded(), "三段加起来应该等于总阅读时间");
    }

    @Test
    @DisplayName("太短的误触不记（否则书架上全是'已读不足 1 分钟'）")
    void tooShortIsNotRecorded() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(2_000L);

        assertEquals(0, tracker.finish());
        assertTrue(recorded.isEmpty(), "2 秒不是阅读，是误触");
    }

    @Test
    @DisplayName("刚好卡在阈值上：30 秒要记")
    void thresholdIsInclusive() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(ReadingTimeTracker.MIN_COUNTABLE_MILLIS);

        assertEquals(ReadingTimeTracker.MIN_COUNTABLE_MILLIS, tracker.finish());
    }

    @Test
    @DisplayName("比阈值少 1 毫秒就不记")
    void justBelowThresholdIsNotRecorded() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(ReadingTimeTracker.MIN_COUNTABLE_MILLIS - 1);

        assertEquals(0, tracker.finish());
    }

    @Test
    @DisplayName("睡了一夜最多算一天，不显示'已读 47 小时'")
    void capsAtOneDay() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(3 * ReadingTimeTracker.MAX_COUNTABLE_MILLIS);

        assertEquals(ReadingTimeTracker.MAX_COUNTABLE_MILLIS, tracker.finish(),
                "离谱的数字会让整个统计功能失去可信度");
    }

    @Test
    @DisplayName("系统时间被往回拨时记 0，不写负数时长")
    void backwardsClockRecordsNothing() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(-10 * 60_000L);

        assertEquals(0, tracker.finish());
        assertTrue(recorded.isEmpty(), "负的阅读时长是脏数据");
    }

    @Test
    @DisplayName("重复 finish 是安全的空操作（关窗/切书/退出都会调它）")
    void finishIsIdempotent() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(10 * 60_000L);

        assertEquals(10 * 60_000L, tracker.finish());
        assertEquals(0, tracker.finish(), "第二遍没有正在计时的书了");
        assertEquals(0, tracker.finish());
        assertEquals(1, recorded.size(), "不能重复记账");
    }

    @Test
    @DisplayName("没有在计时时 start(null) 不留脏状态")
    void startWithNullClearsState() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("b1");
        clock.advance(5 * 60_000L);

        tracker.start(null);

        assertFalse(tracker.running());
        assertNull(tracker.currentBookId());
        assertEquals(1, recorded.size(), "b1 那段已经结掉了");
    }

    @Test
    @DisplayName("空闲的空白串等同于不读")
    void blankBookIdIsNotReading() {
        ReadingTimeTracker tracker = newTracker();
        tracker.start("   ");

        assertFalse(tracker.running());
        assertEquals(0, tracker.finish());
    }

    @Test
    @DisplayName("长会话跨多段累加，不会被上限吃掉")
    void multipleSessionsAccumulate() {
        ReadingTimeTracker tracker = newTracker();
        // 模拟一周，每天读 2 小时
        for (int day = 0; day < 7; day++) {
            tracker.start("b1");
            clock.advance(2 * 60 * 60_000L);
            tracker.finish();
        }

        assertEquals(7, recorded.size());
        assertEquals(14 * 60 * 60_000L, totalRecorded(),
                "上限是'单段'的上限，不该把跨天的累计也砍掉");
    }

    @Test
    @DisplayName("构造参数不能为空")
    void rejectsNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReadingTimeTracker(null, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadingTimeTracker((b, m) -> { }, null));
    }
}
