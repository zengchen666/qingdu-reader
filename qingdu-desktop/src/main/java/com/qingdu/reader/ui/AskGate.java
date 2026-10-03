package com.qingdu.reader.ui;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次问答的"忙"闸门 —— 单独抽成一个类，是为了**能被单元测试钉住**。
 *
 * <h2>🔴 为什么它必须独立存在（2026-10-04 修了一个真实 bug）</h2>
 *
 * 原先这个状态是 {@code AiPanel} 里的一个 {@code AtomicBoolean busy} 字段，
 * 而负责更新界面的方法写成这样：
 *
 * <pre>
 * private final AtomicBoolean busy = new AtomicBoolean(false);
 *
 * private void setBusy(boolean busy) {   // ← 参数把字段**遮蔽**了
 *     progress.setVisible(busy);         // 这里用的全是参数
 *     askButton.setDisable(busy || !serviceReady);
 * }
 * </pre>
 *
 * 于是 {@code setBusy(false)} 只动了界面，**从来没复位过那个 AtomicBoolean**
 * （全文件搜不到 {@code busy.set(}）。结果是：
 *
 * <ol>
 *   <li>第一次提问：{@code compareAndSet(false, true)} 成功 → 置为 true；</li>
 *   <li>回答完成后 {@code setBusy(false)} → 按钮看着恢复了；</li>
 *   <li>第二次点击：标志还是 true，{@code compareAndSet} 失败 → <b>直接 return，
 *       什么都没发生</b>。</li>
 * </ol>
 *
 * 现象是「只能提问一次，第二次点了没反应」，而且按钮看着是可点的 ——
 * 一个纯粹的死点击，用户完全无从判断哪儿坏了。
 *
 * <p>把它抽出来有两个好处：① 状态与界面的联动集中在一处，
 * 不会再出现"改了界面却忘了改状态"；② 不依赖 JavaFX，
 * 因此"问完还能再问"这条最基本的规则**可以被单测覆盖**。
 *
 * <p>调用纪律：{@link #begin()} 与 {@link #end()} 必须成对出现。
 * 漏掉 {@code end()} 的后果是功能永久不可用 —— 比报错还糟，
 * 所以面板里所有退出路径（成功 / 失败 / 召不回 / 空问题 / 没打开书）
 * 都要走同一个收尾方法。
 */
public final class AskGate {

    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * 尝试开始一次问答。
     *
     * @return 抢到了返回 {@code true}；已经在忙则返回 {@code false}，
     *         调用方应当<b>直接放弃</b>（不要排队、不要重试 ——
     *         用户在按钮上连点是常态，排队只会让界面被一堆请求淹没）。
     */
    public boolean begin() {
        return busy.compareAndSet(false, true);
    }

    /**
     * 结束一次问答 —— 成功、失败、召不回都要调。
     *
     * <p>幂等：重复调用无害。因为面板里可能有不止一条路径想要收尾
     * （比如"召不回"分支既结束了本次问答、又要显示提示），
     * 要求"恰好调一次"是脆弱的约束。
     */
    public void end() {
        busy.set(false);
    }

    /** 当前是否正在问答中。 */
    public boolean isBusy() {
        return busy.get();
    }
}
