package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.store.ChapterTextSource;
import com.qingdu.store.SearchStore;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 搜索面板 —— 在当前打开的书里做全文检索。
 *
 * <p><b>三段式流程（这是本类的主线）：</b>
 * <pre>
 *   1. 懒建索引：用户第一次搜这本书时，后台读完整个文件、切分、写进 FTS5
 *      （一本 10 MB / 1636 章的书约 5 秒，期间状态栏报进度）
 *   2. 查询：SQL 取候选章（毫秒级）
 *   3. 后过滤：拿候选章的原文再校验一次，剔掉假阳性，同时生成摘要
 * </pre>
 *
 * <p><b>为什么第 1 步和 2、3 步放在同一个后台任务里？</b>
 * 建索引必须先把整本书读进内存，而查询的后过滤<b>同样需要那批原文</b>
 * （见 {@link ChapterTextSource} 的注释：一次查询可能要回调上千次，
 * 绝不能在回调里现读文件）。同一次任务里复用同一份
 * {@link ChapterTextBatch}，整本书只读一次。
 *
 * <p><b>为什么缓存那个 batch？</b>
 * 它本质上就是整本书的字节（10 MB 左右），用户连着搜几个词时
 * 每次都重读一遍是白花的 I/O。按"书的指纹"缓存：换书或者源文件变了才重新读。
 *
 * <p><b>并发</b>：同一时刻只允许一个搜索任务在跑（{@link #busy}）。
 * 不是怕线程不安全，而是两个任务同时给同一本书建索引会互相抢写锁，
 * 而且先跑完的那个会被后跑完的覆盖 —— 与其处理这种竞态，不如不让它们同时发生。
 */
public final class SearchPanel extends VBox {

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

        /** 在状态栏显示一句话（建索引、查询这类耗时动作要让用户知道在动）。 */
        void setStatus(String text);
    }

    private final SearchStore search;
    private final Host host;

    private final TextField input = new TextField();
    private final Button searchButton = new Button("搜索");
    private final ListView<SearchHit> results = new ListView<>();
    private final Label hint = new Label();
    private final ProgressBar progress = new ProgressBar(0);

    /** 整本书的字节缓存，连同它对应的指纹。只在后台任务里读写。 */
    private ChapterTextBatch batch;
    private String batchFingerprint;

    /** 是否有任务在跑。用它挡住重复提交，而不是去处理两个任务并存。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** 上一次的查询串，点击结果时要拿它去高亮。 */
    private volatile String lastQuery = "";

    public SearchPanel(SearchStore search, Host host) {
        this.search = search;
        this.host = host;

        getStyleClass().add("reader-search-panel");
        setSpacing(8);
        setPadding(new Insets(10, 10, 10, 10));

        input.setPromptText("在本书里搜索…");
        input.getStyleClass().add("reader-search-field");
        input.setOnAction(e -> submit());
        HBox.setHgrow(input, Priority.ALWAYS);

        searchButton.getStyleClass().add("reader-search-button");
        searchButton.setOnAction(e -> submit());
        searchButton.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(6, input, searchButton);
        row.setAlignment(Pos.CENTER_LEFT);

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setFocusTraversable(false);

        hint.getStyleClass().add("reader-search-hint");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);

        results.getStyleClass().add("reader-list");
        results.setPlaceholder(emptyHint());
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SearchHit item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(resultCell(item));
            }
        });
        // 点一下就跳：结果列表是"查一下看看"，不是"选中后还要再确认一次"
        results.setOnMouseClicked(event -> {
            SearchHit selected = results.getSelectionModel().getSelectedItem();
            if (selected != null) {
                host.jumpToChapter(selected.chapterIndex(), lastQuery);
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        getChildren().setAll(row, progress, hint, results);

        if (search == null) {
            input.setDisable(true);
            searchButton.setDisable(true);
            hint.setText("搜索不可用：本地数据库没有打开（本次运行不保存任何数据，索引也无处存放）");
        } else {
            hint.setText("第一次搜索会先给这本书建索引（大书约几秒），之后就是毫秒级。");
        }
    }

    /** 把输入焦点交给搜索框（Ctrl+F 时用）。 */
    public void focusInput() {
        input.requestFocus();
        input.selectAll();
    }

    /**
     * 换书或关书时清空状态。
     *
     * <p>必须清 {@link #batch}：它是上一本书的全部字节，
     * 留着既占内存，又会让"换书后的第一次搜索"读到旧书的文本。
     */
    public void reset() {
        results.getItems().clear();
        batch = null;
        batchFingerprint = null;
        lastQuery = "";
        if (search != null) {
            hint.setText("第一次搜索会先给这本书建索引（大书约几秒），之后就是毫秒级。");
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
        Book book = host.currentBook();
        if (book == null) {
            hint.setText("先打开一本书，再搜索。");
            return;
        }
        List<Chapter> chapters = host.chapters();
        if (chapters.isEmpty()) {
            hint.setText("这本书没有识别出章节，无法搜索。");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            hint.setText("上一个搜索还没跑完，请稍等…");
            return;
        }

        lastQuery = query;
        Path file = host.currentFile();
        String fingerprint = SearchStore.fingerprint(file);
        String bookId = book.id();

        results.getItems().clear();
        progress.setVisible(true);
        progress.setManaged(true);
        hint.setText("正在准备…");

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

        progress.progressProperty().bind(task.progressProperty());
        hint.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> finish(task.getValue(), query));
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            hint.textProperty().unbind();
            hint.setText(error == null ? "搜索失败" : ("搜索失败：" + error.getMessage()));
        });
        task.setOnCancelled(event -> {
            hint.textProperty().unbind();
            hint.setText("搜索已取消");
        });

        Thread worker = new Thread(task, "qingdu-search");
        worker.setDaemon(true);
        worker.start();
    }

    /** 收尾：把结果填进列表，并把"候选 / 精确"这对数字显示出来（诊断用，也是验收依据）。 */
    private void finish(SearchResult result, String query) {
        hint.textProperty().unbind();
        progress.progressProperty().unbind();
        progress.setVisible(false);
        progress.setManaged(false);
        busy.set(false);

        results.getItems().setAll(result.hits());
        if (result.hits().isEmpty()) {
            hint.setText("没有找到「" + query + "」");
        } else {
            // 被条数上限截断时说清楚"还有多少没查"，而不是含糊地少给几条 ——
            // 用户至少知道"再搜具体一点的词"能得到更准的结果
            String extra = result.truncated()
                    ? "（已显示前 " + result.hitCount() + " 章，另有 " + result.unchecked() + " 章未校验）"
                    : "";
            hint.setText(String.format("找到 %d 章%s    ·    候选 %d 章    ·    耗时 %d ms",
                    result.hitCount(), extra, result.candidateCount(), result.elapsedMs()));
        }
        if (!result.hits().isEmpty()) {
            // 选中第一条，用户按上下键就能翻结果
            Platform.runLater(() -> results.getSelectionModel().selectFirst());
        }
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

    /** 一条结果：第一行"第 N 章 · 标题"，第二行命中处上下文。 */
    private VBox resultCell(SearchHit hit) {
        Label title = new Label(chapterTitle(hit));
        title.getStyleClass().add("reader-search-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Label snippet = new Label(hit.snippet());
        snippet.getStyleClass().add("reader-search-snippet");
        snippet.setWrapText(true);
        snippet.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(3, title, snippet);
        box.getStyleClass().add("reader-search-cell");
        return box;
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
