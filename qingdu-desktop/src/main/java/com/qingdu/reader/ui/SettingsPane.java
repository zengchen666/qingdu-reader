package com.qingdu.reader.ui;

import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.settings.ReaderSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 「阅读设置」的内容面板：字体、字号、段间距、行距、预览。
 *
 * <p><b>它是"改一项、立刻生效"的。</b>没有确定 / 取消按钮 ——
 * 这个面板被放进一个<b>非模态</b>窗口（{@link SettingsWindow}），
 * 用户可以一边调字号、一边看主窗口里的正文跟着变。
 * 模态对话框那种"调完点确定才生效"的模式，在这里是多余的：
 * 非模态窗口本来就不会挡住正文，即时反馈才是它的价值所在。
 *
 * <p>代价是"取消"没法实现了。用户想回到改动前的样子，只能靠
 * 「恢复默认」或自己再调回来。这个取舍是清楚的：调字号这类操作是
 * <b>可逆的试错</b>（拖回去就行），不是"提交一份表单"。
 *
 * <p><b>预览区为什么直接调用 {@link ChapterRenderer}？</b><br>
 * 这是整个面板设计上最要紧的一点。如果预览用另一套代码画（哪怕只是
 * "差不多"地设一下字号），那它早晚会和真正的正文渲染<b>跑偏</b>：
 * 有人改了正文的排版规则、忘了改预览，用户就会看到"预览和实际不一样"。
 * 这里让预览走<b>和正文完全相同的那条渲染路径</b>，只是喂给它一段示例文字。
 * 于是"预览是否准确"就不再需要人去维护，它<em>结构上</em>不可能不准。
 *
 * <p><b>通知的时机</b>：面板内部灌值（{@link #applyToControls}）时会
 * 临时闭麦，不往外抛改动通知 —— 否则每打开一次设置窗口，主界面就会
 * 白白重排一次正文，滚动位置还会轻微抖一下。
 */
public final class SettingsPane extends VBox {

    /** 字体下拉里表示"不指定，跟随系统"的那一项。 */
    private static final String SYSTEM_DEFAULT_LABEL = "系统默认";

    /**
     * 优先排在列表前面的字体。
     *
     * <p>系统里装了多少字体，{@code Font.getFamilies()} 就有多长 ——
     * Windows 上动辄两三百项，全塞进下拉框里找起来很痛苦。
     * 所以把中文阅读最常用的几款提到前面；系统里没装的会被跳过，
     * 用户不会看到一个选了没反应的选项。
     *
     * <p>中英文名都列出来，是因为不同 Windows 语言版本返回的名字不一样：
     * 简体中文系统返回"微软雅黑"，英文系统返回"Microsoft YaHei"。
     */
    private static final List<String> PREFERRED_FONTS = List.of(
            "微软雅黑", "Microsoft YaHei",
            "宋体", "SimSun",
            "楷体", "KaiTi",
            "黑体", "SimHei",
            "仿宋", "FangSong",
            "等线", "DengXian",
            "思源宋体", "Source Han Serif SC",
            "思源黑体", "Source Han Sans SC",
            "华文中宋", "STZhongsong",
            "幼圆", "YouYuan",
            "苹方", "PingFang SC",
            "霞鹜文楷", "LXGW WenKai");

    /** 示例内容：刻意挑了一段有长句、有标点的中文，能看出换行和字距效果。 */
    private static final Chapter PREVIEW_CHAPTER = new Chapter(
            "preview", 0, "第一章 星光落下", null, 0, 0,
            List.of(
                    new ChapterBlock.Heading(1, "第一章 星光落下"),
                    new ChapterBlock.Paragraph(
                            "夜色像一块浸了水的布，沉沉地压在屋顶上。这是一段用来试字号和字体的示例文字。"),
                    new ChapterBlock.Paragraph(
                            "汉字的笔画密度比拉丁字母高得多，所以同样的字号，中文看起来会比英文更「满」一些。")));

    private final ComboBox<String> fontBox = new ComboBox<>();
    private final Slider sizeSlider = new Slider(
            ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE, ReaderSettings.defaults().fontSize());
    private final Slider spacingSlider = new Slider(
            ReaderSettings.MIN_PARAGRAPH_SPACING, ReaderSettings.MAX_PARAGRAPH_SPACING,
            ReaderSettings.defaults().paragraphSpacing());
    private final Slider lineSpacingSlider = new Slider(
            ReaderSettings.MIN_LINE_SPACING, ReaderSettings.MAX_LINE_SPACING,
            ReaderSettings.defaults().lineSpacing());

    private final Label sizeValueLabel = new Label();
    private final Label spacingValueLabel = new Label();
    private final Label lineSpacingValueLabel = new Label();
    private final VBox previewBox = new VBox();

    private final Consumer<ReaderSettings> onChange;
    private ReaderSettings current;

    /**
     * 正在"灌值"（面板自己改控件）。
     *
     * <p>灌值会触发控件的监听器，如果不挡住，就会把"面板刚被同步成 X"
     * 当成"用户把设置改成了 X"再抛出去 —— 打开窗口时主界面白白重排一遍正文。
     */
    private boolean loading;

    /**
     * @param initial  打开时的设置
     * @param onChange 用户改动静时的回调（灌值时不触发）
     * @param onClose  「关闭」按钮的动作
     */
    public SettingsPane(ReaderSettings initial, Consumer<ReaderSettings> onChange, Runnable onClose) {
        this.onChange = (onChange == null) ? settings -> { } : onChange;
        this.current = (initial == null) ? ReaderSettings.defaults() : initial;

        getStyleClass().add("settings-pane");

        Node header = buildHeader();
        Node form = buildForm();
        Node preview = buildPreviewSection();
        Node footer = buildFooter(onClose);
        // 只有预览区吃掉纵向余量：窗口被拉高时，多出来的高度应该给预览
        // （它是唯一"越长越有用"的部分），而不是在各块之间平均分掉
        VBox.setVgrow(preview, Priority.ALWAYS);
        getChildren().setAll(header, form, preview, footer);

        // 灌值必须在控件已经进入场景之后（预览区要能算出行高）。
        // 顺序错了不报错，只是预览一片空白 —— 这类顺序依赖在 JavaFX 里很常见
        applyToControls(this.current);
    }

    // ==================== 界面 ====================

    /** 顶部一条说明。窗口自己有标题栏，"改动立刻生效"这句话得在里面说。 */
    private Node buildHeader() {
        Label title = new Label("阅读设置");
        title.getStyleClass().add("settings-header-title");

        Label hint = new Label("改动立刻生效，不需要保存");
        hint.getStyleClass().add("settings-hint");

        VBox header = new VBox(3, title, hint);
        header.getStyleClass().add("settings-header");
        return header;
    }

    private Node buildForm() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(14);
        grid.setPadding(new Insets(18, 20, 6, 20));

        prepareFontBox();
        configureSlider(sizeSlider, ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE, 1);
        configureSlider(spacingSlider, ReaderSettings.MIN_PARAGRAPH_SPACING,
                ReaderSettings.MAX_PARAGRAPH_SPACING, 1);
        // 行距是 1.0~2.5 的倍率，刻度必须细到 0.1 —— 按 1 吸附的话
        // 这个滑块只能在 1.0 / 2.0 两个值之间跳，等于没有中间档
        configureSlider(lineSpacingSlider, ReaderSettings.MIN_LINE_SPACING,
                ReaderSettings.MAX_LINE_SPACING, 0.1);

        // 数值列右对齐，三行文字的左边缘才能对齐
        sizeValueLabel.setAlignment(Pos.CENTER_RIGHT);
        spacingValueLabel.setAlignment(Pos.CENTER_RIGHT);
        lineSpacingValueLabel.setAlignment(Pos.CENTER_RIGHT);
        for (Label valueLabel : List.of(sizeValueLabel, spacingValueLabel, lineSpacingValueLabel)) {
            valueLabel.getStyleClass().add("settings-value");
        }

        grid.add(label("字体"), 0, 0);
        grid.add(fontBox, 1, 0);
        GridPane.setHgrow(fontBox, Priority.ALWAYS);

        grid.add(label("字号"), 0, 1);
        grid.add(sizeSlider, 1, 1);
        grid.add(sizeValueLabel, 2, 1);

        grid.add(label("段间距"), 0, 2);
        grid.add(spacingSlider, 1, 2);
        grid.add(spacingValueLabel, 2, 2);

        grid.add(label("行距"), 0, 3);
        grid.add(lineSpacingSlider, 1, 3);
        grid.add(lineSpacingValueLabel, 2, 3);
        return grid;
    }

    private Node buildPreviewSection() {
        Label previewTitle = label("预览");
        previewBox.getStyleClass().add("settings-preview");
        previewBox.setPadding(new Insets(14, 16, 16, 16));

        VBox section = new VBox(8, previewTitle, previewBox);
        section.setPadding(new Insets(6, 20, 12, 20));
        VBox.setVgrow(previewBox, Priority.ALWAYS);
        return section;
    }

    private Node buildFooter(Runnable onClose) {
        Button resetButton = new Button("恢复默认");
        resetButton.setOnAction(e -> resetToDefaults());

        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().add("settings-close");
        closeButton.setDefaultButton(true);
        closeButton.setOnAction(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(10, resetButton, spacer, closeButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(4, 20, 16, 20));
        return footer;
    }

    private void prepareFontBox() {
        fontBox.getItems().setAll(buildFontChoices());
        fontBox.setVisibleRowCount(14);
        fontBox.setPrefWidth(280);
        // 不做成可编辑的：可编辑 ComboBox 会要求处理"用户输入了不存在的字体名"
        // 这类输入校验，而这里的选项本身就是从系统字体列表里来的，没必要留这个口子
        fontBox.setOnAction(e -> onFontChanged());
    }

    /**
     * 滑块的统一配置。
     *
     * @param tickUnit 吸附步长。字号和段间距是整数（1），行距要 0.1。
     *
     * <p>吸附到刻度是必要的：否则用户很难刚好拖到 18，会得到 17.83 这种值，
     * 显示成 "18" 但实际存的是 17.83，下次打开又显示成 18 —— 很别扭。
     */
    private void configureSlider(Slider slider, double min, double max, double tickUnit) {
        slider.setMinorTickCount(0);
        slider.setMajorTickUnit(tickUnit);
        slider.setBlockIncrement(tickUnit);
        slider.setSnapToTicks(true);
        slider.setPrefWidth(240);
        slider.valueProperty().addListener((obs, oldValue, newValue) -> onSliderChanged());
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-label");
        return l;
    }

    // ==================== 数据流 ====================

    /**
     * 把外部的设置灌进控件。
     *
     * <p>窗口每次打开前会调它一次，保证显示的是"当前真正生效的值" ——
     * 用户可能刚在菜单里点过「增大字号」，设置窗口里必须是新值。
     *
     * <p>灌值期间闭麦（见 {@link #loading}）。预热预览仍然会跑，
     * 因为那是本面板自己的事，不涉及主界面。
     */
    public void applyToControls(ReaderSettings settings) {
        loading = true;
        try {
            current = (settings == null) ? ReaderSettings.defaults() : settings;

            fontBox.setValue(current.fontFamily() == null ? SYSTEM_DEFAULT_LABEL : current.fontFamily());
            if (!fontBox.getItems().contains(fontBox.getValue())) {
                // 数据库里记的字体在这台机器上没装（比如换了电脑），
                // 把它临时加进列表，免得下拉框显示成空白、用户一头雾水
                fontBox.getItems().add(0, fontBox.getValue());
            }
            sizeSlider.setValue(current.fontSize());
            spacingSlider.setValue(current.paragraphSpacing());
            lineSpacingSlider.setValue(current.lineSpacing());

            refreshValueLabels();
            refreshPreview();
        } finally {
            loading = false;
        }
    }

    /**
     * 当前面板上的设置。
     *
     * <p>主题不在这个面板里（主题菜单在「视图」下），所以它始终跟着
     * 外部传进来的那一份走 —— {@link ReaderSettings#withTheme} 之外的几项
     * 才是这里能改的东西。
     */
    public ReaderSettings currentSettings() {
        return current;
    }

    private void resetToDefaults() {
        applyToControls(ReaderSettings.defaults().withTheme(current.theme()));
        publish();
    }

    private void onFontChanged() {
        String chosen = fontBox.getValue();
        current = current.withFontFamily(SYSTEM_DEFAULT_LABEL.equals(chosen) ? null : chosen);
        refreshPreview();
        publish();
    }

    private void onSliderChanged() {
        current = current
                .withFontSize((int) Math.round(sizeSlider.getValue()))
                .withParagraphSpacing(Math.round(spacingSlider.getValue()))
                // 行距保留一位小数：这是滑块的实际步长，
                // 不 round 的话会得到 1.6000000000000003 这种值被写进数据库
                .withLineSpacing(Math.round(lineSpacingSlider.getValue() * 10.0) / 10.0);
        refreshValueLabels();
        refreshPreview();
        publish();
    }

    private void publish() {
        if (loading) {
            return;
        }
        onChange.accept(current);
    }

    private void refreshValueLabels() {
        sizeValueLabel.setText(current.fontSize() + " px");
        spacingValueLabel.setText((long) current.paragraphSpacing() + " px");
        lineSpacingValueLabel.setText(String.format("%.1f 倍", current.lineSpacing()));
    }

    /**
     * 重画预览区。
     *
     * <p>段间距是容器属性（{@code VBox} 的 spacing），所以设在外层的
     * {@code previewBox} 上；字体、字号、行距则由 {@link ChapterRenderer} 写进各段的
     * 行内样式与 {@code TextFlow} 属性里 —— 和正文区的分工完全一致。
     */
    private void refreshPreview() {
        previewBox.setSpacing(current.paragraphSpacing());
        previewBox.getChildren().setAll(ChapterRenderer.render(PREVIEW_CHAPTER, current));
    }

    /**
     * 系统字体列表：常用字体优先，其余按名称排序接在后面。
     *
     * <p>用 {@link LinkedHashSet} 去重，是因为中文字体在系统里可能同时
     * 以中文名和英文名注册（"楷体" 与 "KaiTi"），而我们的优先列表里两个都写了。
     */
    private static List<String> buildFontChoices() {
        Set<String> installed = new LinkedHashSet<>(Font.getFamilies());
        List<String> choices = new ArrayList<>();
        choices.add(SYSTEM_DEFAULT_LABEL);

        for (String preferred : PREFERRED_FONTS) {
            if (installed.contains(preferred) && !choices.contains(preferred)) {
                choices.add(preferred);
            }
        }
        installed.stream().sorted().forEach(name -> {
            if (!choices.contains(name)) {
                choices.add(name);
            }
        });
        return choices;
    }
}
