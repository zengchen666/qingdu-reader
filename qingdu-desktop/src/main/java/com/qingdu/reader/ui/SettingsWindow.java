package com.qingdu.reader.ui;

import com.qingdu.common.settings.ReaderSettings;
import com.qingdu.common.settings.Theme;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 「阅读设置」窗口 —— 独立的<b>非模态</b>窗口。
 *
 * <p><b>为什么不做成模态对话框？</b><br>
 * 因为这是一个"要看着正文调"的面板。模态窗口会把主窗口锁死：
 * 调字号的时候看不到正文、只能看那块小预览，调完关掉再看效果 ——
 * 不对就再开一次，来回好几轮。非模态窗口里用户可以一边拖滑块、
 * 一边看右边主窗口的正文跟着重排，一轮就调好了。
 *
 * <p><b>三个必须自己处理的细节</b>（{@code Dialog} 原本帮忙做了，现在自己做）：
 * <ol>
 *   <li><b>换肤。</b>{@code Stage} 有自己的 {@code Scene}，
 *       不继承主窗口的样式表。切主题时要单独把新样式表装上去
 *       （见 {@link #applyTheme}）。</li>
 *   <li><b>跟着主窗口退出。</b>非模态窗口也是窗口，
 *       它开着的时候关掉主窗口，程序<b>不会退出</b>（JavaFX 要等最后一个窗口关闭）。
 *       所以主窗口的关闭回调里会调 {@link ReaderView#closeAuxiliaryWindows()}。</li>
 *   <li><b>位置别跳到屏幕中间。</b>它是辅助窗口，首次出场应该贴着主窗口，
 *       而不是飞到屏幕正中 —— 那样用户会以为主窗口不见了。</li>
 * </ol>
 *
 * <p><b>为什么"关了"还能再打开？</b>因为关闭走的是 {@code hide()}
 * 而不是销毁：同一个 {@code Stage} 实例在整个会话里复用。
 * 位置和大小因此天然被记住（窗口管理器自己记着），
 * 也避免了每次开设置都重新枚举一遍系统字体列表
 * （{@code Font.getFamilies()} 在 Windows 上要解析几百个字体项，不便宜）。
 */
public final class SettingsWindow extends Stage {

    /** 首次出场时相对主窗口的偏移。 */
    private static final double OFFSET_X = 96;
    private static final double OFFSET_Y = 72;

    private final SettingsPane pane;

    /** 还没露过面。只有第一次 show 时才需要摆位置，之后位置归用户。 */
    private boolean neverShown = true;

    public SettingsWindow(Window owner, ReaderSettings initial, Consumer<ReaderSettings> onChange) {
        setTitle("阅读设置");
        // 非模态：不锁主窗口。这也是它相比 Dialog 的<b>唯一</b>根本区别，
        // 其余外观（标题栏、缩放、Esc）都靠 Stage 自己
        initModality(Modality.NONE);
        setResizable(true);
        setMinWidth(430);
        setMinHeight(480);

        if (owner != null) {
            initOwner(owner);
        }

        pane = new SettingsPane(initial, onChange, this::hide);
        Scene scene = new Scene(pane, 460, 620);
        // 构造阶段「当前设置」理论上不会是 null（ReaderView 一定有一份），
        // 但这里仍然兜一下：换个颜色不该让"设置窗口打不开"
        Theme effective = (initial == null) ? Theme.defaultTheme() : initial.theme();
        scene.getStylesheets().setAll(ThemeStyles.stylesheetsFor(effective));
        setScene(scene);
    }

    /**
     * 显示（或提到最前）。
     *
     * <p>已经开着就只是 {@code toFront()}：连点两次「阅读设置」应该是
     * "把它拿到眼前"，而不是开出第二个窗口 —— 两个窗口各自持有一份设置值，
     * 后关的那个会把先关的那个的改动覆盖掉，用户看到的是"设置自己变回去了"。
     */
    public void showFor() {
        if (isShowing()) {
            toFront();
            requestFocus();
            return;
        }
        if (neverShown) {
            placeNearOwner();
            neverShown = false;
        }
        show();
        toFront();
    }

    /** 把外部的当前设置灌进面板（防止显示的是过期值）。 */
    public void reload(ReaderSettings settings) {
        pane.applyToControls(settings);
    }

    /** 主题变了：给自己的 Scene 换一套样式表。 */
    public void applyTheme(Theme theme) {
        ThemeStyles.apply(getScene(), theme);
    }

    /**
     * 第一次出场时摆到主窗口旁边。
     *
     * <p>{@code initOwner} 只保证"始终在主窗口之上"，不管位置。
     * 不自己摆的话，JavaFX 会把新窗口放在屏幕左上角或系统默认位置，与主窗口无关。
     */
    private void placeNearOwner() {
        Window owner = getOwner();
        if (owner == null) {
            return;
        }
        setX(owner.getX() + OFFSET_X);
        setY(owner.getY() + OFFSET_Y);
    }
}
