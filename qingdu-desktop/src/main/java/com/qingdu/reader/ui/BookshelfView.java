package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import com.qingdu.store.QingduStore;
import com.qingdu.store.StoreException;
import com.qingdu.store.model.RecentBook;
import com.qingdu.store.model.ReadingProgress;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 书架 —— 没有打开任何书的时候，主界面就是这个。
 *
 * <p><b>"本地有哪些书"以什么为准？以书库为准，不是以磁盘为准。</b>
 * 也就是说它列的是 {@code book} 表里的记录：打开过的书、导入过的书。
 * 换一种做法（每次打开都去扫某个"书库文件夹"）看起来更"实时"，
 * 但代价是每次进书架都要遍历磁盘，而且"我在读哪本、读到哪了"这件事
 * 本来就只有书库知道 —— 与其存一个目录设置再每次扫盘，
 * 不如把"导入"做成一个用户主动触发的动作，之后一切以库为准。
 *
 * <p><b>为什么这个类里没有一个对话框？</b>
 * 因为对话框要跟着主题换肤（夜间主题下弹出一个惨白的框很难看），
 * 而换肤需要 {@code Theme} —— 那是 {@code ReaderView} 的职责。
 * 所以这里只把用户的意图通过 {@link Actions} 抛出去：
 * "有人点了这本书"、"有人要移除这本书"，具体弹什么框、删什么数据由外面决定。
 * 这样一来这个类就变成了一个<b>纯粹的展示 + 转发</b>组件，
 * 不依赖主题、不需要 store 之外的东西，也没什么可测错的。
 *
 * <p>网格用 {@link FlowPane} 而不是 {@code GridPane}：卡片宽度固定，
 * 窗口变宽时希望自动多排一列。{@code FlowPane} 就是干这个的，
 * {@code GridPane} 得自己算列数，白写一堆样板。
 */
public class BookshelfView extends BorderPane {

    /**
     * 书架上的动作，由 {@code ReaderView} 实现。
     *
     * <p>用接口而不是四个 {@code Runnable}/{@code Consumer} 参数：
     * 调用点写出来是 {@code new BookshelfView(store, this::open, this::chooseFile, ...)}——
     * 四个裸 lambda 排在一起，谁也看不出哪个是哪个。
     */
    public interface Actions {
        /** 请求打开一本书。文件可能已经不在磁盘上，由实现方去分辨。 */
        void open(Book book);

        /** 请求弹出文件选择框。 */
        void chooseFile();

        /** 请求导入一个文件夹。 */
        void importFolder();

        /** 请求把一本书从书架移除（要确认、要删数据，都归实现方）。 */
        void remove(Book book);
    }

    /** 封面宽度。卡片宽度在它基础上留出左右内边距。 */
    private static final double COVER_WIDTH = 110;

    private static final double CARD_WIDTH = COVER_WIDTH + 24;

    private final QingduStore store;
    private final Actions actions;

    private final Label countLabel = new Label();
    private final FlowPane grid = new FlowPane();
    private final ScrollPane gridScroll = new ScrollPane(grid);
    private final VBox emptyState = new VBox();

    public BookshelfView(QingduStore store, Actions actions) {
        this.store = store;
        this.actions = actions;

        getStyleClass().add("bookshelf");

        grid.getStyleClass().add("bookshelf-grid");
        grid.setHgap(16);
        grid.setVgap(18);
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setPadding(new Insets(20, 22, 26, 22));

        // 撑满宽度让 FlowPane 自己换行；横向滚动条关掉 ——
        // 既然是自动换行，横着能滚就说明行没排好
        gridScroll.getStyleClass().add("bookshelf-scroll");
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        buildEmptyState();
        setTop(buildHeader());
        refresh();
    }

    // ==================== 数据 ====================

    /** 重新从书库拉一遍数据并重画。导入完、移除完、关掉一本书之后都要调一次。 */
    public void refresh() {
        if (store == null) {
            countLabel.setText("");
            grid.getChildren().clear();
            setCenter(emptyState);
            return;
        }

        List<RecentBook> books;
        try {
            books = store.books().list();
        } catch (StoreException e) {
            countLabel.setText("");
            grid.getChildren().clear();
            setCenter(emptyState);
            System.err.println("[轻读] 读取书架失败：" + e);
            return;
        }

        if (books.isEmpty()) {
            countLabel.setText("");
            grid.getChildren().clear();
            setCenter(emptyState);
            return;
        }

        countLabel.setText("共 " + books.size() + " 本");
        List<Node> cards = new ArrayList<>(books.size());
        for (RecentBook item : books) {
            cards.add(buildCard(item));
        }
        grid.getChildren().setAll(cards);
        setCenter(gridScroll);
    }

    // ==================== 界面 ====================

