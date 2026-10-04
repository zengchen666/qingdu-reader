package com.qingdu.reader.ui;

import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.settings.ReaderSettings;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.nio.file.Files;
import java.nio.file.Path;
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
 *
 * <p><b>为什么正文一律用 {@link TextFlow} 而不是 {@link Label}（v0.5 起的改动）</b><br>
 * 因为 <b>{@code Label} 不支持行距</b> —— JavaFX 里行距是 {@code TextFlow} 的
 * 属性（{@code setLineSpacing}），{@code Label} 上根本没有这个开关。
 * 要做"行距可调"，段落就没法继续用 Label。
 *
 * <p>代价是<b>放弃了原来的性能优化</b>：v0.4 及之前，只有确实要高亮某个词时
 * 才走 TextFlow，日常阅读每条段落是一个便宜的 {@code Label}。
 * 现在两条路合并成一条，日常阅读也走 TextFlow。这个取舍是清楚的 ——
 * 与其维护两套渲染路径（还要保证它们长得一样），不如付一点节点开销
 * 换掉"预览和正文可能跑偏"这个隐患。而且高亮路径本来就在用 TextFlow，
 * 说明它的开销在真实章节规模下可以接受。
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
     * 把一章渲染成一组节点，并高亮其中的某个词（搜索结果跳转时用）。
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
     *
     * <p><b>为什么要专门支持高亮？</b>
     * 从搜索结果跳过去时，用户看到的是一整章（几千字），
     * 他真正要找的那几个字埋在里面。不给高亮，这个功能的体验就只剩"跳过去了，然后自己找"。
     * 实现上就是把整段按命中位置切成多个 {@code Text}，命中的那些挂上高亮样式类。
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
            case ChapterBlock.Image image -> renderImage(image, settings);
        };
    }

    private static Node renderHeading(ChapterBlock.Heading heading, ReaderSettings settings,
                                      String highlight) {
        double scale = switch (heading.level()) {
            case 1 -> Typography.HEADING1_SCALE;
            case 2 -> Typography.HEADING2_SCALE;
            default -> Typography.HEADING3_SCALE;
        };
        return textFlow(heading.text(), settings, highlight, scale, "reader-heading");
    }

    private static Node renderParagraph(ChapterBlock.Paragraph paragraph, ReaderSettings settings,
                                        String highlight) {
        return textFlow(paragraph.text(), settings, highlight, 1.0, "reader-paragraph");
    }

    /** 这个词在这一段里到底出现了没有 —— 没出现就退化成单个 Text，不做切分。 */
    private static boolean highlighted(String text, String highlight) {
        if (highlight == null || highlight.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        // 忽略大小写比较：和 SearchStore 的后过滤保持一致
        return text.toLowerCase(Locale.ROOT).contains(highlight.toLowerCase(Locale.ROOT));
    }

    /**
     * 造一个能自动换行、带行距的文本流。
     *
     * <p>{@code highlight} 为 null 时整段只有一个 {@code Text}；
     * 否则按命中位置切成若干段，命中的那些额外挂上 {@code reader-highlight}。
     *
     * <p><b>行距为什么设在这里而不是 CSS 里？</b>
     * 因为它是"倍数 × 字号"算出来的像素值（见
     * {@link ReaderSettings#lineSpacingPixels(double)}），字号是运行时的值，
     * 没法写进静态样式表。
     *
     * <p>🔴 <b>样式类必须同时挂在 {@code TextFlow} 和每个 {@code Text} 上</b>（踩过的坑）：
     * JavaFX 里 {@code Text} 的颜色属性是 {@code -fx-fill}（它继承自 {@code Shape}），
     * 而这个属性<b>不会</b>从父节点继承 —— 只把 {@code .reader-paragraph} 挂在
     * TextFlow 上，里面的 {@code Text} 会静默退回 JavaFX 默认的纯黑。
     * 浅色主题下勉强能看，切到夜间主题就变成"黑底上的黑字"。
     * 这类问题在浅色主题下完全看不出来，只能靠 {@code scripts/RenderProbe.java}
     * 那种真的跑一遍布局的探针来抓 —— 单元测试抓不到。
     *
     * <p><b>为什么 {@code Text} 还要自己带一份行内样式？</b>
     * 同理：字号、字体是否往下继承取决于 JavaFX 对各属性的继承规则，
     * 一旦失效现象就是"字号设了但没变"。直接给每个 {@code Text} 设上，
     * 行为是确定的，不赌继承规则。
     */
    private static TextFlow textFlow(String text, ReaderSettings settings, String highlight,
                                     double scale, String styleClass) {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setLineSpacing(settings.lineSpacingPixels(scale));
        // 容器上挂一份：padding、背景这类"盒子属性"只有 Region 认
        flow.getStyleClass().add(styleClass);

        String body = (text == null) ? "" : text;
        if (!highlighted(body, highlight)) {
            flow.getChildren().add(textNode(body, settings, scale, styleClass));
            return flow;
        }

        String haystack = body.toLowerCase(Locale.ROOT);
        String needle = highlight.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= body.length()) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                if (from < body.length()) {
                    flow.getChildren().add(textNode(body.substring(from), settings, scale, styleClass));
                }
                break;
            }
            if (at > from) {
                flow.getChildren().add(textNode(body.substring(from, at), settings, scale, styleClass));
            }
            Text hit = textNode(body.substring(at, at + needle.length()), settings, scale, styleClass);
            hit.getStyleClass().add("reader-highlight");
            flow.getChildren().add(hit);
            from = at + needle.length();
        }
        return flow;
    }

    private static Text textNode(String text, ReaderSettings settings, double scale, String styleClass) {
        Text node = new Text(text);
        node.setStyle(Typography.css(settings, scale));
        node.getStyleClass().add(styleClass);
        return node;
    }

    /**
     * 渲染插图（v0.5 起是真的图，之前只是占位文字）。
     *
     * <p>{@link ChapterBlock.Image} 只带一个 {@code resourcePath} 字符串，
     * 因为渲染器是"格式无关"的 —— 它不认识 zip 包。EPUB 解析器在解析正文时
     * 已经把图片抽到缓存目录，这里只要按路径加载即可。
     *
     * <p><b>宽度为什么跟字号挂钩？</b>
     * 版心宽度就是按 {@code 字号 × 每行字数} 算的（见 {@code ReaderView.applyColumnWidth}）。
     * 图片写死一个像素值的话，字号调小之后版心变窄、图就会溢出被裁掉。
     * 跟着字号缩放，它和文字的视觉比例才稳定。
     *
     * <p><b>加载失败就退回占位文字</b>：图片损坏、路径失效都不是致命问题，
     * 告诉用户"这里有一张图"比让整章渲染不出来好。
     */
    private static Node renderImage(ChapterBlock.Image image, ReaderSettings settings) {
        Path file = imageFile(image.resourcePath());
        if (file != null) {
            try {
                // 同步加载（最后一个参数 false）：后台加载会让首次布局拿不到尺寸，
                // 章节高度算错 → 进度条和"已读到哪"都会偏
                Image loaded = new Image(file.toUri().toString(), imageWidth(settings), 0, true, true, false);
                if (!loaded.isError()) {
                    ImageView view = new ImageView(loaded);
                    view.setPreserveRatio(true);
                    view.getStyleClass().add("reader-image");
                    return view;
                }
            } catch (RuntimeException ignored) {
                // 落到下面的占位文字
            }
        }
        return imagePlaceholder(image);
    }

    private static double imageWidth(ReaderSettings settings) {
        return Math.min(520, Math.max(160, settings.fontSize() * 26));
    }

    /** 只有确实是文件才加载 —— 防 {@code resourcePath} 被填成任意字符串。 */
    private static Path imageFile(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(resourcePath);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Label imagePlaceholder(ChapterBlock.Image image) {
        Label label = new Label("［插图：" + image.resourcePath() + "］");
        label.getStyleClass().add("reader-image-note");
        return label;
    }

    private static Label emptyHint() {
        Label label = new Label("（本章没有正文内容）");
        label.getStyleClass().add("reader-muted-hint");
        return label;
    }
}
