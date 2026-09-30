package com.qingdu.reader.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

/**
 * 章末的翻章条：「← 上一章」　　第 12 / 1474 章　　「下一章 →」
 *
 * <p>布局是三栏等宽，左右两栏各是一个「按钮 + 标题预览」的竖排 ——
 * 中间的定位文字因此永远在正中间，不会因为左右标题长短不同而左右晃。
 *
 * <p>三条设计选择，每一条都对应一个具体的坏情况：
 * <ol>
 *   <li><b>放在正文流里，跟着正文滚动，而不是固定在窗口底部。</b>
 *       用户读到章末时，期待翻页按钮就在眼跟前；固定到屏幕底部的话，
 *       视线要额外往下扫一屏才能找到它。</li>
 *   <li><b>首章 / 末章的按钮置灰，而不是隐藏。</b>隐藏的话，
 *       中间的「第 N / M 章」会左右横跳（少一个按钮就少一份宽度），
 *       而置灰能同时说清"这是边界"和"这个功能没坏"。</li>
 *   <li><b>按钮下面带一小截目标章节的标题。</b>"下一章"这件事用户有时想先看一眼
 *       标题再决定去不去 —— 章节标题本身有信息量的书（卷名、章节名）尤其明显。</li>
 * </ol>
 *
 * <p><b>为什么目标下标要用静态方法算，而不是在构造器里内联？</b><br>
 * "第一章的上一章是哪一个""最后一章的下一章是哪一个"是仅有的两处边界，
 * 而边界正是最容易写错的地方（{@code index - 1} 在 0 处会变成 -1，
 * 如果拿它去 {@code List.get} 就是一次崩溃）。把它们抽成纯函数之后，
 * 就能用单元测试钉住，不必为了验证一处减法去起一个图形环境。
 */
public final class ChapterNavBar extends HBox {

    /** 按钮下方的标题预览最多留几个字。 */
    private static final int TITLE_PREVIEW_CHARS = 14;

    /** 左右两栏各自的最小宽度，免得极短的标题让按钮贴到一起。 */
    private static final double SIDE_MIN_WIDTH = 150;

    /**
     * @param currentIndex  当前章下标
     * @param total         全书章数
     * @param previousTitle 上一章的标题，可以为 null
     * @param nextTitle     下一章的标题，可以为 null
     * @param onJump        点击按钮时回调目标下标（只会在下标合法时被调用）
     */
    public ChapterNavBar(int currentIndex, int total,
                         String previousTitle, String nextTitle,
                         IntConsumer onJump) {
        int previous = previousIndex(currentIndex, total);
        int next = nextIndex(currentIndex, total);

        VBox left = side("← 上一章", previousTitle, previous, Pos.CENTER_LEFT,
                "已经是第一章了", onJump);
        VBox right = side("下一章 →", nextTitle, next, Pos.CENTER_RIGHT,
                "已经是最后一章了", onJump);

        Label position = new Label("第 " + (currentIndex + 1) + " / " + total + " 章");
        position.getStyleClass().add("chapter-nav-position");

        // 左右两栏都设成"可以伸展"，于是它们平分按钮之外的全部宽度，
        // 中间那句定位文字被挤在正中央 —— 不需要额外算宽度，也不怕标题长短变化
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        setAlignment(Pos.CENTER);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("chapter-nav");
        getChildren().setAll(left, position, right);
    }

    /**
     * 造一栏：一个按钮，下面一行目标章节标题。
     *
     * @param target  目标章节下标；为负表示"到头了"
     * @param endHint 到头时替代标题显示的那句话
     */
    private static VBox side(String caption, String targetTitle, int target, Pos alignment,
                             String endHint, IntConsumer onJump) {
        Button button = new Button(caption);
        button.getStyleClass().add("chapter-nav-button");
        button.setMinWidth(SIDE_MIN_WIDTH);

        Label title = new Label();
        title.getStyleClass().add("chapter-nav-title");
        title.setMaxWidth(SIDE_MIN_WIDTH + 40);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);

        if (target < 0) {
            button.setDisable(true);
            title.setText(endHint);
        } else {
            button.setOnAction(e -> onJump.accept(target));
            String preview = shorten(targetTitle, TITLE_PREVIEW_CHARS);
            title.setText(preview);
            if (targetTitle != null && !targetTitle.isBlank() && !preview.equals(targetTitle)) {
                // 预览被截断了就把完整标题挂成悬停提示，
                // 长章节名（"第 347 章 那一剑的风情与之后的三十年"）才有机会看全
                button.setTooltip(new Tooltip(targetTitle));
            }
        }

        VBox box = new VBox(4, button, title);
        box.setAlignment(alignment);
        return box;
    }

    /**
     * 上一章的下标；当前已经在第一章（或下标本身不合法）时返回 -1。
     *
     * <p>返回 -1 而不是 0 —— 调用方据此把按钮置灰。如果这里直接返回 0，
     * "第一章"就会得到一个指向自己的按钮，点下去什么也不会发生。
     */
    static int previousIndex(int current, int total) {
        if (total <= 0 || current <= 0 || current >= total) {
            return -1;
        }
        return current - 1;
    }

    /** 下一章的下标；已在最后一章时返回 -1。理由同上。 */
    static int nextIndex(int current, int total) {
        if (total <= 0 || current < 0 || current >= total - 1) {
            return -1;
        }
        return current + 1;
    }

    /** 把标题截到 {@code max} 个字，超出部分用省略号代替。 */
    static String shorten(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        return (trimmed.length() <= max) ? trimmed : trimmed.substring(0, max) + "…";
    }
}
