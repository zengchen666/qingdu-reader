package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.settings.ReaderSettings;
import com.qingdu.common.settings.Theme;
import com.qingdu.core.parser.BookParseException;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.core.parser.txt.TxtChapterSplitter;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.reader.library.BookImporter;
import com.qingdu.store.QingduStore;
import com.qingdu.store.StoreException;
import com.qingdu.store.model.Bookmark;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.RecentBook;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 阅读器主界面。
 *
 * <p>布局是经典的"三明治"结构：
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │ 菜单栏                                    │
 *   ├──────────────────────────────────────────┤
 *   │ 书籍信息（书名 / 作者 / 编码）              │
 *   ├──────────────┬───────────────────────────┤
 *   │ 目录 / 书签   │        正文（版心）         │
 *   │  (TabPane)   │      (ScrollPane)         │
 *   │              ├───────────────────────────┤
 *   │              │  ▬▬▬▬▬▬  全书进度条（3px）  │
 *   ├──────────────┴───────────────────────────┤
 *   │ 章节 · 章内 % · 本章字节            全书 N% │
 *   └──────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>中间那块有两种形态，二选一：</b>
 * <ul>
 *   <li><b>书架态</b>（没有打开书）—— 整个中心区是一面封面墙，见
 *       {@link BookshelfView}。此时左侧的「目录 / 书签」栏<b>整个不在</b>：
 *       两个空列表看起来像坏了，而不是"还没有书"。书名条和状态栏也一并复位。</li>
 *   <li><b>阅读态</b>（打开了书）—— 就是上面那张图。</li>
 * </ul>
 * 两者挂在同一个 {@code BorderPane} 的 center 上互相替换。
 * 之所以整块换而不是只换正文：版心那条"纸"的宽度是按"一行 34 个字"算死的，
 * 书架需要的是撑满窗口的宽度，两者没法共用同一个容器。
 *
 * <p>界面上只有两种底色：左侧/上下是"面板"，中间那条窄栏是"纸"。
 * 正文的纸不铺满窗口宽度，而是按"一行 34 个字"算出来固定住 ——
 * 一行太长的中文读起来会频繁串行，所以宁可两边留白。
 *
 * <p><b>为什么要开后台线程建索引？</b><br>
 * 因为"扫描全书找章节"这件事的耗时和文件大小成正比。一本 5MB 的小说
 * 大概要几百毫秒，100MB 的合集就要好几秒。如果放在 JavaFX 的应用线程里做，
 * 这几秒内窗口会完全卡死、点不动 —— 用户会以为程序崩了。
 *
 * <p>JavaFX 的约定是：<b>所有界面改动必须在应用线程（FX Application Thread）执行</b>，
 * 耗时计算必须放到别的线程。{@link Task} 就是官方提供的"跨线程搬结果"的工具：
 * {@code call()} 在后台线程跑，{@code setOnSucceeded} 的回调自动回到应用线程。
 *
 * <p><b>存储层的失败不进入业务判断。</b><br>
 * {@link #store} 允许为 null（数据库打不开时程序仍然可用，只是不记进度），
 * 所有持久化调用都走几个私有的 {@code persistXxx} 方法，集中在那里挡掉。
 * 这样"能不能存"这件事只在 6 个地方出现，而不是散落在整个界面逻辑里。
 */
public class ReaderView extends BorderPane {

    /** 「最近打开」菜单最多列几本。 */
    private static final int RECENT_LIMIT = 12;

    /**
     * 导入失败时，明细最多列几条。
     *
     * <p>一个装满损坏文件的文件夹可能失败几百次，全塞进对话框就是一面墙。
     * 与其把界面撑爆，不如只给最前面的几条看看是什么毛病 ——
     * 剩下的失败原因通常是一样的。
     */
    private static final int MAX_FAILURE_LINES = 8;

    /**
     * 版心里一行放多少个字。
     *
     * <p>中文正文的舒适区间大约是 25~40 字一行：字太少要不停回行，读起来碎；
     * 字太多，眼睛从行尾扫回行首时容易看串到下一行。
     *
     * <p>所以正文的宽度<b>不跟着窗口走，而是跟着这个字数和当前字号走</b> ——
     * 窗口拉大时多出来的宽度变成两边的留白，而不是变成更长的行。
     */
    private static final int COLUMN_CHARS = 34;

    /** 版心左右的内边距。和 {@code contentBox} 的 padding 是同一个数，必须一起改。 */
    private static final double COLUMN_PADDING_X = 40;

    // ==================== 依赖 ====================

    private final TxtBookParser parser = new TxtBookParser();

    /** 存储入口，可能为 null —— 表示"这次运行不保存任何东西"。 */
    private final QingduStore store;

    // ==================== 界面控件 ====================

    private final ListView<Chapter> chapterList = new ListView<>();
    private final ListView<Bookmark> bookmarkList = new ListView<>();
    private final TabPane sideTabs = new TabPane();

    /** 版心的外层容器：占满整个视口，只负责把 {@link #contentBox} 居中。 */
    private final VBox columnHost = new VBox();

    /** 正文那条"纸"。宽度由 {@link #applyColumnWidth()} 算出来写进去。 */
    private final VBox contentBox = new VBox();

    private final ScrollPane contentScroll = new ScrollPane();

    /** 全书阅读进度条。只是个显示用的派生值，不进数据库。 */
    private final ProgressBar bookProgressBar = new ProgressBar(0);

    /** 进度条的外框：占满正文区宽度，把进度条居中成"和版心一样宽"。 */
    private final StackPane progressHolder = new StackPane(bookProgressBar);

    private final Label bookTitleLabel = new Label("轻读阅读器");
    private final Label bookMetaLabel = new Label("从菜单「文件 → 打开本地 TXT」开始阅读");
    private final Label statusLabel = new Label("就绪");

    /** 状态栏右侧的"全书 37%"。和左边的长文本分开，是为了让它不被挤掉。 */
    private final Label statusProgressLabel = new Label();

    private final Menu recentMenu = new Menu("最近打开");
    private final Menu themeMenu = new Menu("主题");
    private final ToggleGroup themeGroup = new ToggleGroup();

    /**
     * 阅读态的整个中心区（左侧「目录 / 书签」+ 右边正文）。
     *
     * <p>它和 {@link #bookshelf} 是"中心区"的两种形态，二选一挂上去。
     * 之所以整块换，而不是只换正文：书架上不该出现一个空的目录栏 ——
     * 那看起来像坏了，而不是"还没有书"。
     */
    private SplitPane readerPane;

    /** 书架态。没有打开书时中心区显示的是它。 */
    private BookshelfView bookshelf;

    /**
     * 左侧第三个标签页：全文搜索。
     *
     * <p>它是"当前这本书"的搜索，换书时由 {@link SearchPanel#reset()} 清空 ——
     * 面板自己不知道书什么时候换了，只能由这里通知（见 {@link #applyResult}）。
     */
    private final SearchPanel searchPanel;

    // ==================== 阅读状态 ====================

    private Path currentFile;
    private Book currentBook;
    private List<Chapter> chapters = List.of();
    private ReaderSettings settings;
    private int currentChapterIndex = -1;

    /** 有书签的章节序号，用来在目录里打标记。 */
    private final Set<Integer> bookmarkedChapters = new HashSet<>();

    /**
     * 下一次渲染正文时要高亮的词（从搜索结果跳过来时设置）。
     *
     * <p><b>它是一次性的</b>：被 {@code showChapter} 消费掉之后立刻清空。
     * 不这么做的话，用户从搜索结果跳过去、再点目录里另一章，
     * 那个词会继续高亮 —— 看起来像程序记错了。
     */
    private String highlightTerm;

    /**
     * 进度回写的防抖器。
     *
     * <p>滚动时会连续触发几十上百次 {@code vvalue} 变化，每次都写一次数据库
     * 既浪费又没必要 —— 用户真正在意的是"停下来之后读到哪"。
     * {@link PauseTransition} 的作用就是"等安静 700 毫秒再执行"。
     */
    private final PauseTransition progressDebounce = new PauseTransition(Duration.millis(700));

    /**
     * 是否正在"恢复进度"。
     *
     * <p>用它区分两种章节切换：用户自己点的（从本章开头看起），
     * 和程序恢复上次位置时的（要跳到章内某个位置）。
     * 两者都会经过同一个选中监听器，只能靠这个标记分辨。
     */
    private boolean restoringProgress = false;
    private double restoreRatio = 0;

    // ==================== 构造 ====================

    public ReaderView(QingduStore store) {
        this.store = store;
        this.settings = loadSettingsQuietly();

        // 主题菜单要在 buildTop() 之前建好，否则菜单栏第一次显示时是空的
        buildThemeMenu();

        // 搜索面板要在 buildCenter() 之前建好：它作为左侧第三个标签页的内容被装进去。
        // store 为 null（数据库没打开）时给它 null，面板自己会切成"不可用"的样子
        searchPanel = new SearchPanel(store == null ? null : store.search(), new SearchHost());

        getStyleClass().add("reader-window");
        readerPane = buildCenter();
        setTop(buildTop());
        setBottom(buildStatusBar());

        // 书架的动作全部回调到本类：弹框要跟主题换肤、移除要删数据，
        // 那两件事都只有这里做得对（BookshelfView 的类注释里有说明）
        bookshelf = new BookshelfView(store, new BookshelfView.Actions() {
            @Override
            public void open(Book book) {
                openLibraryBook(book);
            }

            @Override
            public void chooseFile() {
                chooseAndOpen();
            }

            @Override
            public void importFolder() {
                chooseAndImportFolder();
            }

            @Override
            public void remove(Book book) {
                removeFromShelf(book);
            }
        });
        showBookshelf();

        progressDebounce.setOnFinished(e -> persistProgress());
        // 滚动位置变化 → 延迟写库。用户拖动滚动条时这里会连续触发，
        // 但真正落库的只有最后那次，中间的都被 PauseTransition 取消了。
        contentScroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            refreshStatusChapter();
            progressDebounce.playFromStart();
        });

        reloadRecentMenu();
    }

    /**
     * 窗口和场景准备好之后的收尾动作。
     *
     * <p>样式表要装在 {@code Scene} 上，而构造 {@code ReaderView} 时它还没有
     * Scene（Scene 是围绕它创建的），所以只能由外部在组装完之后回调一次。
     * 这是 JavaFX 里很常见的顺序依赖 —— 顺序错了不会报错，
     * 只会看到界面"没换肤"，排查起来很费时间，所以专门留一个方法并写清用途。
     */
    public void onSceneReady() {
        applyTheme();
    }

    // ==================== 菜单栏 ====================

    private VBox buildTop() {
        return new VBox(buildMenuBar(), buildBookInfoBar());
    }

    private MenuBar buildMenuBar() {
        MenuItem openItem = new MenuItem("打开本地 TXT…");
        openItem.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        openItem.setOnAction(e -> chooseAndOpen());

        MenuItem importItem = new MenuItem("导入文件夹…");
        importItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+O"));
        importItem.setOnAction(e -> chooseAndImportFolder());

        MenuItem closeItem = new MenuItem("关闭当前书籍");
        closeItem.setOnAction(e -> closeBook());

        MenuItem exitItem = new MenuItem("退出");
        exitItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Q"));
        exitItem.setOnAction(e -> {
            Window window = getScene() == null ? null : getScene().getWindow();
            if (window != null) {
                window.hide();
            }
        });

        Menu fileMenu = new Menu("文件", null,
                openItem, importItem, recentMenu, new SeparatorMenuItem(), closeItem,
                new SeparatorMenuItem(), exitItem);

        Menu bookmarkMenu = new Menu("书签", null, buildBookmarkMenuItems());

        Menu searchMenu = new Menu("搜索", null, buildSearchMenuItems());

        Menu viewMenu = new Menu("视图", null, buildViewMenuItems());

        MenuItem aboutItem = new MenuItem("关于轻读阅读器");
        aboutItem.setOnAction(e -> showAbout());
        Menu helpMenu = new Menu("帮助", null, aboutItem);

        MenuBar bar = new MenuBar(fileMenu, bookmarkMenu, searchMenu, viewMenu, helpMenu);
        bar.getStyleClass().add("reader-menubar");
        return bar;
    }

    private MenuItem[] buildBookmarkMenuItems() {
        MenuItem add = new MenuItem("在本页添加书签");
        add.setAccelerator(KeyCombination.keyCombination("Shortcut+B"));
        add.setOnAction(e -> addBookmark());

        MenuItem show = new MenuItem("查看书签列表");
        show.setAccelerator(KeyCombination.keyCombination("Shortcut+L"));
        show.setOnAction(e -> sideTabs.getSelectionModel().select(1));

        return new MenuItem[]{add, new SeparatorMenuItem(), show};
    }

    /**
     * 搜索菜单。
     *
     * <p>{@code Shortcut+F} 是各平台都认的"查找"快捷键，用户不用学。
     * 按下去做两件事：切到搜索标签页、把光标放进输入框 ——
     * 只切标签页的话，用户还得再用鼠标点一下输入框才能打字。
     */
    private MenuItem[] buildSearchMenuItems() {
        MenuItem find = new MenuItem("在本书中查找…");
        find.setAccelerator(KeyCombination.keyCombination("Shortcut+F"));
        find.setOnAction(e -> {
            sideTabs.getSelectionModel().select(2);
            searchPanel.focusInput();
        });

        return new MenuItem[]{find};
    }

    private MenuItem[] buildViewMenuItems() {
        MenuItem settingsItem = new MenuItem("阅读设置…");
        settingsItem.setAccelerator(KeyCombination.keyCombination("Shortcut+Comma"));
        settingsItem.setOnAction(e -> openSettingsDialog());

        MenuItem bigger = new MenuItem("增大字号");
        bigger.setAccelerator(KeyCombination.keyCombination("Shortcut+Equals"));
        bigger.setOnAction(e -> changeFontSize(1));

        MenuItem smaller = new MenuItem("减小字号");
        smaller.setAccelerator(KeyCombination.keyCombination("Shortcut+Minus"));
        smaller.setOnAction(e -> changeFontSize(-1));

        return new MenuItem[]{themeMenu, new SeparatorMenuItem(), settingsItem,
                new SeparatorMenuItem(), bigger, smaller};
    }

    /** 主题子菜单：一组单选菜单项。 */
    private void buildThemeMenu() {
        themeMenu.getItems().clear();
        for (Theme theme : Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(theme.displayName());
            item.setToggleGroup(themeGroup);
            item.setUserData(theme);
            item.setSelected(theme == settings.theme());
            item.setOnAction(e -> applySettings(settings.withTheme(theme)));
            themeMenu.getItems().add(item);
        }
    }

    private VBox buildBookInfoBar() {
        bookTitleLabel.getStyleClass().add("reader-book-title");
        bookMetaLabel.getStyleClass().add("reader-book-meta");

        VBox bar = new VBox(4, bookTitleLabel, bookMetaLabel);
        bar.getStyleClass().add("reader-book-bar");
        return bar;
    }

    // ==================== 左侧面板 + 正文区 ====================

    /**
     * 判断这一章上方要不要插一行卷标题分组头，需要就返回那个 Label。
     *
     * <p>只在「本卷第一章」上方返回非空 —— 判据是本章带着卷名、
     * 而上一章没带（或换了一个卷名）。同一卷的后续章节上方就不再重复。
     *
     * <p>为什么卷标题要做成"挂在章节上的分组头"而不是自己占一个目录条目？
     * 因为卷标题这一"章"实际只有二十几个字节（就是标题那一行），
     * 点进去是一片空白。《全职高手》原本就有 3 个这样的空条目。
     * 分章器现在只把它作为分组信息挂到后面的章节上，目录这里再渲染出来。
     *
     * @param list  目录的数据源，用来回看上一章的卷名
     * @param item  当前章
     * @param index 当前章在列表中的位置
     */
    private static Label buildVolumeHeader(ListView<Chapter> list, Chapter item, int index) {
        List<Chapter> items = list.getItems();
        Chapter previous = (index > 0 && index - 1 < items.size()) ? items.get(index - 1) : null;
        // 判断本身在 Chapter 上，那里有单元测试兜着 —— 界面这边只负责画出来
        if (!item.startsNewVolume(previous)) {
            return null;
        }
        Label header = new Label(item.volumeTitle());
        header.getStyleClass().add("reader-volume-header");
        header.setMaxWidth(Double.MAX_VALUE);
        return header;
    }

    private SplitPane buildCenter() {
        chapterList.getStyleClass().add("reader-list");
        chapterList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Chapter item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                // 章节标题。用独立的 Label 而不是 cell 自身的 text，
                // 是为了能在它上面叠一行卷标题分组头。
                Label title = new Label(item.title());
                title.getStyleClass().add("reader-chapter-label");
                title.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(title, Priority.ALWAYS);

                HBox row = new HBox(title);
                row.setAlignment(Pos.CENTER_LEFT);

                // 有书签的章节在标题右边点一个小圆点。
                // 用独立节点而不是往文字里加符号，是为了让标记不影响
                // 标题本身的省略号行为（长标题该截断还是截断）
                if (bookmarkedChapters.contains(item.index())) {
                    Label flag = new Label("●");
                    flag.getStyleClass().add("reader-bookmark-flag");
                    row.getChildren().add(flag);
                }

                // 卷标题分组头：只在"本卷第一章"的上方插一行。
                // 判据是本章带卷名、而上一章没带（或换了一个卷名）——
                // 于是同一卷的后续章节上方不会再重复出现卷头。
                Label volumeHeader = buildVolumeHeader(list, item, getIndex());

                setText(null);
                setContentDisplay(ContentDisplay.LEFT);
                if (volumeHeader == null) {
                    setGraphic(row);
                } else {
                    VBox box = new VBox(2, volumeHeader, row);
                    box.getStyleClass().add("reader-cell-box");
                    setGraphic(box);
                }
            }
        });
        chapterList.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldChapter, newChapter) -> {
                    if (newChapter != null && newChapter != oldChapter) {
                        // 恢复进度时用保存的位置，用户自己点则从章首开始
                        double ratio = restoringProgress ? restoreRatio : 0;
                        // 高亮词只生效一次：取走就清掉，免得用户再点别的章节时还在高亮
                        String highlight = highlightTerm;
                        highlightTerm = null;
                        showChapter(newChapter, ratio, highlight);
                    }
                });

        VBox chapterBox = new VBox(chapterList);
        VBox.setVgrow(chapterList, Priority.ALWAYS);
        chapterBox.getStyleClass().add("reader-side-panel");

        VBox bookmarkBox = buildBookmarkPane();

        Tab chapterTab = new Tab("目录", chapterBox);
        Tab bookmarkTab = new Tab("书签", bookmarkBox);
        Tab searchTab = new Tab("搜索", searchPanel);
        chapterTab.setClosable(false);
        bookmarkTab.setClosable(false);
        searchTab.setClosable(false);
        // 顺序即快捷键里用的下标：0 目录、1 书签、2 搜索（见 buildBookmarkMenuItems 与搜索菜单）
        sideTabs.getTabs().setAll(chapterTab, bookmarkTab, searchTab);
        sideTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sideTabs.getStyleClass().add("reader-tabs");

        contentBox.getStyleClass().add("reader-content");
        contentBox.setPadding(new Insets(32, COLUMN_PADDING_X, 48, COLUMN_PADDING_X));
        contentBox.setSpacing(settings.paragraphSpacing());

        // 版心居中：外层容器占满视口，里面那条"纸"只占固定宽度，
        // 多出来的宽度由对齐方式变成两侧留白。
        // 用对齐来实现居中，比在两边各塞一个"撑开的空白节点"少两个对象，
        // 也不用维护它们的宽度。
        columnHost.setAlignment(Pos.TOP_CENTER);
        columnHost.getChildren().setAll(contentBox);

        contentScroll.getStyleClass().add("reader-content-scroll");
        contentScroll.setFitToWidth(true);
        // 宽度已经由版心算好了，再出现横向滚动条必然是哪里算错了。
        // 直接关掉：宁可裁掉，也不要给用户一个能横着拖的阅读界面。
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setContent(columnHost);

        bookProgressBar.getStyleClass().add("reader-progress-bar");
        bookProgressBar.setFocusTraversable(false);
        progressHolder.setAlignment(Pos.CENTER);
        progressHolder.setVisible(false);
        progressHolder.setManaged(false);

        // 进度条要和版心左右对齐。版心是在 ScrollPane 的"视口"里居中的，
        // 而视口在冒出竖直滚动条时会比正文区窄掉一条滚动条的宽度 ——
        // 外框不补上这段宽度，进度条就会整体偏右几个像素（滚动条一出现/消失
        // 还会跟着跳一下）。
        //
        // 这里直接拿"正文区宽度 - 视口宽度"来补，而不是假设"滚动条宽 10px"：
        // 换主题、换系统缩放、以后改滚动条样式，这个差值都会自己跟上。
        contentScroll.viewportBoundsProperty().addListener((obs, oldBounds, bounds) -> {
            double gap = contentScroll.getWidth() - bounds.getWidth();
            progressHolder.setPadding(new Insets(0, Math.max(0, gap), 0, 0));
        });

        BorderPane readingPane = new BorderPane();
        readingPane.getStyleClass().add("reader-reading-pane");
        readingPane.setCenter(contentScroll);
        readingPane.setBottom(progressHolder);

        applyColumnWidth();

        SplitPane split = new SplitPane(sideTabs, readingPane);
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sideTabs, Boolean.FALSE);
        return split;
    }

    /**
     * 按当前字号算出"版心"的宽度，并同步给正文和进度条。
     *
     * <p>宽度按「一行多少字」算，而不是写死像素。原因在调字号的时候最明显：
     * 用户把字号调大，如果宽度不动，每行能放的字就会从 34 个掉到 24 个，
     * 行变得又短又碎。让宽度跟着字号一起长，行宽（字数）才能保持稳定。
     *
     * <p>因此这个方法是"改设置之后必须调一次"的地方，和字号是一对。
     */
    private void applyColumnWidth() {
        double width = Math.round(settings.fontSize() * COLUMN_CHARS + COLUMN_PADDING_X * 2);
        contentBox.setPrefWidth(width);
        contentBox.setMaxWidth(width);
        bookProgressBar.setPrefWidth(width);
        bookProgressBar.setMaxWidth(width);
    }

    private VBox buildBookmarkPane() {
        bookmarkList.getStyleClass().add("reader-list");
        bookmarkList.setPlaceholder(emptyHint("还没有书签。\n读到想记住的地方，按 Ctrl + B 加一条。"));
        bookmarkList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Bookmark item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(bookmarkCell(item));
            }
        });
        // 双击跳转 —— 列表类的控件里这是用户默认会去试的交互
        bookmarkList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                gotoSelectedBookmark();
            }
        });
        bookmarkList.setContextMenu(buildBookmarkContextMenu());

        Button addButton = new Button("＋ 当前页");
        addButton.setOnAction(e -> addBookmark());

        Button removeButton = new Button("删除");
        removeButton.setOnAction(e -> deleteSelectedBookmark());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(6, addButton, removeButton, spacer);
        toolbar.setPadding(new Insets(8, 10, 6, 10));

        VBox box = new VBox(toolbar, bookmarkList);
        VBox.setVgrow(bookmarkList, Priority.ALWAYS);
        box.getStyleClass().add("reader-side-panel");
        return box;
    }

    private Node bookmarkCell(Bookmark bookmark) {
        Label title = new Label("第 " + (bookmark.chapterIndex() + 1) + " 章    "
                + safeTitle(bookmark.chapterTitle()));
        title.getStyleClass().add("reader-bookmark-title");
        title.setWrapText(true);

        Label meta = new Label("章内 " + Math.round(bookmark.scrollRatio() * 100) + "%    ·    "
                + formatTime(bookmark.createdAt())
                + (bookmark.note() == null ? "" : "    ·    " + bookmark.note()));
        meta.getStyleClass().add("reader-bookmark-meta");

        VBox box = new VBox(2, title, meta);
        box.getStyleClass().add("reader-bookmark-cell");
        return box;
    }

    private ContextMenu buildBookmarkContextMenu() {
        MenuItem gotoItem = new MenuItem("跳转到这条书签");
        gotoItem.setOnAction(e -> gotoSelectedBookmark());
        MenuItem deleteItem = new MenuItem("删除这条书签");
        deleteItem.setOnAction(e -> deleteSelectedBookmark());
        return new ContextMenu(gotoItem, deleteItem);
    }

    /**
     * 状态栏：左边一句话说"读到哪"，右边单独放全书进度。
     *
     * <p>两段分开而不是拼成一整条字符串，是因为左边的文字长短差别很大
     * （章节标题可以很长）。拼在一起的话，标题一长就把右边的百分比挤出窗口。
     * 拆成两个节点后，左边可以自己收缩并显示省略号，右边永远留在原位。
     */
    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("reader-status-text");
        // 允许被压缩：位置信息很长时（章节标题可以很长）由它退让，
        // 并且自动显示省略号，而不是把右边的百分比顶出窗口
        statusLabel.setMinWidth(0);
        statusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);

        statusProgressLabel.getStyleClass().add("reader-status-progress");
        // 反过来，右边这一小段不允许被压缩
        statusProgressLabel.setMinWidth(Region.USE_PREF_SIZE);

        // 用一段"撑开的空白"把百分比推到最右。
        // 本来可以直接给 statusLabel 设 hgrow，但那要依赖 Label 自身能伸展；
        // 这里用一个 prefWidth 为 0 的 Region，它只会朝"变大"这个方向走，
        // 不会因为任何默认尺寸策略而卡住 —— 少一个需要验证的假设。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(12, statusLabel, spacer, statusProgressLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("reader-statusbar");
        return bar;
    }

    /** 列表为空时的占位提示。ListView 自带 placeholder 机制，不必自己叠一层。 */
    private Label emptyHint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("reader-empty-hint");
        label.setWrapText(true);
        return label;
    }

    /**
     * 切到书架态。
     *
     * <p>没有打开任何书的时候，主界面是书架，而不是一张写死的欢迎页。
     * 理由很实际：用户第二次打开程序时想看的是"我上次读到哪本了"，
     * 一句"按 Ctrl+O 打开文件"对他是零信息量的；而对第一次用的人，
     * 书架的空状态里本来就有引导和按钮，并不比欢迎页差。
     *
     * <p>它同时负责把"书籍信息条"和状态栏恢复成无书的样子 ——
     * 否则关掉一本书之后，上一本的书名还会赖在上面。
     */
    private void showBookshelf() {
        setCenter(bookshelf);
        bookshelf.refresh();

        bookTitleLabel.setText("轻读阅读器");
        bookMetaLabel.setText(store == null
                ? "本地数据存储未启用：本次阅读不会被记录"
                : "点击一本书打开    ·    「文件 → 导入文件夹…」批量添加");
        statusLabel.setText("就绪");
        setStatusProgressVisible(false);
        statusProgressLabel.setText("");
        applyWindowTitle();
    }

    // ==================== 打开一本书 ====================

    /** 弹出文件选择框，选中后打开。 */
    public void chooseAndOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要打开的 TXT 小说");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("全部文件", "*.*"));
        File chosen = chooser.showOpenDialog(windowOrNull());
        if (chosen != null) {
            open(chosen.toPath());
        }
    }

    // ==================== 书架与批量导入 ====================

    /** 弹出文件夹选择框，选中后整个导入。 */
    private void chooseAndImportFolder() {
        if (store == null) {
            statusLabel.setText("导入功能不可用：本地数据库没有打开");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择装着 TXT 小说的文件夹");
        File folder = chooser.showDialog(windowOrNull());
        if (folder != null) {
            importFolder(folder.toPath());
        }
    }

    /**
     * 把一个文件夹里的 TXT 全部登记进书库。
     *
     * <p>和 {@link #open(Path)} 一样放后台线程，理由也一样：
     * 扫一个装满小说的文件夹要读几百个文件的头、查几百次库，
     * 放在应用线程里界面会僵住。这里比打开单本书更明显 ——
     * 用户点的动作是"导入一个文件夹"，天然预期要等一下，
     * 但"等一下"应该是状态栏在变，而不是窗口点不动。
     *
     * <p>顺带说明：导入<b>不建章节索引</b>，所以它和书的体积无关。
     * 细节见 {@link BookImporter} 的类注释。
     *
     * @param folder 要导入的文件夹
     */
    public void importFolder(Path folder) {
        if (store == null) {
            statusLabel.setText("导入功能不可用：本地数据库没有打开");
            return;
        }
        statusLabel.setText("正在扫描：" + folder.getFileName() + " …");

        BookImporter importer = new BookImporter(store);
        Task<BookImporter.Report> task = new Task<>() {
            @Override
            protected BookImporter.Report call() {
                return importer.importFolder(folder);
            }
        };
        task.setOnSucceeded(event -> {
            BookImporter.Report report = task.getValue();
            bookshelf.refresh();
            reloadRecentMenu();
            showImportReport(report);
        });
        task.setOnFailed(event -> {
            statusLabel.setText("导入失败");
            showError("导入文件夹失败", task.getException());
        });

        Thread worker = new Thread(task, "qingdu-importer");
        worker.setDaemon(true);
        worker.start();
    }

    /** 导入结束后给个交代：状态栏一句话汇总；有失败才弹框说明是哪些文件。 */
    private void showImportReport(BookImporter.Report report) {
        statusLabel.setText(report.summary());
        if (report.failures().isEmpty()) {
            return;
        }
        Alert alert = themedAlert(Alert.AlertType.WARNING);
        alert.setHeaderText(report.summary());
        alert.setContentText("下面这些文件没能导入：\n\n" + failureLines(report.failures()));
        alert.showAndWait();
    }

    private static String failureLines(List<String> failures) {
        if (failures.size() <= MAX_FAILURE_LINES) {
            return String.join("\n", failures);
        }
        List<String> head = failures.subList(0, MAX_FAILURE_LINES);
        return String.join("\n", head)
                + "\n…… 还有 " + (failures.size() - MAX_FAILURE_LINES) + " 个";
    }

    /**
     * 打开书架（或「最近打开」）里的一本书，先确认文件还在。
     *
     * <p>这两个入口是同一件事 —— 都是"打开一本已经在库里的书"，
     * 所以共用同一段逻辑：文件没了就给出解释并提供"移除记录"的选项，
     * 而不是让解析器抛一句"文件不存在"了事。
     */
    private void openLibraryBook(Book book) {
        Path path = book.filePath();
        if (path == null || !Files.isRegularFile(path)) {
            promptMissingBook(book);
            return;
        }
        open(path);
    }

    /** 文件已经不在了：说清楚原位置，并给一个"从书架移除"的选择。 */
    private void promptMissingBook(Book book) {
        Alert alert = themedAlert(Alert.AlertType.CONFIRMATION);
        alert.setHeaderText("找不到这本书了");
        alert.setContentText("《" + book.title() + "》原来的位置是：\n"
                + book.filePath() + "\n\n文件可能被移动、改名或删除了。\n"
                + "要把这条记录从书架里移除吗？");
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.OK) {
            forgetBook(book);
        }
    }

    /** 从书架移除一本书（会连带删掉它的进度和书签，靠外键级联）。 */
    private void removeFromShelf(Book book) {
        Alert alert = themedAlert(Alert.AlertType.CONFIRMATION);
        alert.setHeaderText("把《" + book.title() + "》从书架移除？");
        alert.setContentText("这本书的阅读进度和书签会一起删掉。\n"
                + "书文件本身不会被删除。");
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        forgetBook(book);
    }

    /** 真正执行删除 + 刷新所有显示着这本书的地方。 */
    private void forgetBook(Book book) {
        if (store == null) {
            return;
        }
        try {
            store.books().forget(book.id());
            bookshelf.refresh();
            reloadRecentMenu();
            statusLabel.setText("已从书架移除：" + book.title());
        } catch (StoreException e) {
            showError("移除失败", e);
        }
    }

    /**
     * 打开指定文件。
     *
     * <p>解析过程放在后台线程，界面只负责显示"正在解析"。
     */
    public void open(Path file) {
        // 关掉当前书之前先把进度存下来，否则用户点了另一本书，
        // 刚才那本的滚动位置就丢了
        persistProgress();

        // 先拦一道：目前只有 TXT 解析器。
        // 如果不拦，EPUB 会被当成 TXT 硬读 —— 它的本质是 zip 压缩包，
        // 读出来是一堆二进制乱码，用户只会以为"这软件坏了"。
        // 与其给出错误的结果，不如给出清楚的解释。
        BookFormat format = BookFormat.fromFileName(file.getFileName().toString());
        if (format != BookFormat.TXT) {
            showUnsupportedFormat(file, format);
            return;
        }

        currentFile = file;
        statusLabel.setText("正在解析：" + file.getFileName() + " …");

        Task<LoadResult> task = new Task<>() {
            @Override
            protected LoadResult call() throws Exception {
                Book book = parser.parseMetadata(file);
                TxtChapterSplitter.Report report = parser.index(file, book.id());
                CharsetDetector.Detection detection = CharsetDetector.detect(file);
                return new LoadResult(book, report, detection);
            }
        };
        task.setOnSucceeded(event -> applyResult(task.getValue()));
        task.setOnFailed(event -> {
            statusLabel.setText("打开失败");
            showError("无法打开这个文件", task.getException());
        });

        Thread worker = new Thread(task, "qingdu-indexer");
        // 设为守护线程：用户直接关窗口时，不因为这个后台任务还没跑完而卡住退出
        worker.setDaemon(true);
        worker.start();
    }

    /** 选到了当前还不支持的格式，给出解释而不是抛异常。 */
    private void showUnsupportedFormat(Path file, BookFormat format) {
        String shown = (format == BookFormat.UNKNOWN)
                ? file.getFileName().toString()
                : format.name() + "（" + format.extension() + "）";

        Alert alert = themedAlert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("暂时还打不开 " + shown + " 格式");
        alert.setContentText("""
                目前只支持 TXT 纯文本。

                原因：EPUB 等格式需要另一整套解析链路（zip 解包 → OPF 清单解析 → XHTML 提纯），
                计划在阶段 4 实现。现在硬读只会得到一堆乱码，所以先不做。

                你可以先打开一本 TXT 小说试试。""");
        alert.showAndWait();
    }

    private void applyResult(LoadResult result) {
        currentBook = result.book();
        chapters = result.report().chapters();
        currentChapterIndex = -1;

        // 数据齐了才把中心区切回阅读态。切早了会先闪一下空白的目录栏
        setCenter(readerPane);

        bookTitleLabel.setText(currentBook.title());
        bookMetaLabel.setText(buildBookMeta(result));
        applyWindowTitle();

        // 【顺序很重要】必须先读出上次的进度，再写书目信息。
        // 反过来的话，写入时的"默认进度"（第 0 章、0%）会把已存的位置覆盖掉，
        // 然后读回来的就是刚被清空的值 —— 现象是"进度永远回到第一章"。
        ReadingProgress saved = findSavedProgress(currentBook.id());
        persistBookRecord(result, saved);

        chapterList.getSelectionModel().clearSelection();
        chapterList.getItems().setAll(chapters);
        reloadBookmarks();

        if (chapters.isEmpty()) {
            statusLabel.setText("没有识别出任何章节");
            // 上一本书的进度条还挂在那里就露馅了，收起来
            setStatusProgressVisible(false);
            statusProgressLabel.setText("");
            return;
        }

        int target = clamp(saved == null ? 0 : saved.chapterIndex(), 0, chapters.size() - 1);

        // select() 会同步触发选中监听器，所以这个标记在监听器里一定读得到
        restoringProgress = true;
        restoreRatio = (saved == null) ? 0 : saved.scrollRatio();
        chapterList.getSelectionModel().select(target);
        restoringProgress = false;
        restoreRatio = 0;

        // 换书了：搜索面板里还留着上一本书的结果和整本书的字节缓存，必须清掉
        searchPanel.reset();
        highlightTerm = null;

        reloadRecentMenu();
        showRestoreNotice(saved);
    }

    private String buildBookMeta(LoadResult result) {
        Book book = result.book();
        String author = book.authorName().orElse("作者未知");
        return author + "    ·    " + book.fileName()
                + "    ·    " + result.detection().displayName()
                + "（" + result.detection().reason() + "）";
    }

    /** 关掉当前书，回到书架。 */
    public void closeBook() {
        persistProgress();
        currentFile = null;
        currentBook = null;
        chapters = List.of();
        currentChapterIndex = -1;
        bookmarkedChapters.clear();
        highlightTerm = null;
        chapterList.getItems().clear();
        bookmarkList.getItems().clear();
        contentBox.getChildren().clear();
        // 搜索面板缓存着整本书的字节（10 MB 量级）和这本书的搜索结果，一并释放
        searchPanel.reset();
        // 「无书时界面长什么样」只在 showBookshelf() 里定义一次，
        // 这里不重复设置书名条和状态栏 —— 两处各写一份，早晚会走散
        showBookshelf();
        reloadRecentMenu();
    }

    // ==================== 显示正文 ====================

    /**
     * 显示某一章。
     *
     * @param restoreRatio 章内要恢复到的滚动比例；0 表示从章首开始
     * @param highlight    要高亮的词（从搜索结果跳过来时传），可以为 null
     */
    private void showChapter(Chapter chapter, double restoreRatio, String highlight) {
        if (currentFile == null) {
            return;
        }
        try {
            // 读单章很快（只 seek 读那一小段字节），放在应用线程里也不会卡顿，
            // 所以这里刻意不做异步 —— 少一层复杂度，翻页响应还更即时
            Chapter loaded = parser.loadChapter(currentFile, chapter);
            contentBox.setAlignment(Pos.TOP_LEFT);
            contentBox.getChildren().setAll(ChapterRenderer.render(loaded, settings, highlight));
            currentChapterIndex = chapter.index();
            if (highlight == null || highlight.isEmpty()) {
                restoreScroll(restoreRatio);
            } else {
                // 从搜索跳过来时，"读到哪里"由命中位置决定，而不是章首 ——
                // 否则用户看到的是这一章的开头，还得自己往下找
                scrollToHighlight();
            }
            refreshStatusChapter();
            // 章节变了就是"读到了这里"，立刻排一次回写
            progressDebounce.playFromStart();
        } catch (BookParseException e) {
            showError("读取章节失败", e);
        }
    }

    /**
     * 把视口滚到第一个高亮词那里。
     *
     * <p>做法是先让布局跑一遍（不跑的话节点尺寸还是 0，算不出位置），
     * 再把那个节点在场景里的位置换算回 {@code contentBox} 的坐标系，
     * 最后折算成滚动条的比例值。
     *
     * <p>用"命中位置 - 视口高度的 30%"当目标，是让命中词落在屏幕<b>偏上</b>的位置 ——
     * 正中间往上一点，前后文都能看到一点，比正对中线更符合阅读习惯。
     *
     * <p>整段包在 try 里：定位失败最多是"停在章首"，用户自己滚一下就行，
     * 不值得为一个装饰性行为把阅读流程弄崩。
     */
    private void scrollToHighlight() {
        contentScroll.setVvalue(0);
        Platform.runLater(() -> {
            try {
                contentScroll.applyCss();
                contentScroll.layout();
                Node first = contentBox.lookup(".reader-highlight");
                if (first == null) {
                    return;
                }
                Bounds inContent = contentBox.sceneToLocal(first.localToScene(first.getBoundsInLocal()));
                double viewportHeight = contentScroll.getViewportBounds().getHeight();
                double scrollable = contentBox.getHeight() - viewportHeight;
                double y = inContent.getMinY();
                if (scrollable <= 0 || !Double.isFinite(y)) {
                    return;
                }
                double ratio = (y - viewportHeight * 0.3) / scrollable;
                contentScroll.setVvalue(Math.max(0, Math.min(1, ratio)));
            } catch (RuntimeException ignored) {
                // 定位不到就停在章首，不影响阅读
            }
        });
    }

    /**
     * 恢复滚动位置。
     *
     * <p>关键在 {@code applyCss() + layout()} 这两行：
     * 刚刚把段落塞进 {@code contentBox}，此时 {@code ScrollPane} 还不知道
     * 内容有多高 —— 它当前的"可滚动范围"是 0，这时设 {@code vvalue}
     * 会被直接夹回 0，现象就是"进度明明存了，打开还是从头开始"。
     * 手动跑一次布局把尺寸算出来，再设值就稳了。
     *
     * <p>后面那句 {@code Platform.runLater} 是保险：布局偶尔会晚一拍
     * （比如字体还没解析完导致行高变化），补设一次。值相同时
     * 赋值不产生任何副作用，所以重复设一次没有代价。
     */
    private void restoreScroll(double ratio) {
        double target = Math.max(0, Math.min(1, ratio));
        if (target <= 0) {
            contentScroll.setVvalue(0);
            return;
        }
        contentScroll.applyCss();
        contentScroll.layout();
        contentScroll.setVvalue(target);
        Platform.runLater(() -> contentScroll.setVvalue(target));
    }

    /** 滚动或翻章时刷新状态栏里的位置信息，以及底部的全书进度条。 */
    private void refreshStatusChapter() {
        if (currentChapterIndex < 0 || currentChapterIndex >= chapters.size()) {
            setStatusProgressVisible(false);
            statusProgressLabel.setText("");
            return;
        }
        Chapter chapter = chapters.get(currentChapterIndex);
        statusLabel.setText(String.format("第 %d / %d 章    ·    %s    ·    章内 %d%%    ·    本章 %,d 字节",
                chapter.index() + 1, chapters.size(), chapter.title(),
                Math.round(contentScroll.getVvalue() * 100), chapter.byteLength()));

        double whole = wholeBookRatio();
        bookProgressBar.setProgress(whole);
        statusProgressLabel.setText("全书 " + Math.round(whole * 100) + "%");
        setStatusProgressVisible(true);
    }

    /**
     * 全书阅读进度，0~1。
     *
     * <p><b>按字节算，不按"第几章 / 共几章"算。</b>
     * 章节长短差别很大，按章数算会出现"读了 90% 的章节数，其实只读了 50% 的字"
     * 这种跳动。字节数虽然不完全等于字数（标点、换行也算），但它是单调连续的，
     * 进度条走起来是平滑的。
     *
     * <p>分子是"已读到的字节位置"：当前章起点 + 章内比例 × 本章字节数。
     * 章内比例用的就是存储里的那个 scrollRatio，所以这里不需要额外的数据。
     *
     * <p><b>它是纯派生值，不进数据库。</b>
     * 存进去只会多出一份可能和真实位置对不上的数据（改字号、换窗口大小都会动它），
     * 而现有的「章节序号 + 章内比例」已经能唯一确定读到哪了 ——
     * 能算出来的东西就不要存，这是避免数据不一致最省事的办法。
     */
    private double wholeBookRatio() {
        if (currentChapterIndex < 0 || chapters.isEmpty()) {
            return 0;
        }
        // 用最后一章的结束位置当"全书长度"。
        // 注意它不等于文件大小：只算被切进章节的部分，章节目录之前的
        // 前言、附录不会被算进去 —— 对"读了多少正文"这个语义来说更准确。
        long total = chapters.get(chapters.size() - 1).endOffset();
        if (total <= 0) {
            return 0;
        }
        Chapter chapter = chapters.get(currentChapterIndex);
        long done = chapter.startOffset()
                + Math.round(contentScroll.getVvalue() * chapter.byteLength());
        return Math.max(0, Math.min(1, (double) done / total));
    }

    /** 进度条只在"有书在看"的时候出现。收起来时必须连着 managed 一起设，
     *  否则它虽然看不见，却还占着 3px 的高度。 */
    private void setStatusProgressVisible(boolean visible) {
        progressHolder.setVisible(visible);
        progressHolder.setManaged(visible);
    }

    /** 恢复进度后给用户一句提示，否则用户会以为"怎么一打开就在中间"。 */
    private void showRestoreNotice(ReadingProgress saved) {
        if (saved == null || !saved.started()) {
            return;
        }
        statusLabel.setText(String.format("已恢复到上次阅读位置：第 %d 章  ·  章内 %s",
                saved.chapterIndex() + 1, saved.percentLabel()));
    }

    // ==================== 阅读进度 ====================

    private ReadingProgress findSavedProgress(String bookId) {
        if (store == null) {
            return null;
        }
        try {
            return store.books().findProgress(bookId).orElse(null);
        } catch (StoreException e) {
            warnOnce("读取阅读进度失败", e);
            return null;
        }
    }

    /**
     * 把当前阅读位置立刻写库。
     *
     * <p>这是唯一对外暴露的"收尾"接口，{@code QingduApplication.stop()} 会调它 ——
     * 用户关窗口时防抖器可能还欠着一次没执行，不补这一下就丢最后一段进度。
     */
    public void flushProgress() {
        progressDebounce.stop();
        persistProgress();
    }

    private void persistProgress() {
        if (store == null || currentBook == null || currentChapterIndex < 0) {
            return;
        }
        try {
            String title = (currentChapterIndex < chapters.size())
                    ? chapters.get(currentChapterIndex).title() : null;
            store.books().saveProgress(new ReadingProgress(
                    currentBook.id(), currentChapterIndex, title,
                    contentScroll.getVvalue(), System.currentTimeMillis()));
        } catch (StoreException e) {
            // 存进度失败只是一件小事，绝不能因此打断阅读。
            // 状态栏说一句就够了，下一次翻章时它自然会被覆盖掉。
            statusLabel.setText("保存阅读进度失败（不影响阅读）：" + e.getMessage());
            System.err.println("[轻读] 保存阅读进度失败：" + e);
        }
    }

    /**
     * 把书的元信息写进库（也就是"加入最近打开"）。
     *
     * <p>{@code keep} 是上次读到的位置。之所以要把它一起传进来，
     * 是因为 {@code save()} 写的是一整行 —— 不显式带上旧位置的话，
     * 就会用默认的第 0 章把它覆盖掉。
     * 最后阅读时间则统一刷新成"现在"：打开一本书本身就是一次阅读行为。
     */
    private void persistBookRecord(LoadResult result, ReadingProgress keep) {
        if (store == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ReadingProgress position = (keep == null)
                ? new ReadingProgress(currentBook.id(), 0, null, 0, now)
                : new ReadingProgress(currentBook.id(), keep.chapterIndex(),
                        keep.chapterTitle(), keep.scrollRatio(), now);
        try {
            store.books().save(currentBook, chapters.size(), position);
        } catch (StoreException e) {
            warnOnce("记录这本书失败", e);
        }
    }

    // ==================== 书签 ====================

    private void addBookmark() {
        if (currentBook == null || currentChapterIndex < 0) {
            statusLabel.setText("先打开一本书再添加书签");
            return;
        }
        if (store == null) {
            statusLabel.setText("书签功能不可用：本地数据库没有打开");
            return;
        }
        double ratio = contentScroll.getVvalue();
        String title = (currentChapterIndex < chapters.size())
                ? chapters.get(currentChapterIndex).title() : null;

        try {
            Optional<Bookmark> existing = store.bookmarks()
                    .findNear(currentBook.id(), currentChapterIndex, ratio);
            if (existing.isPresent()) {
                // 已经有一条了就不要再插一条，直接把用户带到那条上。
                // 用状态栏提示而不是弹窗 —— 这只是个"哦，知道了"，不值得打断阅读
                selectBookmarkInList(existing.get());
                sideTabs.getSelectionModel().select(1);
                statusLabel.setText("当前位置已经有书签了，已为你定位到那条");
                return;
            }
            Bookmark created = store.bookmarks().add(Bookmark.newOne(
                    currentBook.id(), currentChapterIndex, title, ratio, null));
            reloadBookmarks();
            selectBookmarkInList(created);
            sideTabs.getSelectionModel().select(1);
            statusLabel.setText("已添加书签：" + created.summary());
        } catch (StoreException e) {
            showError("添加书签失败", e);
        }
    }

    private void gotoSelectedBookmark() {
        Bookmark selected = bookmarkList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("先选中一条书签");
            return;
        }
        gotoBookmark(selected);
    }

    private void gotoBookmark(Bookmark bookmark) {
        if (currentBook == null || chapters.isEmpty()) {
            return;
        }
        int target = clamp(bookmark.chapterIndex(), 0, chapters.size() - 1);
        if (target == currentChapterIndex) {
            // 同一章内，直接滚过去就行，不必重新渲染
            restoreScroll(bookmark.scrollRatio());
        } else {
            restoringProgress = true;
            restoreRatio = bookmark.scrollRatio();
            chapterList.getSelectionModel().select(target);
            restoringProgress = false;
            restoreRatio = 0;
        }
        statusLabel.setText("已跳转到：" + bookmark.summary());
    }

    private void deleteSelectedBookmark() {
        Bookmark selected = bookmarkList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("先选中一条书签");
            return;
        }
        if (store == null) {
            return;
        }
        try {
            store.bookmarks().delete(selected.id());
            reloadBookmarks();
            statusLabel.setText("已删除书签：" + selected.summary());
        } catch (StoreException e) {
            showError("删除书签失败", e);
        }
    }

    private void reloadBookmarks() {
        if (store == null || currentBook == null) {
            bookmarkList.getItems().clear();
            bookmarkedChapters.clear();
            return;
        }
        try {
            List<Bookmark> list = store.bookmarks().list(currentBook.id());
            bookmarkList.getItems().setAll(list);
            bookmarkedChapters.clear();
            for (Bookmark bookmark : list) {
                bookmarkedChapters.add(bookmark.chapterIndex());
            }
            // 目录里的书签小圆点需要重画
            chapterList.refresh();
        } catch (StoreException e) {
            warnOnce("读取书签失败", e);
        }
    }

    private void selectBookmarkInList(Bookmark bookmark) {
        for (Bookmark item : bookmarkList.getItems()) {
            if (item.id() == bookmark.id()) {
                bookmarkList.getSelectionModel().select(item);
                bookmarkList.scrollTo(item);
                return;
            }
        }
    }

    // ==================== 主题与阅读设置 ====================

    /**
     * 应用一份新的阅读设置：换肤、改排版、立刻落库。
     *
     * <p>三件事的先后顺序有讲究：<b>先改设置字段，再重画正文</b>。
     * 反过来的话，重画时用的还是旧设置，用户会看到"我先改了字号，
     * 界面过一会儿才跟上"，或者干脆没跟上。
     */
    private void applySettings(ReaderSettings newSettings) {
        ReaderSettings previous = this.settings;
        this.settings = (newSettings == null) ? ReaderSettings.defaults() : newSettings;

        if (previous.theme() != settings.theme()) {
            applyTheme();
        }
        syncThemeMenuSelection();
        persistSettings();
        // 字号可能变了，版心宽度要跟着重算（必须排在重画正文之前）
        applyColumnWidth();

        // 段间距是容器属性，字体字号是每个文本节点的行内样式 ——
        // 后者必须重新渲染才能生效，所以这里重新加载一次当前章。
        // 单章的解码只要几毫秒，比维护一套"就地改样式"的逻辑划算得多。
        if (currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            double ratio = contentScroll.getVvalue();
            // 这里刻意不高亮：改字号是"排版"动作，不该把搜索结果的高亮又带回来，
            // 否则用户早就翻到别处了，改一下字号突然又冒出几个高亮词
            showChapter(chapters.get(currentChapterIndex), ratio, null);
        }
    }

    private void changeFontSize(int delta) {
        int next = settings.fontSize() + delta;
        if (next == settings.fontSize()) {
            statusLabel.setText("字号已经是" + (delta > 0 ? "最大" : "最小")
                    + "（" + settings.fontSize() + " px）");
            return;
        }
        applySettings(settings.withFontSize(next));
        statusLabel.setText("字号：" + settings.fontSize() + " px");
    }

    private void openSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(settings, windowOrNull());
        dialog.showAndWait().ifPresent(this::applySettings);
    }

    private void applyTheme() {
        ThemeStyles.apply(getScene(), settings.theme());
    }

    private void syncThemeMenuSelection() {
        if (themeMenu.getItems().isEmpty()) {
            buildThemeMenu();
            return;
        }
        for (MenuItem item : themeMenu.getItems()) {
            if (item instanceof RadioMenuItem radio && radio.getUserData() instanceof Theme theme) {
                radio.setSelected(theme == settings.theme());
            }
        }
    }

    private ReaderSettings loadSettingsQuietly() {
        if (store == null) {
            return ReaderSettings.defaults();
        }
        try {
            return store.settings().loadSettings();
        } catch (StoreException e) {
            System.err.println("[轻读] 读取阅读设置失败，改用默认设置：" + e);
            return ReaderSettings.defaults();
        }
    }

    private void persistSettings() {
        if (store == null) {
            return;
        }
        try {
            store.settings().saveSettings(settings);
        } catch (StoreException e) {
            statusLabel.setText("保存阅读设置失败：" + e.getMessage());
            System.err.println("[轻读] 保存阅读设置失败：" + e);
        }
    }

    // ==================== 最近打开 ====================

    private void reloadRecentMenu() {
        recentMenu.getItems().clear();
        if (store == null) {
            MenuItem unavailable = new MenuItem("（本地数据库未启用）");
            unavailable.setDisable(true);
            recentMenu.getItems().add(unavailable);
            return;
        }

        List<RecentBook> recent;
        try {
            recent = store.books().recent(RECENT_LIMIT);
        } catch (StoreException e) {
            warnOnce("读取最近打开失败", e);
            return;
        }

        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem("（还没有读过任何书）");
            empty.setDisable(true);
            recentMenu.getItems().add(empty);
            return;
        }

        for (RecentBook item : recent) {
            MenuItem menuItem = new MenuItem(item.book().title() + "    —    " + item.describe());
            menuItem.setOnAction(e -> openRecent(item));
            recentMenu.getItems().add(menuItem);
        }

        recentMenu.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem("清空最近打开");
        clear.setOnAction(e -> clearRecent());
        recentMenu.getItems().add(clear);
    }

    /**
     * 打开「最近打开」里的一项。
     *
     * <p>它和点击书架卡片是同一件事 —— 都是"打开一本已经在库里的书"，
     * 所以共用 {@link #openLibraryBook(Book)}：文件已经不在了的话，
     * 那里会给出解释并提供"移除记录"的选择，而不是让解析器抛一句
     * "文件不存在"了事。移动硬盘没插、文件被删、目录被重命名都会走到那条路。
     */
    private void openRecent(RecentBook recent) {
        openLibraryBook(recent.book());
    }

    private void clearRecent() {
        if (store == null) {
            return;
        }
        Alert alert = themedAlert(Alert.AlertType.CONFIRMATION);
        alert.setHeaderText("清空最近打开列表？");
        alert.setContentText("这会一并清除这些书的阅读进度和书签。\n"
                + "书籍文件本身不会被删除。");
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        try {
            int removed = store.books().forgetAll();
            reloadRecentMenu();
            statusLabel.setText("已清空 " + removed + " 条阅读记录");
        } catch (StoreException e) {
            showError("清空失败", e);
        }
    }

    // ==================== 对话框 ====================

    /**
     * 造一个跟随当前主题的对话框。
     *
     * <p>对话框有自己的 Scene，不会继承主窗口的样式表，
     * 所以每次都要手动把主题装上去 —— 否则夜间模式下会突然弹出一个惨白的框。
     * 统一走这个工厂方法，就不会有哪一处漏掉。
     */
    private Alert themedAlert(Alert.AlertType type) {
        Alert alert = new Alert(type, null, ButtonType.OK);
        alert.setTitle("轻读阅读器");
        ThemeStyles.apply(alert.getDialogPane(), settings.theme());
        return alert;
    }

    private void showError(String header, Throwable error) {
        Alert alert = themedAlert(Alert.AlertType.ERROR);
        alert.setHeaderText(header);
        // 只显示一句话，不把 Java 堆栈甩给用户；堆栈留给日志
        alert.setContentText(error == null ? "未知错误" : String.valueOf(error.getMessage()));
        alert.showAndWait();
        if (error != null) {
            error.printStackTrace();
        }
    }

    private void showAbout() {
        Alert alert = themedAlert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("轻读阅读器 0.2.0");
        alert.setContentText("""
                一个本地优先的中文小说阅读器。

                特性：
                · 自动识别 GBK / UTF-8 / UTF-16 编码
                · 三重校验的章节切分
                · 按字节偏移量按需加载正文
                · 阅读进度与书签自动保存
                · 全书全文搜索（Ctrl + F），结果可点击跳转并高亮
                · 日间 / 护眼 / 羊皮纸 / 夜间 四种主题

                数据位置：""" + (store == null ? "（本次运行未启用）" : store.databaseFile())
                + """

                本软件只读取你自己合法持有的本地文件，不提供任何书源或联网下载功能。""");
        alert.showAndWait();
    }

    /** 持久化失败时的提示：写日志，并且只在状态栏说一次，不打断阅读。 */
    private void warnOnce(String what, StoreException error) {
        statusLabel.setText(what + "：" + error.getMessage());
        System.err.println("[轻读] " + what + "：" + error);
    }

    // ==================== 搜索面板的回调 ====================

    /**
     * 搜索面板要用的那几件事。
     *
     * <p>写成内部类而不是让 {@link SearchPanel} 直接持有 {@code ReaderView}：
     * 面板因此只认识一个四方法的接口，不必知道这个界面的其余一千多行 ——
     * 这两者的耦合方向就只有一个（面板 → 宿主），反过来没有。
     */
    private final class SearchHost implements SearchPanel.Host {

        @Override
        public Book currentBook() {
            return currentBook;
        }

        @Override
        public Path currentFile() {
            return currentFile;
        }

        @Override
        public List<Chapter> chapters() {
            return chapters;
        }

        @Override
        public void jumpToChapter(int chapterIndex, String highlight) {
            if (chapterIndex < 0 || chapterIndex >= chapters.size()) {
                return;
            }
            if (chapterList.getSelectionModel().getSelectedIndex() == chapterIndex) {
                // 已经在同一章：选中项没变化，监听器不会触发，
                // 只能直接渲染一次，否则点了结果界面一动不动
                showChapter(chapters.get(chapterIndex), 0, highlight);
                return;
            }
            highlightTerm = highlight;
            chapterList.getSelectionModel().select(chapterIndex);
            chapterList.scrollTo(chapterIndex);
        }

        @Override
        public void setStatus(String text) {
            statusLabel.setText(text);
        }
    }

    // ==================== 小工具 ====================

    /**
     * 把当前书名写进窗口标题。
     *
     * <p>任务栏和 Alt-Tab 里显示的是窗口标题。开着好几本书（或者一边开着
     * 阅读器一边开着别的程序）的时候，"轻读阅读器"这四个字无法区分谁是谁，
     * 带上书名才能一眼认出来。
     *
     * <p>取不到窗口（构造阶段还没 Scene）就静默跳过 —— 这只是个锦上添花的信息，
     * 不值得为它抛异常。
     */
    private void applyWindowTitle() {
        Window window = windowOrNull();
        if (window instanceof Stage stage) {
            stage.setTitle(currentBook == null
                    ? "轻读阅读器"
                    : currentBook.title() + " — 轻读阅读器");
        }
    }

    private Window windowOrNull() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private String safeTitle(String title) {
        return (title == null || title.isBlank()) ? "（无标题）" : title;
    }

    private String formatTime(long epochMillis) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 后台任务的返回结果。
     *
     * <p>用一个 record 把三样东西一起搬回应用线程，比定义三个字段再逐个赋值清楚。
     */
    private record LoadResult(Book book, TxtChapterSplitter.Report report,
                              CharsetDetector.Detection detection) {
    }
}
