import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.settings.ReaderSettings;
import com.qingdu.reader.ui.ChapterRenderer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 渲染探针 —— 验证「行距」和「文字颜色」在真实的 JavaFX 布局里是否生效。
 *
 * <p><b>为什么要单独写这个探针，而不是靠单元测试？</b><br>
 * v0.5 把正文从 {@code Label} 换成了 {@code TextFlow} + {@code Text}，
 * 因为 {@code Label} 根本没有行距这个属性。但换完之后有两类问题是
 * 单元测试抓不到的，而它们都不会报错：
 *
 * <ol>
 *   <li><b>颜色</b>：{@code Text} 认的是 {@code -fx-fill}，{@code Label} 认的是
 *       {@code -fx-text-fill}。CSS 里漏写 {@code -fx-fill} 的话，正文会静默
 *       退回黑色 —— 浅色主题下看不出来，切到夜间主题才变成"黑底黑字"。</li>
 *   <li><b>行距</b>：{@code setLineSpacing()} 只有在节点真的参与布局之后
 *       才会体现在高度上。不跑一遍 {@code applyCss() + layout()}，
 *       拿到的高度是不算行距的旧值。</li>
 * </ol>
 *
 * 所以这里起一个<b>不显示</b>的 Scene，跑完整套 CSS 与布局，
 * 直接量高度、取颜色，把结论写进报告文件。
 *
 * <p>⚠️ 跑完会在 stderr 打一条 {@code IllegalStateException: This operation is
 * permitted on the event thread only}（来自 {@code Screen.getVideoRefreshPeriod}）。
 * 那是这里的 {@code Platform.exit()} 在 FX 线程里关工具包时 JavaFX 自身的噪音，
 * 报告在这之前已经写完并关掉了 —— <b>看结论只看报告最后那行 PASSED / FAILED</b>。
 *
 * <p>用法（classpath 必须带 dist 运行时里的 jar，sqlite-jdbc 只在那里）：
 * <pre>
 * java -cp "out;qingdu-desktop\target\classes;dist\QingduReader\app\*" RenderProbe &lt;报告文件&gt;
 * </pre>
 */
public final class RenderProbe {

    private static PrintStream out;
    private static int failures = 0;

    /** 正文容器的宽度。中文 17px 下约 21 字一行，足够让示例段落折行。 */
    private static final double VIEW_WIDTH = 360;

    public static void main(String[] args) throws Exception {
        String report = (args.length > 0) ? args[0] : "render-probe-report.txt";
        out = new PrintStream(report, "UTF-8");

        Platform.startup(() -> {
            try {
                run();
            } catch (Throwable t) {
                out.println("EXCEPTION: " + t);
                t.printStackTrace(out);
                failures++;
            } finally {
                out.println();
                out.println(failures == 0 ? "[result] RENDER PROBE PASSED" : "[result] FAILED: " + failures);
                out.close();
                Platform.exit();
            }
        });
    }

