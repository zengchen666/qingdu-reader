package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.reader.library.LibraryChapterTextSource;
import com.qingdu.reader.library.LibraryIndexTask;
import com.qingdu.reader.library.LibrarySearchPresenter;
import com.qingdu.store.QingduStore;
import com.qingdu.store.SearchStore;
import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchHit;
import com.qingdu.store.model.SearchResult;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 搜索面板 —— 「本书」与「全库」两种范围的全文检索。
 *
 * <h2>两种范围为什么不是两个面板</h2>
 * 它们共用输入框、结果列表、进度条、提示行，差别只在
 * "候选从哪来"和"结果怎么显示"。做成两个面板就要把这些控件写两遍，
 * 而两套代码迟早会走散（换主题时最容易漏掉一处）。
 * 所以是<b>一个面板 + 一个开关</b>，流程的分叉集中在 {@link #submit} 一处。
 *
 * <h2>本书搜索的三段式流程（v0.2.0 沿用）</h2>
 * <pre>
 *   1. 懒建索引：第一次搜这本书时，后台读完整个文件、切分、写进 FTS5
 *   2. 查询：SQL 取候选章（毫秒级）
 *   3. 后过滤：拿候选章的原文再校验一次，剔掉假阳性，同时生成摘要
 * </pre>
 * 建索引与后过滤放在同一个后台任务里，因为两者都要整本书的字节
 * （见 {@link ChapterTextBatch} 的注释：一次查询可能回调上千次，
 * 绝不能在回调里现读文件）。
 *
 * <h2>全库搜索为什么完全不同</h2>
 * 全库检索的候选可能来自几十本书，<b>把整本书读进内存这条路直接作废</b>
 * （50 本 × 10 MB = 500 MB）。所以全库模式改用
 * {@link LibraryChapterTextSource}：按字节偏移随机读，命中哪章读哪章，
 * 三层 LRU 把同时打开的句柄压在 8 个。
 * 详见那个类的类注释 —— 它是 v0.3 最关键的一个类。
 *
 * <h2>并发</h2>
 * 同一时刻只允许一个任务在跑（{@link #busy}）。
 * 两个任务同时给同一本书建索引会互相抢写锁，先跑完的还会被后跑完的覆盖 ——
 * 与其处理这种竞态，不如不让它们同时发生。
 */
public final class SearchPanel extends VBox {

    /** 搜索范围。 */
    public enum Scope {
        /** 只搜当前打开的这本书（v0.2.0 的行为）。 */
        BOOK("本书"),
        /** 搜整个书库（v0.3 新增）。 */
        LIBRARY("全库");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 面板对宿主（阅读界面）的依赖。
     *
     * <p>用回调而不是直接持有 {@code ReaderView}：
     * 搜索需要知道的只有"当前是哪本书、章节列表是什么、跳到某一章"，
     * 定死一个窄接口，这个面板就不必知道阅读界面的其余一千行。
     */
    public interface Host {

        /** 当前打开的书；没有打开任何书时返回 null。 */
        Book currentBook();

        /** 当前书的源文件。 */
        Path currentFile();

        /** 当前书的章节列表（用来把章号显示成章节标题）。 */
        List<Chapter> chapters();

        /**
         * 跳到某一章，并把某个词高亮出来。
         *
         * @param chapterIndex 章号
         * @param highlight    要高亮的词；为 null 表示不高亮
         */
        void jumpToChapter(int chapterIndex, String highlight);

        /**
         * 打开指定的一本书并跳到其中某一章 —— <b>跨书检索点结果时用</b>。
         *
         * <p>🔴 为什么不复用 {@link #jumpToChapter}：跨书结果可能来自另一本，
         * 而那本<b>此刻根本没打开</b>。只传章号的话，宿主只能跳到
         * "当前这本书的第 N 章" —— 点《沧澜录》的结果却跳进了《星尘纪》，
         * 而且不报错、界面看起来完全正常。这是本类最容易犯的错，
         * 所以接口层面就分成两个方法，让"要切书"这件事显式起来。
         *
         * @param bookId       要打开的书
         * @param chapterIndex 目标章号
         * @param highlight    要高亮的词；可为 null
         */
        void openBookAt(String bookId, int chapterIndex, String highlight);

        /** 在状态栏显示一句话（建索引、查询这类耗时动作要让用户知道在动）。 */
        void setStatus(String text);
    }

    private final SearchStore search;
    private final QingduStore store;
    private final Host host;

    private final TextField input = new TextField();
    private final Button searchButton = new Button("搜索");
    private final ToggleButton bookScope = new ToggleButton("本书");
    private final ToggleButton libraryScope = new ToggleButton("全库");
    private final ToggleGroup scopeGroup = new ToggleGroup();

    /**
     * 结果列表。
     *
     * <p>🔴 <b>刻意做成 {@code ListView<Object>} 而不是两个列表。</b>
     * 两个列表（一个装 {@code SearchHit}、一个装分组行）看着更"类型干净"，
     * 但切换范围时必须同步维护两个列表的选中态和可见性，
     * 而<b>切了范围但忘了清选中态</b>的症状是"按上下键跳到别的书的章节去"——
     * 界面不报错、结果也不对，很难定位。
     * 一个列表 + 一个渲染时再分派的 {@code cellFactory} 没有这个状态。
     */
    private final ListView<Object> results = new ListView<>();

    private final HBox scopeRow = new HBox(6);
    private final HBox inputRow = new HBox(6);

    private final Label hint = new Label();
    private final ProgressBar progress = new ProgressBar(0);

    /** 覆盖度提示 + 「建立索引」按钮那一行；只在全库模式且有书没索引时出现。 */
    private final Label coverage = new Label();
    private final Button buildIndexButton = new Button("建立索引");
    private final Button cancelIndexButton = new Button("取消");
    private final VBox indexBox = new VBox();

    /** 整本书的字节缓存，连同它对应的指纹。只在后台任务里读写。 */
    private ChapterTextBatch batch;
    private String batchFingerprint;

    /** 是否有任务在跑。用它挡住重复提交，而不是去处理两个任务并存。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * 正在跑的批量建索引任务。
     *
     * <p>用 {@link AtomicReference} 而不是裸字段：它是在 UI 线程点「取消」、
     * 在后台线程读，两边都要看得见最新值。
     * 之所以不直接用 {@code LibraryIndexTask.cancel()} 之外再加锁：
     * 取消本身是幂等的一次 boolean 写，锁带来的开销和复杂度都不值。
     */
    private final AtomicReference<LibraryIndexTask> runningIndexTask = new AtomicReference<>();

    /**
     * 全库检索的原文源，<b>长期持有、跨多次搜索复用</b>。
     *
     * <p>🔴 <b>这一条是 v0.3 跨书检索性能的关键，真书语料实测约 2 倍。</b>
     * {@link LibraryChapterTextSource} 里最贵的是"章节表"——
     * 要按字节偏移 seek 就得先知道每章的偏移，而偏移来自分章，
     * 分章要把<b>整个文件读进内存扫一遍</b>（10 MB 的书约 200~300 ms）。
     * 它内部缓存了这些表，注释里也写着"这个代价只付一次"。
     *
     * <p>但如果每次搜索都新建一个源（try-with-resources 是最自然的写法，
     * 也确实防句柄泄漏），那些表每次搜索都要重算一遍 ——
     * "只付一次"变成了"每次都付"。症状很隐蔽：功能全对，
     * 只是每次搜索都慢几百毫秒，很容易被当成"跨书检索本来就慢"而放过。
     *
     * <p>实测（四本真书 37.8 MB / 6099 章，见 v0.3 验收报告）：
     * 「每次新建源」1146~2160 ms，「复用同一个源」555~1222 ms，
     * 九个查询词平均快 <b>1.9 倍</b>、最好 3.0 倍。
     * （注：某个词如果只涉及一本书，而那一本的章节表恰好已经在缓存里，
     * 差距会缩小到 1.0 倍 —— 这也是为什么不能拿单个查询词下结论。）
     *
     * <p>所以这里<b>故意长期持有</b>，代价是句柄不立刻释放 ——
     * 而它自己的 LRU 把同时打开的句柄压在 8 个，泄漏不了。
     * 真正的释放点只有 {@link #dispose()}（面板要扔了）。
     *
     * <p>⚠️ <b>它不是线程安全的</b>，所以只能由 {@link #busy} 挡着，
     * 保证同一时刻只有一个搜索在用它。
     */
    private LibraryChapterTextSource librarySource;

    /** 上一次的查询串，点击结果时要拿它去高亮。 */
    private volatile String lastQuery = "";

    /** 当前范围。默认「本书」—— 那是绝大多数用户 95% 时间在做的事。 */
    private Scope scope = Scope.BOOK;

    public SearchPanel(QingduStore store, Host host) {
        this.store = store;
        this.search = store == null ? null : store.search();
        this.host = host;

        getStyleClass().add("reader-search-panel");
        setSpacing(8);
        setPadding(new Insets(10, 10, 10, 10));

        buildScopeRow();
        buildInputRow();
        buildIndexBox();
        buildResultList();

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setFocusTraversable(false);

        hint.getStyleClass().add("reader-search-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        VBox.setVgrow(results, Priority.ALWAYS);
        getChildren().setAll(scopeRow, inputRow, progress, indexBox, hint, results);

        if (search == null) {
            input.setDisable(true);
            searchButton.setDisable(true);
            bookScope.setDisable(true);
            libraryScope.setDisable(true);
            hint.setText("搜索不可用：本地数据库没有打开（本次运行不保存任何数据，索引也无处存放）");
        } else {
            hint.setText("第一次搜索会先给这本书建索引（大书约几秒），之后就是毫秒级。");
        }
    }

    // ==================== 构造 ====================

    private void buildScopeRow() {
        // ToggleGroup 而不是两个独立的 CheckBox：必须互斥，
        // 而互斥这件事交给 ToggleGroup 就不必自己写监听去纠正"两个都被选中"
        bookScope.setToggleGroup(scopeGroup);
        libraryScope.setToggleGroup(scopeGroup);
        bookScope.setSelected(true);
        bookScope.getStyleClass().add("reader-search-scope");
        libraryScope.getStyleClass().add("reader-search-scope");
        bookScope.setOnAction(e -> switchScope(Scope.BOOK));
        libraryScope.setOnAction(e -> switchScope(Scope.LIBRARY));
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.getChildren().setAll(bookScope, libraryScope);
    }

    private void buildInputRow() {
        input.setPromptText("在本书里搜索…");
        input.getStyleClass().add("reader-search-field");
        input.setOnAction(e -> submit());
        HBox.setHgrow(input, Priority.ALWAYS);

        searchButton.getStyleClass().add("reader-search-button");
        searchButton.setOnAction(e -> submit());
        searchButton.setMinWidth(Region.USE_PREF_SIZE);

        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.getChildren().setAll(input, searchButton);
    }

    /**
     * 「还没建索引」那一行：一个说明 + 「建立索引」/「取消」两个按钮。
     *
     * <p>整条在没有待建索引的书时 {@code setVisible(false) + setManaged(false)} ——
     * 只 {@code setVisible(false)} 的话它仍然占布局空间，
     * 结果列表会被顶上来一小截（JavaFX 里"看不见但仍占位"是常见的坑）。
     */
    private void buildIndexBox() {
        coverage.getStyleClass().add("reader-search-coverage");
        coverage.setWrapText(true);

        buildIndexButton.getStyleClass().add("reader-search-button");
        buildIndexButton.setOnAction(e -> startIndexing());
        buildIndexButton.setMinWidth(Region.USE_PREF_SIZE);

        cancelIndexButton.getStyleClass().add("reader-search-button");
        cancelIndexButton.setOnAction(e -> cancelIndexing());
        cancelIndexButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelIndexButton.setVisible(false);
        cancelIndexButton.setManaged(false);

        HBox buttons = new HBox(6, buildIndexButton, cancelIndexButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        indexBox.setSpacing(4);
        indexBox.getChildren().setAll(coverage, buttons);
        hideIndexBox();
    }

    private void buildResultList() {
        results.getStyleClass().add("reader-list");
        results.setPlaceholder(emptyHint());
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(renderRow(item));
            }
        });
        // 点一下就跳：结果列表是"查一下看看"，不是"选中后还要再确认一次"
        results.setOnMouseClicked(event -> {
            Object selected = results.getSelectionModel().getSelectedItem();
            jumpIfHit(selected);
        });
    }

    private void hideIndexBox() {
        indexBox.setVisible(false);
        indexBox.setManaged(false);
    }

    // ==================== 对外 ====================

    /** 把输入焦点交给搜索框（Ctrl+F 时用）。 */
    public void focusInput() {
        input.requestFocus();
        input.selectAll();
    }

    /** 当前范围（设置窗口与测试用）。 */
    public Scope scope() {
        return scope;
    }

    /**
     * 换书或关书时清空状态。
     *
     * <p>必须清 {@link #batch}：它是上一本书的全部字节，
     * 留着既占内存，又会让"换书后的第一次搜索"读到旧书的文本。
     *
     * <p>🔴 <b>全库结果不清。</b>全库结果列的是整个书库，跟"当前打开哪本"无关 ——
     * 而跨书检索点结果<b>必然</b>会换书（{@code openBookAt} → {@code open} →
     * {@code applyResult} → 回到这里）。如果这里无条件清空列表，
     * 用户点第一条结果之后整个列表就没了，想看第二条必须重新搜一遍 ——
     * 而"在结果里来回翻"恰恰是全库检索的主要用法。
     *
     * <p>⚠️ <b>但不取消批量建索引</b>。用户点了「建立 38 本索引」之后
     * 顺手把当前书关掉（或者点另一本），索引任务跟当前书毫无关系 ——
     * 把它一起 cancel 掉，用户会发现"我只是换本书，怎么索引停了"。
     * 只有 {@link #dispose()}（整个面板要扔了）才停它。
     */
    public void reset() {
        batch = null;
        batchFingerprint = null;
        if (scope == Scope.BOOK) {
            results.getItems().clear();
            lastQuery = "";
            hideIndexBox();
        }
        if (search != null && scope == Scope.BOOK) {
            hint.setText("第一次搜索会先给这本书建索引（大书约几秒），之后就是毫秒级。");
        }
    }

    /**
     * 面板彻底不用了 —— 停掉后台任务。
     *
     * <p>和 {@link #reset()} 分开是因为语义完全不同：
     * {@code reset} 是"换书了"，这里是"整个面板要扔了"。
     * 混成一个方法的后果是关书时把索引任务也停掉（见 {@link #reset} 的注释）。
     */
    public void dispose() {
        LibraryIndexTask task = runningIndexTask.getAndSet(null);
        if (task != null) {
            task.cancel();
        }
        // 跨书检索的原文源是长期持有的（见字段注释），所以这里才是它真正的关闭点。
        // 不关的话最多漏 8 个文件句柄 —— 进程退出时 OS 会回收，
        // 但显式关掉能让"句柄泄漏"这件事在开发期就暴露出来，而不是靠用户报 bug。
        if (librarySource != null) {
            librarySource.close();
            librarySource = null;
        }
    }

    // ==================== 范围切换 ====================

    private void switchScope(Scope next) {
        if (next == scope) {
            return;
        }
        scope = next;
        results.getItems().clear();
        lastQuery = "";
        // 范围换了，字节缓存也该换：本书模式的 batch 是"当前书"，
        // 全库模式压根不用它（走的是 LibraryChapterTextSource）
        batch = null;
        batchFingerprint = null;
        if (next == Scope.LIBRARY) {
            input.setPromptText("在全部书籍里搜索…");
            hint.setText("全库检索需要先给书建索引。没建索引的书不会被搜索到。");
            refreshCoverageLine();
        } else {
            input.setPromptText("在本书里搜索…");
            hideIndexBox();
            if (search != null) {
                hint.setText("第一次搜索会先给这本书建索引（大书约几秒），之后就是毫秒级。");
            }
        }
    }

    /**
     * 刷新「还有 N 本没建索引」那一行。
     *
     * <p>只在<b>全库模式</b>有意义：本书模式会在搜索时自动给这一本建索引，
     * 不需要用户手动操作。
     */
    private void refreshCoverageLine() {
        if (scope != Scope.LIBRARY || store == null) {
            hideIndexBox();
            return;
        }
        int pending = new LibraryIndexTask(store).pendingCount();
        if (pending <= 0) {
            hideIndexBox();
            return;
        }
        coverage.setText(pending == 1
                ? "书库里有 1 本书还没建索引，它不会被搜索到。"
                : "书库里有 " + pending + " 本书还没建索引，它们不会被搜索到。");
        indexBox.setVisible(true);
        indexBox.setManaged(true);
    }

    // ==================== 批量建索引 ====================

    private void startIndexing() {
        if (store == null || runningIndexTask.get() != null) {
            return;
        }
        // 🔴 建索引和搜索不能同时跑。两个理由，第二个是硬性的：
        // ① 两者都要写同一个 SQLite 库，并发写会互相抢锁；
        // ② <b>共用同一个进度条</b> —— 搜索开始时它被 bind 到 Task.progressProperty，
        //    而建索引的进度回调是直接 setProgress，
        //    对一个已 bind 的属性 set 会抛 IllegalStateException。
        //    症状是"建索引建到一半，界面崩了"，而且必现。
        if (busy.get()) {
            hint.setText("搜索还没跑完，请稍等一下再建立索引。");
            return;
        }
        // 反过来也要挡住：占了 busy 之后 submit() 就进不来了
        busy.set(true);
        progress.progressProperty().unbind();

        LibraryIndexTask task = new LibraryIndexTask(store);
        runningIndexTask.set(task);
        setIndexingUi(true);
        results.getItems().clear();
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        // 界面上的「取消」直接落到 task.cancel()：
        // LibraryIndexTask 会在"正要开始处理下一本"时检查这个标志（见它的注释），
        // 所以点了取消最多再多跑半本，不会拖到全部跑完。
        cancelIndexButton.setOnAction(e -> task.cancel());

        Thread worker = new Thread(() -> {
            LibraryIndexTask.Report report = task.run((done, total, current) -> {
                // 进度回调来自后台线程，改界面必须回 FX 线程
                Platform.runLater(() -> {
                    if (current == null) {
                        progress.setProgress(1);
                        return;
                    }
                    progress.setProgress(total <= 0 ? 0 : (double) done / total);
                    coverage.setText("正在建立索引 " + (done + 1) + " / " + total
                            + "：" + current.title());
                });
            });
            Platform.runLater(() -> finishIndexing(report));
        }, "qingdu-library-index");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishIndexing(LibraryIndexTask.Report report) {
        runningIndexTask.set(null);
        busy.set(false);
        setIndexingUi(false);
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setProgress(0);

        if (report.failed() > 0) {
            // 有失败要说出来，但别把整个报告糊在界面上：
            // 名字可能很长，全贴出来会把结果列表挤没
            String first = report.failures().values().stream().findFirst().orElse("未知原因");
            coverage.setText("已建立 " + report.succeeded() + " 本，"
                    + report.failed() + " 本失败（" + first + "）");
            indexBox.setVisible(true);
            indexBox.setManaged(true);
            return;
        }
        refreshCoverageLine();
        if (report.cancelled()) {
            hint.setText("已取消建立索引（完成 " + report.succeeded() + " 本，已建好的可以正常搜索）");
            return;
        }
        hint.setText("索引已就绪，共 " + report.succeeded() + " 本。现在可以搜整个书库了。");
    }

    /**
     * 建索引期间把按钮切换成「取消」。
     *
     * <p>「建立索引」被<b>藏掉</b>而不是只禁用：它和「取消」占同一个位置，
     * 按钮宽度不变，切换时这一行不会左右跳一下。
     *
     * <p>🔴 <b>搜索按钮也一并禁掉</b>，尽管它看上去跟建索引无关。
     * 原因是共用进度条：搜索一开始就把 {@code progressProperty} bind 到
     * {@code Task.progressProperty}，而建索引的进度回调是直接
     * {@code setProgress} —— 对已 bind 的属性 set 会抛
     * {@code IllegalStateException}。与其在两处各写一遍"检查对方在不在跑"，
     * 不如让界面直接不给点：那才是唯一可靠的一道门。
     * （本可以用两个进度条规避，但两个进度条意味着用户同时看到两个"在动"的指示器，
     * 比禁一个按钮更让人困惑。）
     */
    private void setIndexingUi(boolean indexing) {
        buildIndexButton.setVisible(!indexing);
        buildIndexButton.setManaged(!indexing);
        cancelIndexButton.setVisible(indexing);
        cancelIndexButton.setManaged(indexing);
        searchButton.setDisable(indexing);
        input.setDisable(indexing);
        if (indexing) {
            indexBox.setVisible(true);
            indexBox.setManaged(true);
        }
    }

    private void cancelIndexing() {
        LibraryIndexTask task = runningIndexTask.get();
        if (task != null) {
            task.cancel();
            coverage.setText("正在取消…（当前这一本建完就停）");
        }
    }

    // ==================== 搜索流程 ====================

    private void submit() {
        if (search == null) {
            return;
        }
        String query = input.getText() == null ? "" : input.getText().strip();
        if (query.isEmpty()) {
            hint.setText("输入要找的词，按回车开始搜索。");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            // busy 有两个占用者：搜索任务和批量建索引。
            // 不区分开的话，用户在等 38 本建索引时看到"上一个搜索还没跑完"，
            // 会以为刚才那一次搜索卡住了 —— 而他其实一次搜索都没跑过。
            hint.setText(runningIndexTask.get() != null
                    ? "正在建立索引，请等它跑完再搜索。"
                    : "上一个搜索还没跑完，请稍等…");
            return;
        }
        if (scope == Scope.LIBRARY) {
            submitLibrary(query);
        } else {
            submitBook(query);
        }
    }

    // ---------- 本书 ----------

    private void submitBook(String query) {
        Book book = host.currentBook();
        if (book == null) {
            busy.set(false);
            hint.setText("先打开一本书，再搜索。");
            return;
        }
        List<Chapter> chapters = host.chapters();
        if (chapters.isEmpty()) {
            busy.set(false);
            hint.setText("这本书没有识别出章节，无法搜索。");
            return;
        }

        lastQuery = query;
        Path file = host.currentFile();
        String fingerprint = SearchStore.fingerprint(file);
        String bookId = book.id();

        results.getItems().clear();
        showProgress(true);
        setHint("正在准备…");

        Task<SearchResult> task = new Task<>() {
            @Override
            protected SearchResult call() throws Exception {
                // 1) 保证整本书在内存里（建索引和后过滤共用这一份）
                ChapterTextBatch texts = ensureBatch(file, chapters, fingerprint, this::updateMessage);

                // 2) 没建过索引（或源文件变了）就先建 —— 这是唯一会花几秒的一步
                if (!search.isIndexed(bookId, fingerprint)) {
                    updateMessage("正在建立索引…");
                    int total = texts.size();
                    List<SearchDocument> docs = new ArrayList<>(total);
                    for (int i = 0; i < total; i++) {
                        docs.add(new SearchDocument(i, texts.textOf(i)));
                        updateProgress(i, total);
                        updateMessage("正在建立索引 " + i + " / " + total + " 章");
                    }
                    search.index(bookId, fingerprint, docs, done ->
                            updateMessage("正在写入索引 " + done + " / " + total + " 章"));
                }

                // 3) 查询 + 后过滤。整本书已经在内存里，回调只是切一段字节再解码
                updateMessage("正在检索…");
                updateProgress(-1, 1);
                return search.search(bookId, query, 0, texts::textOf);
            }
        };

        bindProgress(task);
        task.setOnSucceeded(event -> finishBookSearch(task.getValue(), query));
        task.setOnFailed(event -> failSearch(task.getException()));
        task.setOnCancelled(event -> {
            setHint("搜索已取消");
            showProgress(false);
            busy.set(false);
        });

        startDaemon(task, "qingdu-search");
    }

    // ---------- 全库 ----------

    private void submitLibrary(String query) {
        if (store == null) {
            busy.set(false);
            return;
        }
        lastQuery = query;
        results.getItems().clear();
        showProgress(true);
        setHint("正在检索…");

        LibraryIndexTask indexTask = new LibraryIndexTask(store);
        Task<LibrarySearchResult> task = new Task<>() {
            @Override
            protected LibrarySearchResult call() throws Exception {
                // 🔴 这里<b>不能</b>用 try-with-resources 每次新建一个源。
                // 复用同一个源，章节表缓存才留得住 —— 真书语料实测平均快 1.9 倍，
                // 原因与数据见 librarySource 字段的注释。
                // 代价（句柄不立刻释放）由源自己的 LRU 兜住：最多 8 个。
                updateMessage("正在跨书检索…");
                return search.searchAll(query, 0, libraryTextSource(indexTask));
            }
        };

        bindProgress(task);
        task.setOnSucceeded(event -> finishLibrarySearch(task.getValue(), query));
        task.setOnFailed(event -> failSearch(task.getException()));
        task.setOnCancelled(event -> {
            setHint("搜索已取消");
            showProgress(false);
            busy.set(false);
        });

        startDaemon(task, "qingdu-library-search");
    }

    // ---------- 共用的收尾 ----------

    private void bindProgress(Task<?> task) {
        progress.progressProperty().bind(task.progressProperty());
    }

    private void startDaemon(Task<?> task, String name) {
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.start();
    }

    private void showProgress(boolean visible) {
        progress.progressProperty().unbind();
        if (!visible) {
            progress.setVisible(false);
            progress.setManaged(false);
        } else {
            progress.setVisible(true);
            progress.setManaged(true);
        }
    }

    private void setHint(String text) {
        hint.textProperty().unbind();
        hint.setText(text);
    }

    private void failSearch(Throwable error) {
        showProgress(false);
        busy.set(false);
        setHint(error == null ? "搜索失败" : ("搜索失败：" + error.getMessage()));
    }

    /** 本书搜索的结果：直接铺进列表（v0.2.0 的行为，一行一条）。 */
    private void finishBookSearch(SearchResult result, String query) {
        showProgress(false);
        busy.set(false);

        List<Object> rows = new ArrayList<>(result.hits());
        results.getItems().setAll(rows);
        if (result.hits().isEmpty()) {
            setHint("没有找到「" + query + "」");
        } else {
            // 被条数上限截断时说清楚"还有多少没查"，而不是含糊地少给几条 ——
            // 用户至少知道"再搜具体一点的词"能得到更准的结果
            String extra = result.truncated()
                    ? "（已显示前 " + result.hitCount() + " 章，另有 " + result.unchecked() + " 章未校验）"
                    : "";
            setHint(String.format("找到 %d 章%s    ·    候选 %d 章    ·    耗时 %d ms",
                    result.hitCount(), extra, result.candidateCount(), result.elapsedMs()));
        }
        if (!result.hits().isEmpty()) {
            // 选中第一条，用户按上下键就能翻结果
            Platform.runLater(() -> results.getSelectionModel().selectFirst());
        }
    }

    /**
     * 全库搜索的结果：按书分组，每本书一个头。
     *
     * <p>🔴 覆盖度提示<b>不能省</b>。索引是懒建的，
     * 搜出 0 条时用户分不清"书库里确实没有"和"大部分书根本没被搜"。
     * 不说清楚就是沉默的错误答案 —— 详见 {@link LibrarySearchPresenter#summary}。
     */
    private void finishLibrarySearch(LibrarySearchResult result, String query) {
        showProgress(false);
        busy.set(false);

        List<Object> rows = new ArrayList<>(LibrarySearchPresenter.toRows(
                result.hits(), result.hitsByBook().size()));
        results.getItems().setAll(rows);
        setHint(LibrarySearchPresenter.summary(query, result));
        refreshCoverageLine();

        if (LibrarySearchPresenter.hasHits(result)) {
            Platform.runLater(() -> results.getSelectionModel().selectFirst());
        }
    }

    /**
     * 拿到（必要时创建）那个长期持有的全库原文源。
     *
     * <p>只在后台任务里调用，而 {@link #busy} 保证同一时刻只有一个 ——
     * 所以这里不需要加锁（{@code LibraryChapterTextSource} 明确不是线程安全的）。
     */
    private LibraryChapterTextSource libraryTextSource(LibraryIndexTask indexTask) {
        if (librarySource == null) {
            librarySource = indexTask.newTextSource();
        }
        return librarySource;
    }

    /**
     * 保证"整本书的字节"在内存里，且与当前指纹匹配。
     *
     * <p>指纹不匹配意味着换书了、或者源文件被改过 ——
     * 这时候必须重读，否则会拿旧文本去校验，结果全是错的。
     */
    private ChapterTextBatch ensureBatch(Path file, List<Chapter> chapters, String fingerprint,
                                         Consumer<String> report) throws Exception {
        if (batch != null && fingerprint.equals(batchFingerprint)) {
            return batch;
        }
        report.accept("正在读取整本书…");
        ChapterTextBatch loaded = ChapterTextBatch.load(file, chapters);
        batch = loaded;
        batchFingerprint = fingerprint;
        return loaded;
    }

    // ==================== 界面细节 ====================

    /** 一行结果：可能是分组头（本书），也可能是一条命中（本书或全库）。 */
    private javafx.scene.Node renderRow(Object item) {
        if (item instanceof LibrarySearchPresenter.Row.BookHeader header) {
            return bookHeaderCell(header);
        }
        if (item instanceof LibrarySearchPresenter.Row.ChapterRow row) {
            return libraryHitCell(row.hit());
        }
        if (item instanceof LibraryHit hit) {
            return libraryHitCell(hit);
        }
        if (item instanceof SearchHit hit) {
            return bookHitCell(hit);
        }
        return new Label(String.valueOf(item));
    }

    /**
     * 分组头：「《星尘纪》 · 3 章命中」。
     *
     * <p>刻意做成<b>不可点</b>（点它不跳转）：它不是一条结果，
     * 点下去什么都不发生才是最诚实的反馈。
     */
    private HBox bookHeaderCell(LibrarySearchPresenter.Row.BookHeader header) {
        Label title = new Label("《" + header.bookTitle() + "》");
        title.getStyleClass().add("reader-search-book-title");

        Label count = new Label(header.hitCount() + " 章命中");
        count.getStyleClass().add("reader-search-book-count");

        HBox box = new HBox(8, title, count);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("reader-search-book-header");
        return box;
    }

    /** 全库模式的一条命中：书名已经在分组头上给了，这里只显示章与摘要。 */
    private VBox libraryHitCell(LibraryHit hit) {
        Label title = new Label("第 " + (hit.chapterIndex() + 1) + " 章 · "
                + hit.hitCount() + " 处");
        title.getStyleClass().add("reader-search-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label snippet = new Label(hit.snippet());
        snippet.getStyleClass().add("reader-search-snippet");
        snippet.setWrapText(true);
        snippet.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(3, title, snippet);
        box.getStyleClass().add("reader-search-cell");
        box.setPadding(new Insets(0, 0, 0, 12));
        return box;
    }

    /** 本书模式的一条结果：第一行"第 N 章 · 标题"，第二行命中处上下文。 */
    private VBox bookHitCell(SearchHit hit) {
        Label title = new Label(chapterTitle(hit));
        title.getStyleClass().add("reader-search-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label snippet = new Label(hit.snippet());
        snippet.getStyleClass().add("reader-search-snippet");
        snippet.setWrapText(true);
        snippet.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(3, title, snippet);
        box.getStyleClass().add("reader-search-cell");
        return box;
    }

    /**
     * 点结果时真正要跳的地方。
     *
     * <p>分三种：分组头（不跳）、本书命中（跳当前书）、全库命中（可能要先切书）。
     * 全部走 {@link LibrarySearchPresenter} 的静态判断，
     * 免得"哪些行可点"这条规则散在两个地方各写一遍。
     */
    private void jumpIfHit(Object item) {
        if (item instanceof LibrarySearchPresenter.Row row) {
            if (!LibrarySearchPresenter.isJumpable(row)) {
                return;
            }
            LibraryHit hit = LibrarySearchPresenter.hitOf(row);
            jumpToLibraryHit(hit);
            return;
        }
        if (item instanceof LibraryHit hit) {
            jumpToLibraryHit(hit);
            return;
        }
        if (item instanceof SearchHit hit) {
            host.jumpToChapter(hit.chapterIndex(), lastQuery);
        }
    }

    /**
     * 跳到跨书命中。
     *
     * <p>如果命中的正是当前打开的书，走快捷路径（不必重开一遍书）；
     * 否则让宿主切书后再跳 —— 这一步是 v0.3 跨书检索的最后一环。
     */
    private void jumpToLibraryHit(LibraryHit hit) {
        Book current = host.currentBook();
        if (current != null && current.id().equals(hit.bookId())) {
            host.jumpToChapter(hit.chapterIndex(), lastQuery);
        } else {
            host.openBookAt(hit.bookId(), hit.chapterIndex(), lastQuery);
        }
    }

    /** 把章号翻译成"第 N 章 · 标题"；章节列表对不上时退化成"第 N 章"。 */
    private String chapterTitle(SearchHit hit) {
        List<Chapter> chapters = host.chapters();
        String number = "第 " + (hit.chapterIndex() + 1) + " 章";
        if (chapters == null || hit.chapterIndex() >= chapters.size()) {
            return number;
        }
        Chapter chapter = chapters.get(hit.chapterIndex());
        String title = (chapter == null || chapter.title() == null) ? "" : chapter.title().strip();
        return title.isEmpty() ? number : (number + "    ·    " + title);
    }

    private Label emptyHint() {
        Label label = new Label("输入关键词，回车开始搜索。\n搜到的章节点一下就能跳过去。");
        label.getStyleClass().add("reader-empty-hint");
        label.setWrapText(true);
        return label;
    }
}