    private Node buildHeader() {
        Label title = new Label("书架");
        title.getStyleClass().add("bookshelf-title");
        countLabel.getStyleClass().add("bookshelf-count");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button importButton = new Button("＋ 导入文件夹…");
        importButton.setOnAction(e -> actions.importFolder());

        HBox bar = new HBox(10, title, countLabel, spacer, importButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("bookshelf-header");
        return bar;
    }

    /**
     * 空库时的引导页。
     *
     * <p>提示分两行、按钮并排放在中间：一个新用户打开程序看到的第一个界面
     * 就是这个，所以"我能干什么"必须一眼看出来，
     * 不能只丢一句"没有书"。
     */
    private void buildEmptyState() {
        Label title = new Label("书架还是空的");
        title.getStyleClass().add("bookshelf-empty-title");

        Label hint = new Label("把一个装着 TXT 小说的文件夹整个导进来，\n或者直接打开一本书。");
        hint.getStyleClass().add("bookshelf-empty-hint");
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);

        Button importButton = new Button("导入文件夹…");
        importButton.setOnAction(e -> actions.importFolder());

        Button openButton = new Button("打开文件…");
        openButton.setOnAction(e -> actions.chooseFile());

        HBox buttons = new HBox(12, importButton, openButton);
        buttons.setAlignment(Pos.CENTER);

        emptyState.getStyleClass().add("bookshelf-empty");
        emptyState.setSpacing(14);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.getChildren().setAll(BookCover.forBook(null, 84), title, hint, buttons);
    }

    /**
     * 一本书的卡片：封面 / 书名 / 作者 / 进度。
     *
     * <p>单击即打开。书架上没有"选中某一本"这个中间状态
     * （不像列表那样需要键盘导航），所以单击是明确的、不会误触的。
     * 其余的操作用右键菜单。
     */
    private Node buildCard(RecentBook item) {
        Book book = item.book();
        Path path = book.filePath();
        boolean missing = (path == null) || !Files.isRegularFile(path);
        String location = (path == null) ? "（位置未知）" : path.toString();

        Label title = new Label(book.title());
        title.getStyleClass().add("book-card-title");
        title.setWrapText(true);
        title.setMaxWidth(CARD_WIDTH);
        // 长书名换行会把卡片撑高，网格里就会参差不齐。
        // 给标题留出固定高度（两行），短书名也不会显得"缺一块"。
        title.setMinHeight(Region.USE_PREF_SIZE);

        Label author = new Label(book.authorName().orElse("作者未知"));
        author.getStyleClass().add("book-card-meta");
        author.setMaxWidth(CARD_WIDTH);
        author.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label status = new Label(missing ? "文件缺失" : progressText(item));
        status.getStyleClass().add(missing ? "book-card-missing" : "book-card-progress");
        status.setMaxWidth(CARD_WIDTH);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);

        VBox card = new VBox(6, BookCover.forBook(book, COVER_WIDTH), title, author, status);
        card.getStyleClass().add("book-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setMinWidth(CARD_WIDTH);
        card.setAlignment(Pos.TOP_LEFT);

        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                actions.open(book);
            }
        });
        // 卡片是普通布局节点（这样才能自由地摆封面、书名、进度），
        // 而 ContextMenu 是 Control 才有的属性 —— VBox 没有 setContextMenu。
        // 所以这里自己接"请求右键菜单"这个事件，在鼠标位置弹出。
        ContextMenu menu = buildCardMenu(book, missing);
        card.setOnContextMenuRequested(
                event -> menu.show(card, event.getScreenX(), event.getScreenY()));

        // 完整路径放进悬停提示：书架上只能显示书名，但用户偶尔想确认
        // "这本到底是哪个文件"（同一个书名下载过好几份的情况并不少见）
        Tooltip tooltip = new Tooltip(missing ? "文件已不在原位置：\n" + location : location);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(460);
        Tooltip.install(card, tooltip);

        return card;
    }

    private String progressText(RecentBook item) {
        ReadingProgress progress = item.progress();
        if (progress == null || !progress.started()) {
            return "尚未开始";
        }
        return "第 " + (progress.chapterIndex() + 1) + " 章 · " + progress.percentLabel();
    }

    private ContextMenu buildCardMenu(Book book, boolean missing) {
        MenuItem open = new MenuItem("打开");
        open.setDisable(missing);
        open.setOnAction(e -> actions.open(book));

        MenuItem reveal = new MenuItem("在资源管理器中显示");
        reveal.setDisable(missing);
        reveal.setOnAction(e -> revealInExplorer(book.filePath()));

        MenuItem remove = new MenuItem("从书架移除");
        remove.setOnAction(e -> actions.remove(book));

        return new ContextMenu(open, reveal, new SeparatorMenuItem(), remove);
    }

    /**
     * 在资源管理器里定位到这个文件（Windows 下会把文件高亮选中）。
     *
     * <p>用 {@code Desktop.browseFileDirectory} 而不是拼 {@code explorer.exe /select,}：
     * 后者要自己处理带空格的路径该不该加引号的问题，而那套引号规则和
     * {@code ProcessBuilder} 的转义规则会打架，很容易变成"路径里带空格就打不开"。
     * 标准 API 一行搞定，还顺带支持 macOS / Linux。
     *
     * <p>失败就<em>只写日志</em>：定位文件是锦上添花的功能，
     * 弹一个错误框告诉用户"打不开资源管理器"反而更烦人。
     */
    private void revealInExplorer(Path file) {
        if (file == null) {
            return;
        }
        try {
            File target = file.toFile();
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(target);
            }
        } catch (RuntimeException e) {
            System.err.println("[轻读] 无法定位文件：" + e);
        }
    }
}
