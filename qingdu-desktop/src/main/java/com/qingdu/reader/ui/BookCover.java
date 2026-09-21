package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 一本书的封面图。
 *
 * <p><b>为什么几乎所有书都会走到"占位图"那一支？</b>
 * 因为封面从来就没被存过：{@code Book} 上虽然有个 {@code coverPath} 字段，
 * 但 {@code BookStore} 往库里写/从库里读的时候都给它 {@code null}。
 * TXT 小说本来也不带封面。所以这里的"有图就显示图"目前是一条<b>预留的支路</b>，
 * 真正干活的是占位图 —— 保留那条支路是为了以后做 EPUB 时能直接接上
 * （EPUB 的封面是一张真实的图片），不用再改这里的调用方。
 *
 * <p><b>占位图为什么不放一张 PNG 资源？</b>
 * 因为颜色。这个程序有四套主题，一张固定颜色的 PNG 到了夜间主题下
 * 就是一块刺眼的白斑。用 JavaFX 的图形来画，颜色就能写成 CSS 变量，
 * 跟着主题一起变 —— 和界面里其它元素走同一套机制，不需要为每套主题各出一张图。
 * 具体来说，那个"摊开的书"轮廓是用 {@code -fx-shape} 交给 CSS 描述的，
 * 这里只负责把它和底色叠成一个节点。
 */
public final class BookCover {

    /**
     * 封面高宽比。
     *
     * <p>约 1.4 是纸书的常见比例（也接近 A5、接近各家阅读 App 的封面默认值）。
     * 之所以要固定，是因为网格里的卡片必须一样高，否则一行里会参差不齐。
     */
    private static final double COVER_RATIO = 1.4;

    private BookCover() {
        // 工具类不允许实例化
    }

    /**
     * 造一个宽为 {@code width} 的封面节点。
     *
     * @param book  图书
     * @param width 封面宽度（高度按 {@link #COVER_RATIO} 算出来）
     */
    public static Node forBook(Book book, double width) {
        double height = Math.round(width * COVER_RATIO);

        Optional<Path> imageFile = (book == null) ? Optional.empty()
                : book.cover().filter(Files::isRegularFile);
        if (imageFile.isPresent()) {
            return loadImage(imageFile.get(), width, height);
        }
        return placeholder(width, height);
    }

    /**
     * 加载真实封面。
     *
     * <p>带上宽高交给 {@link Image}，是让它按需要的尺寸解码 ——
     * 一张 2000px 的大图直接塞进 110px 的格子，白白占几 MB 内存。
     * 这在书架上会被放大成几十本一起加载的代价。
     */
    private static Node loadImage(Path file, double width, double height) {
        Image image = new Image(file.toUri().toString(), width, height, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        view.getStyleClass().add("book-cover-image");
        return view;
    }

    /**
     * 通用占位封面：一块圆角底色 + 一个"摊开的书"图形。
     *
     * <p>尺寸写死在节点上（而不是交给 CSS），因为封面尺寸要参与网格排版，
     * 放在代码里和 {@link #COVER_RATIO} 在一起才不容易走散。
     */
    private static Node placeholder(double width, double height) {
        Region glyph = new Region();
        glyph.getStyleClass().add("book-cover-glyph");

        StackPane box = new StackPane(glyph);
        box.getStyleClass().add("book-cover-placeholder");
        box.setAlignment(Pos.CENTER);
        box.setMinSize(width, height);
        box.setPrefSize(width, height);
        box.setMaxSize(width, height);
        return box;
    }
}
