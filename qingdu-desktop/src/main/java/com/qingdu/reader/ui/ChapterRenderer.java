package com.qingdu.reader.ui;

import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.settings.ReaderSettings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 章节内容渲染器 —— 把 {@link Chapter} 翻译成 JavaFX 节点。
 *
 * <p><b>这一层为什么必须存在？</b><br>
 * 因为 {@link ChapterBlock} 是"格式无关"的中间表示。
 * TXT 解析出来是段落，EPUB 解析出来也是段落 —— 差别被挡在解析层，
 * 到了这里只剩下三种块类型。所以这个类<b>一行都不需要知道内容来自哪种文件</b>。
 * 以后加 MOBI 支持时，这个文件不用改。
 *
 * <p>渲染逻辑用 {@code switch} 模式匹配穷举了 sealed 接口的所有实现，
 * 因此不需要 {@code default} 分支。将来给 {@code ChapterBlock} 加一种新块类型，
 * 编译器会在这里直接报错提醒补上渲染代码 —— 这是 sealed 接口带来的安全网。
 *
 * <p><b>颜色与字体的分工</b>（这是能换主题的前提）：
 * <ul>
 *   <li><b>颜色一律不写</b>，只挂样式类（{@code reader-paragraph} 等），
 *       具体色值由主题样式表决定。行内样式的优先级比样式表高，
 *       只要这里写死一个颜色，换主题时它就会"顽固地"不跟着变。</li>
 *   <li><b>字体族和字号写行内样式</b>，因为它们由用户在运行时随时调整，
 *       没法预置在静态的 CSS 文件里。</li>
 * </ul>
 * 这条界线看着琐碎，但它是"主题能不能用"和"字体字号能不能用"两件事
 * 同时成立的关键。
 */
public final class ChapterRenderer {

    private ChapterRenderer() {
        // 工具类不允许实例化
    }

    /** 用默认设置渲染，给不需要自定义排版的场景（如测试）用。 */
    public static List<Node> render(Chapter chapter) {
        return render(chapter, ReaderSettings.defaults(), null);
    }

    /** 不高亮任何词的常规渲染。 */
    public static List<Node> render(Chapter chapter, ReaderSettings settings) {
        return render(chapter, settings, null);
    }

    /**
     * 把一章渲染成一组节点。
     *
     * <p>返回 {@code List} 而不是包一层 {@code VBox}：段落之间的间距、
     * 正文区四周的留白，都是"容器"的属性，交给 {@code ReaderView} 统一管，
     * 这里只管每一块自己长什么样。少包一层容器，样式属性也就只有一处定义，
     * 不会出现"外层设了 14px、内层又设了 12px"这种互相打架的情况。
     *
     * <p>正式出版物的排版规范是"段落首行缩进两字"，
     * 但网文读者更习惯"段落之间空一行、不缩进"。这里采用后者，
     * 因为它对长短段落的适应性更好，也不会因为全角空格在不同字体下的
     * 宽度差异导致缩进忽宽忽窄。
     */
    /**
     * 把一章渲染成一组节点，并高亮其中的某个词（搜索结果跳转时用）。
     *
     * <p><b>为什么要专门支持高亮？</b>
     * 从搜索结果跳过去时，用户看到的是一整章（几千字），
     * 他真正要找的那几个字埋在里面。不给高亮，这个功能的体验就只剩"跳过去了，然后自己找"。
     *
     * <p>实现上有个绕不开的取舍：要高亮就必须把整段拆成多个 {@code Text} 节点
     * 放进 {@code TextFlow}，而不能用一整个 {@code Label}。
     * 所以只在<b>确实要高亮</b>时才走 {@code TextFlow} 这条路
     * （见 {@link #highlighted}），常规阅读仍然是每条一个 Label ——
     * 日常翻页占绝大多数，没必要为了偶尔一次搜索付出额外的节点开销。
     *
     * @param chapter   要渲染的章节
     * @param settings  阅读设置
     * @param highlight 要高亮的词；null 或空白表示不高亮
     */
    public static List<Node> render(Chapter chapter, ReaderSettings settings, String highlight) {
        ReaderSettings effective = (settings == null) ? ReaderSettings.defaults() : settings;
        List<Node> nodes = new ArrayList<>();

        if (chapter == null || chapter.blocks().isEmpty()) {
            nodes.add(emptyHint());
            return nodes;
        }

        for (ChapterBlock block : chapter.blocks()) {
            nodes.add(renderBlock(block, effective, highlight));
        }
        return nodes;
    }

