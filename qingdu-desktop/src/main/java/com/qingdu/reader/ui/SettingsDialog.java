package com.qingdu.reader.ui;

import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.settings.ReaderSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「阅读设置」对话框：字体、字号、段间距。
 *
 * <p><b>预览区为什么直接调用 {@link ChapterRenderer}？</b><br>
 * 这是这个对话框设计上最要紧的一点。如果预览用另一套代码画（哪怕只是
 * "差不多"地设一下字号），那它早晚会和真正的正文渲染<b>跑偏</b>：
 * 有人改了正文的排版规则、忘了改预览，用户就会看到"预览和实际不一样"。
 *
 * <p>这里让预览走<b>和正文完全相同的那条渲染路径</b>，
 * 只是喂给它一段示例文字。于是"预览是否准确"就不再需要人去维护，
 * 它<em>结构上</em>不可能不准。
 *
 * <p>对话框本身用 {@code Dialog<ReaderSettings>} 而不是自己拼一个
 * {@code Stage}：JavaFX 的 {@code Dialog} 已经处理好了模态、按钮栏、
 * 键盘 Esc 关闭、窗口大小这些琐事，自己做一个只会多出一堆边界 bug。
 */
public class SettingsDialog extends Dialog<ReaderSettings> {

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
            "preview", 0, "第一章 星光落下", 0, 0,
            List.of(
                    new ChapterBlock.Heading(1, "第一章 星光落下"),
                    new ChapterBlock.Paragraph(
                            "夜色像一块浸了水的布，沉沉地压在屋顶上。这是一段用来试字号和字体的示例文字。"),
                    new ChapterBlock.Paragraph(
                            "汉字的笔画密度比拉丁字母高得多，所以同样的字号，中文看起来会比英文更\"满\"一些。")));

    private final ComboBox<String> fontBox = new ComboBox<>();
    private final Slider sizeSlider = new Slider(
            ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE, ReaderSettings.defaults().fontSize());
    private final Slider spacingSlider = new Slider(
            ReaderSettings.MIN_PARAGRAPH_SPACING, ReaderSettings.MAX_PARAGRAPH_SPACING,
            ReaderSettings.defaults().paragraphSpacing());

    private final Label sizeValueLabel = new Label();
    private final Label spacingValueLabel = new Label();
    private final VBox previewBox = new VBox();

    private final ReaderSettings original;
    private ReaderSettings pending;

    public SettingsDialog(ReaderSettings current, Window owner) {
        this.original = (current == null) ? ReaderSettings.defaults() : current;
        this.pending = original;

        setTitle("阅读设置");
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        DialogPane pane = getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        pane.setContent(buildContent());
        // 对话框有自己的 Scene，不会继承主窗口的样式表，必须单独装一遍
        ThemeStyles.apply(pane, original.theme());
        // 让"确定"成为回车键的默认动作
        if (pane.lookupButton(ButtonType.OK) instanceof Button okButton) {
            okButton.setDefaultButton(true);
        }

        // 把控件上的当前值灌进去（注意要在 setContent 之后，
        // 否则触发监听器时预览区还不存在）
        applyToControls(original);

        // 点确定才把结果交出去；点取消或直接关窗口时返回 null（showAndWait 会得到空 Optional）
        setResultConverter(button -> button == ButtonType.OK ? pending : null);
    }

    // ==================== 界面 ====================

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(14);
        grid.setPadding(new Insets(18, 20, 6, 20));

        prepareFontBox();
        configureSlider(sizeSlider, ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE);
        configureSlider(spacingSlider, ReaderSettings.MIN_PARAGRAPH_SPACING, ReaderSettings.MAX_PARAGRAPH_SPACING);
        // 数值列右对齐，三行文字的左边缘才能对齐
        sizeValueLabel.setAlignment(Pos.CENTER_RIGHT);
        spacingValueLabel.setAlignment(Pos.CENTER_RIGHT);

        grid.add(label("字体"), 0, 0);
        grid.add(fontBox, 1, 0);
        GridPane.setHgrow(fontBox, Priority.ALWAYS);

        grid.add(label("字号"), 0, 1);
        grid.add(sizeSlider, 1, 1);
        grid.add(sizeValueLabel, 2, 1);

        grid.add(label("段间距"), 0, 2);
        grid.add(spacingSlider, 1, 2);
        grid.add(spacingValueLabel, 2, 2);

        Label previewTitle = label("预览");
        previewBox.getStyleClass().add("settings-preview");
        previewBox.setPadding(new Insets(14, 16, 16, 16));

        Button resetButton = new Button("恢复默认");
        resetButton.setOnAction(e -> applyToControls(ReaderSettings.defaults()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(resetButton, spacer);
        footer.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, grid, previewTitle, previewBox, footer);
        root.getStyleClass().add("settings-dialog");
        root.setPadding(new Insets(0, 16, 12, 16));
        return root;
    }

    private void prepareFontBox() {
        fontBox.getItems().setAll(buildFontChoices());
        fontBox.setVisibleRowCount(14);
        fontBox.setPrefWidth(280);
        // 不做成可编辑的：可编辑 ComboBox 会要求处理"用户输入了不存在的字体名"
        // 这类输入校验，而这里的选项本身就是从系统字体列表里来的，没必要留这个口子
        fontBox.setOnAction(e -> onFontChanged());
    }

    private void configureSlider(Slider slider, double min, double max) {
        slider.setMinorTickCount(0);
        slider.setMajorTickUnit(1);
        slider.setBlockIncrement(1);
        // 吸附到整数刻度：否则用户很难刚好拖到 18，会得到 17.83 这种值，
        // 显示成 "18" 但实际存的是 17.83，下次打开又显示成 18 —— 很别扭
        slider.setSnapToTicks(true);
        slider.setPrefWidth(240);
        slider.valueProperty().addListener((obs, oldValue, newValue) -> onSliderChanged());
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-label");
        return l;
    }

    /**
     * 把设置值灌进控件。
     *
     * <p>灌值的过程中会触发控件的监听器，于是预览会跟着刷新。
     * 这依赖"控件已经放进场景了"这个前提，所以调用点必须放在
     * {@code setContent} 之后 —— 这类顺序依赖是 JavaFX 里最容易踩的坑之一：
     * 顺序错了不会报错，只是预览区一片空白。
     */
    private void applyToControls(ReaderSettings settings) {
        pending = settings;

        fontBox.setValue(settings.fontFamily() == null ? SYSTEM_DEFAULT_LABEL : settings.fontFamily());
        if (!fontBox.getItems().contains(fontBox.getValue())) {
            // 数据库里记的字体在这台机器上没装（比如换了电脑），
            // 把它临时加进列表，免得下拉框显示成空白、用户一头雾水
            fontBox.getItems().add(0, fontBox.getValue());
        }
        sizeSlider.setValue(settings.fontSize());
        spacingSlider.setValue(settings.paragraphSpacing());

        refreshValueLabels();
        refreshPreview();
    }

    private void onFontChanged() {
        String chosen = fontBox.getValue();
        pending = pending.withFontFamily(SYSTEM_DEFAULT_LABEL.equals(chosen) ? null : chosen);
        refreshPreview();
    }

    private void onSliderChanged() {
        pending = pending
                .withFontSize((int) Math.round(sizeSlider.getValue()))
                .withParagraphSpacing(Math.round(spacingSlider.getValue()));
        refreshValueLabels();
        refreshPreview();
    }

    private void refreshValueLabels() {
        sizeValueLabel.setText(pending.fontSize() + " px");
        spacingValueLabel.setText((long) pending.paragraphSpacing() + " px");
    }

    /**
     * 重画预览区。
     *
     * <p>段间距是容器属性（{@code VBox} 的 spacing），所以设在外层的
     * {@code previewBox} 上；字体和字号则由 {@link ChapterRenderer} 写进各段的
     * 行内样式里 —— 和正文区的分工完全一致。
     */
    private void refreshPreview() {
        previewBox.setSpacing(pending.paragraphSpacing());
        previewBox.getChildren().setAll(ChapterRenderer.render(PREVIEW_CHAPTER, pending));
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
