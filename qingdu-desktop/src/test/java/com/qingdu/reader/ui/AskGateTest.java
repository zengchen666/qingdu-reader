package com.qingdu.reader.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskGate} 测试 —— 钉住「提问一次之后还能不能再提问」。
 *
 * <p>这个类存在的唯一理由就是 2026-10-04 那个真机 bug：
 * 用户反馈「只能提问一次，第二次点提问就没有响应」。
 * 根因是状态与界面没联动 —— 界面恢复了，闸门却没释放。
 *
 * <p>把闸门抽成不依赖 JavaFX 的纯类，就是为了<b>让这条最基本的规则能被单测覆盖</b>。
 * 没有这个测试，同样的错误随时会以别的形式回来。
 */
class AskGateTest {

    @Test
    @DisplayName("第一次提问可以开始")
    void 第一次可以开始() {
        AskGate gate = new AskGate();
        assertTrue(gate.begin());
    }

    @Test
    @DisplayName("🔴 结束之后必须还能开第二次 —— 钉住「只能问一次」")
    void 结束后必须能开始第二次() {
        // 这条就是那个 bug 本身。
        // 现象：第一次提问成功返回，按钮看着是可点的，
        // 再点却什么也不发生 —— 因为闸门从未释放，
        // begin() 永远返回 false，调用方直接 return。
        AskGate gate = new AskGate();

        assertTrue(gate.begin(), "第一次应该成功");
        gate.end();
        assertTrue(gate.begin(), "第一次结束后，第二次必须仍然能开始");
    }

    @Test
    @DisplayName("连续问答十轮都要能进行")
    void 多轮问答都能进行() {
        // 用户的真实用法就是连着问好几个问题，
        // 不能"第二轮之后就废了"。
        AskGate gate = new AskGate();
        for (int i = 1; i <= 10; i++) {
            assertTrue(gate.begin(), "第 " + i + " 轮应该能开始");
            gate.end();
        }
    }

    @Test
    @DisplayName("忙的时候不能重复开始 —— 连点要被挡住")
    void 忙时不能重复开始() {
        AskGate gate = new AskGate();
        assertTrue(gate.begin());
        assertFalse(gate.begin(), "问答进行中不该再放行一次");
        assertFalse(gate.begin(), "第三次也不该放行");
    }

    @Test
    @DisplayName("end 是幂等的 —— 多条收尾路径重复调用无害")
    void 结束可重复调用() {
        // 面板里"召不回"分支既结束了本次问答、又显示了提示，
        // 未来还可能再加一条路径。要求"恰好调一次"是脆弱的约束。
        AskGate gate = new AskGate();
        gate.begin();
        gate.end();
        gate.end();
        gate.end();
        assertTrue(gate.begin(), "重复 end 之后仍要能开始下一轮");
    }

    @Test
    @DisplayName("没开始就结束也不会把状态搞坏")
    void 未开始就结束是安全的() {
        AskGate gate = new AskGate();
        gate.end();
        assertFalse(gate.isBusy());
        assertTrue(gate.begin());
    }

    @Test
    @DisplayName("isBusy 如实反映状态")
    void 如实反映忙闲() {
        AskGate gate = new AskGate();
        assertFalse(gate.isBusy());
        gate.begin();
        assertTrue(gate.isBusy());
        gate.end();
        assertFalse(gate.isBusy());
    }

    @Test
    @DisplayName("并发连点只有一个能抢到")
    void 并发下只有一个成功() throws Exception {
        // 用户在按钮上连点是常态；放行多个会让答案乱序覆盖。
        AskGate gate = new AskGate();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger won = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (gate.begin()) {
                    won.incrementAndGet();
                }
            });
        }
        try {
            for (Runnable t : tasks) {
                pool.submit(t);
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "线程应当都就绪");
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "任务应当都跑完");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(won.get() == 1, "并发下应当恰好一个抢到，实际 " + won.get());
    }
}