    private static Node renderBlock(ChapterBlock block, ReaderSettings settings, String highlight) {
        return switch (block) {
            case ChapterBlock.Heading heading -> renderHeading(heading, settings, highlight);
            case ChapterBlock.Paragraph paragraph -> renderParagraph(paragraph, settings, highlight);
            case ChapterBlock.Image image -> renderImagePlaceholder(image);
        };
    }

    private static Node renderHeading(ChapterBlock.Heading heading, ReaderSettings settings,
                                      String highlight) {
        double scale = switch (heading.level()) {
            case 1 -> Typography.HEADING1_SCALE;
            case 2 -> Typography.HEADING2_SCALE;
            default -> Typography.HEADING3_SCALE;
        };
        if (highlighted(heading.text(), highlight)) {
            TextFlow flow = highlightFlow(heading.text(), settings, highlight, scale);
            flow.getStyleClass().add("reader-heading");
            return flow;
        }
        Label label = newTextLabel(heading.text(), settings, scale);
        label.getStyleClass().add("reader-heading");
        return label;
    }

    private static Node renderParagraph(ChapterBlock.Paragraph paragraph, ReaderSettings settings,
                                        String highlight) {
        if (highlighted(paragraph.text(), highlight)) {
            TextFlow flow = highlightFlow(paragraph.text(), settings, highlight, 1.0);
            flow.getStyleClass().add("reader-paragraph");
            return flow;
        }
        Label label = newTextLabel(paragraph.text(), settings, 1.0);
        label.getStyleClass().add("reader-paragraph");
        return label;
    }

    /** 这个词在这一段里到底出现了没有 —— 没出现就用回便宜的 Label 渲染。 */
    private static boolean highlighted(String text, String highlight) {
        if (highlight == null || highlight.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        // 忽略大小写比较：和 SearchStore 的后过滤保持一致
        return text.toLowerCase(Locale.ROOT).contains(highlight.toLowerCase(Locale.ROOT));
    }

    /**
     * 把一段文字按命中位置切成若干 {@code Text}，命中的那些挂上高亮样式类。
     *
     * <p><b>为什么用 {@code TextFlow} 而不是给 Label 上色？</b>
     * {@code Label} 只能整块一个颜色，做不到"段落里只标出几个字"。
     * {@code TextFlow} 则可以按顺序摆放任意多个 {@code Text}，
     * 换行行为与普通段落一致（这是它能直接替代 Label 的前提）。
     *
     * <p>对比时用小写：搜索是大小写无关的（见 {@code SearchStore#indexOfIgnoreCase}），
     * 高亮自然也要无关，否则会出现"搜到了但不标出来"。
     */
    private static TextFlow highlightFlow(String text, ReaderSettings settings, String highlight,
                                          double scale) {
        TextFlow flow = new TextFlow();
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = highlight.toLowerCase(Locale.ROOT);

        int from = 0;
        while (from <= text.length()) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                if (from < text.length()) {
                    flow.getChildren().add(textNode(text.substring(from), settings, scale));
                }
                break;
            }
            if (at > from) {
                flow.getChildren().add(textNode(text.substring(from, at), settings, scale));
            }
            Text hit = textNode(text.substring(at, at + needle.length()), settings, scale);
            hit.getStyleClass().add("reader-highlight");
            flow.getChildren().add(hit);
            from = at + needle.length();
        }
        return flow;
    }

    private static Text textNode(String text, ReaderSettings settings, double scale) {
        Text node = new Text(text);
        node.setStyle(Typography.css(settings, scale));
        return node;
    }

    /**
     * 图片先渲染成占位提示。
     *
     * <p>暂时不做真实图片加载，因为图片缓存目录还没设计好 ——
     * 与其做一个半吊子实现，不如先明确告诉用户"这里有张图"。
     * 阶段 4 支持 EPUB 时会连同图片缓存一起完成。
     */
    private static Label renderImagePlaceholder(ChapterBlock.Image image) {
        Label label = new Label("［插图：" + image.resourcePath() + "］");
        label.getStyleClass().add("reader-image-note");
        return label;
    }

    private static Label emptyHint() {
        Label label = new Label("（本章没有正文内容）");
        label.getStyleClass().add("reader-muted-hint");
        return label;
    }

    /**
     * 造一个自动换行的文本标签。
     *
     * <p>{@code setMaxWidth(MAX_VALUE)} + {@code setWrapText(true)} 是让中文段落
     * 正确换行的关键组合：{@code Label} 默认按内容宽度算，不撑满容器，
     * 结果就是长段落变成一条横向拉不到头的长条，完全不会自动换行。
     */
    private static Label newTextLabel(String text, ReaderSettings settings, double scale) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setStyle(Typography.css(settings, scale));
        return label;
    }
}