    private static void run() {
        Chapter chapter = new Chapter("probe", 0, "第一章 星光落下", null, 0, 0,
                List.of(
                        new ChapterBlock.Heading(1, "第一章 星光落下"),
                        new ChapterBlock.Paragraph(
                                "夜色像一块浸了水的布，沉沉地压在屋顶上。这是一段足够长的中文示例文本，"
                                        + "用来保证它在下面那个固定宽度的容器里一定会折行，"
                                        + "这样行距才会体现在高度上。")));

        // ---- 1. 节点类型必须是 TextFlow（Label 不支持行距） ----
        // ⚠️ 取颜色/高度必须用"已经挂进 Scene 并跑过布局"的那份节点。
        // 直接拿 render() 的返回值去读 getFill()，拿到的是 CSS 应用前的默认值
        // （纯黑）—— 那会让这个检查永远失败，而且看起来像 CSS 没写对。
        VBox laidOut = layout(chapter, ReaderSettings.defaults());
        Node paragraph = laidOut.getChildren().get(1);
        check("段落是 TextFlow 而不是 Label", paragraph instanceof TextFlow);

        // ---- 2. 行距必须真的改变布局高度 ----
        double tight = measureHeight(chapter, 1.0);
        double loose = measureHeight(chapter, 2.5);
        out.println("  行距 1.0 高度 = " + tight);
        out.println("  行距 2.5 高度 = " + loose);
        check("行距变大时高度必须变大", loose > tight + 1.0);
        // 示例段落约 60 字、17px 字号、360px 宽 → 应该折成 3 行左右（约 90px）。
        // 如果 TextFlow 没按容器宽度折行，会退化成每字一行（实测 1555px）。
        // 这一条就是用来挡住那种"看着像生效了其实没生效"的情况
        out.println("  段落宽度 = " + paragraph.getBoundsInLocal().getWidth());
        check("段落按容器宽度折行（不是每个字一行）", tight > 40 && tight < 400);

        // ---- 3. 字号变化时行距跟着按比例走（存的是倍数而不是像素） ----
        ReaderSettings big = ReaderSettings.defaults().withFontSize(30).withLineSpacing(1.5);
        ReaderSettings small = ReaderSettings.defaults().withFontSize(15).withLineSpacing(1.5);
        out.println("  字号 30 / 行距 1.5 → 每帧 " + big.lineSpacingPixels(1.0) + " px");
        out.println("  字号 15 / 行距 1.5 → 每帧 " + small.lineSpacingPixels(1.0) + " px");
        check("行距跟随字号缩放", big.lineSpacingPixels(1.0)
                > small.lineSpacingPixels(1.0) + 0.5);

        // ---- 4. 文字颜色必须是主题色（说明 -fx-fill 生效了） ----
        // 浅色主题的 -qd-text 是 #2f2f2c。断言"等于主题色"比"不是黑"更硬 ——
        // 后者在"颜色没生效但恰好不是纯黑"时也会通过
        Color fill = colorOf((TextFlow) paragraph);
        out.println("  正文颜色 = " + fill);
        check("正文颜色来自主题（-qd-text #2f2f2c）",
                fill != null && equalsRgb(fill, Color.web("#2f2f2c")));

        // ---- 5. 高亮路径：命中词要挂上 reader-highlight ----
        VBox highlightedBox = layout(chapter, ReaderSettings.defaults(), "屋顶");
        TextFlow flow = (TextFlow) highlightedBox.getChildren().get(1);
        long hits = flow.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("reader-highlight"))
                .count();
        out.println("  高亮命中节点数 = " + hits);
        check("高亮词被单独切出来", hits == 1);
        check("高亮之外仍有普通文本", flow.getChildren().size() > 1);
    }

    /** 在固定宽度的容器里跑一遍完整布局，返回段落的实际高度。 */
    private static double measureHeight(Chapter chapter, double lineSpacing) {
        VBox box = layout(chapter, ReaderSettings.defaults().withLineSpacing(lineSpacing));
        return box.getChildren().get(1).getBoundsInLocal().getHeight();
    }

    private static VBox layout(Chapter chapter, ReaderSettings settings) {
        return layout(chapter, settings, null);
    }

    /**
     * 建一个固定宽度的容器，把渲染结果放进去跑完整的 CSS + 布局。
     *
     * <p>两步都不能省：不 {@code applyCss()} 就没有样式表的值，
     * 不 {@code layout()} 量到的高度是不含行距的旧值。
     */
    private static VBox layout(Chapter chapter, ReaderSettings settings, String highlight) {
        VBox box = new VBox();
        box.getChildren().setAll(ChapterRenderer.render(chapter, settings, highlight));

        // 🔴 Scene 必须给明确尺寸。不传宽高的 Scene 尺寸是 0，
        // 布局时正文拿到 0 宽度 —— 于是每个字独占一行，量出来的高度是
        // "一行一个字"的结果（实测 1555px，正常应该是 3 行约 90px）。
        // 那种数字看着"变大了所以行距生效了"，其实验的是错的场景，
        // 真正的风险（TextFlow 会不会按容器宽度折行）反而没被覆盖到
        Scene scene = new Scene(box, VIEW_WIDTH, 600);
        // 两张表都要挂：.reader-paragraph 规则在 base.css 里，
        // 而它引用的 -qd-text 变量在主题表里。少挂任何一张，
        // 颜色都会落回 JavaFX 默认 —— 这正是要验的东西
        for (String sheet : List.of("base.css", "theme-light.css")) {
            String url = cssOf(sheet);
            if (url == null) {
                out.println("  [FAIL] 找不到样式表 " + sheet);
                failures++;
            } else {
                scene.getStylesheets().add(url);
            }
        }
        box.applyCss();
        box.layout();
        return box;
    }

    private static String cssOf(String name) {
        java.net.URL url = RenderProbe.class.getResource("/css/" + name);
        return (url == null) ? null : url.toExternalForm();
    }

    private static Color colorOf(TextFlow flow) {
        for (Node child : flow.getChildren()) {
            if (child instanceof Text text) {
                return (Color) text.getFill();
            }
        }
        return null;
    }

    private static boolean equalsRgb(Color a, Color b) {
        return a.getRed() == b.getRed() && a.getGreen() == b.getGreen() && a.getBlue() == b.getBlue();
    }

    private static void check(String name, boolean ok) {
        out.println((ok ? "  [ok]   " : "  [FAIL] ") + name);
        if (!ok) {
            failures++;
        }
    }
}
