package com.qingdu.reader.ui;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.reader.ai.AiServiceClient;
import com.qingdu.reader.ai.AiServiceClient.AskOutcome;
import com.qingdu.reader.ai.AiServiceClient.Failure;
import com.qingdu.reader.ai.AiModels;
import com.qingdu.reader.ai.AskKeyword;
import com.qingdu.reader.ai.ChunkSplitter;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 问答面板 —— 基于原文的问答，答案带可点击的引用。
 *
 * <h2>🔴 服务没起时必须"先禁用、后提示"，不能"点了才报错"</h2>
 * 用户不会记得要先开 Python 服务。如果让入口可点、点了才弹"服务未启动"，
 * 那是把一个**启动顺序**问题伪装成**用户操作错误**。
 * 所以构造时就探测一次：探测不到就把输入框与按钮置灰，
 * 并直接把启动命令显示出来。
 *
 * <h2>硬约束：轻读断网仍完全可用</h2>
 * 本面板的一切行为都被包在"探测不到就禁用"里 ——
 * AI 服务挂了、没起、没 key，阅读功能一个字都不受影响（设计文档 7.3）。
 *
 * @see AiServiceClient
 */
public final class AiPanel extends VBox {

    /**
     * 宿主接口 —— 面板只抛意图，不自己碰界面其它部分。
     *
     * <p>与 {@code SearchPanel} 同一套约定：这样面板能单测（用假 Host），
     * 也避免面板去调 {@code ReaderView} 的私有方法。
     */
    public interface Host {
        /** 当前打开的书；没有打开时返回 null。 */
        Book currentBook();

        /**
         * 读某章的内容块。
         *
         * @return 该章内容块；读不到时返回空 List
         */
        List<ChapterBlock> chapterBlocks(int chapterIndex);

        /**
         * 取某章的标题（用于引用出处显示）。
         *
         * <p><b>为什么要单独一个方法</b>：{@link ChapterBlock} 里没有章名，
         * 章名在 {@code Chapter.title()} 上。而引用列表只给人看一行
         * 「[1] 第十二章 夜访」—— 没有章名就只剩个编号，核对价值大打折扣。
         *
         * @return 章节标题；越界时返回空串
         */
        String chapterTitle(int chapterIndex);

        /**
         * 用关键词检索当前这本书，返回命中的章节序号。
         *
         * <p><b>为什么返回章节而不是片段？</b>
         * 轻读只负责"哪几章相关"，切片段与排序由 {@link ChunkSplitter}
         * 和服务端各做一半。轻读再往细里做就是<b>两处真理</b>。
         *
         * @return 命中的章节序号（0 起）；没有索引时返回空 List
         */
        List<Integer> searchableChapters(String keyword);

        /** 跳到指定章节（引用点击）。 */
        void jumpToChapter(int chapterIndex, String highlight);

        /** 状态栏提示。 */
        void setStatus(String text);
    }

    private static final int DEFAULT_TOP_K = 8;
    /** 一次问答最多送多少片段给服务端（服务端还会再去重/打散/截断）。 */
    private static final int MAX_CHUNKS = 200;
    /**
     * 一次问答最多送几本书的章节。
     *
     * <p><b>为什么是 4 而不是 1</b>：只送一章的话，"跨章推理"类问题
     * （比如"他后来去哪了"）必然召不回。但也不能给多——每多一章就是
     * 几十 KB JSON。Python 侧会打散，所以多送几章只提升召回上限，
     * 不会让上下文被单章淹没。
     */
    private static final int MAX_HIT_CHAPTERS = 4;

    private final Host host;
    private final AiServiceClient client;

    private final TextField input = new TextField();
    private final Button askButton = new Button("提问");
    private final Label statusLabel = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final VBox answerBox = new VBox();
    private final ListView<AiModels.Citation> citationList = new ListView<>();
    private final TitledPane citationPane = new TitledPane("引用出处", citationList);
    private final Button recheckButton = new Button("重新检测服务");

    /** 防止重复提问：连点会并发发出多个请求，答案会乱序覆盖。 */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** 服务是否可用。false 时输入与提问按钮置灰。 */
    private volatile boolean serviceReady;

    /**
     * 最近一次提问<b>真的召回了章节</b>的检索词 —— 引用点击跳转时拿它做高亮。
     *
     * <p>为什么不用整句：整句在原文里根本不存在（见 {@code AskKeyword}），
     * 拿去高亮一个都标不出来。这里存的是候选阶梯里第一个有命中的短词。
     *
     * <p>为什么存字段而不是每次重算：cellFactory 是工厂，不该有业务逻辑；
     * 而且重算得再跑一遍 FTS 才能知道该用哪个词。
     */
    private volatile String lastKeyword = "";

    public AiPanel(Host host) {
        this(host, new AiServiceClient());
    }

    AiPanel(Host host, AiServiceClient client) {
        this.host = host;
        this.client = client;
        buildUi();
        // 探测在后台线程做：服务没起时 connect 失败是立即返回的，
        // 但仍不该在 UI 线程上做网络调用。
        probeService();
    }

    // ==================== 界面 ====================

    private void buildUi() {
        setSpacing(8);
        // 🔴 面板本身要内边距：它直接被塞进 TabPane 里当内容，
        // 不给 padding 的话所有控件会贴着侧栏边缘，和 SearchPanel 那种
        // "外面再包一层 VBox 加 padding" 的做法不一致。
        setPadding(new Insets(10, 10, 10, 10));
        getStyleClass().add("reader-ai-panel");

        input.setPromptText("问点什么，比如「药老是哪本书的人物」");
        input.getStyleClass().add("reader-ai-field");
        askButton.getStyleClass().add("reader-ai-ask");
        askButton.setOnAction(e -> onAsk());
        input.setOnAction(e -> onAsk());

        HBox inputRow = new HBox(6, input, askButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        // 输入框吃掉全部余量，按钮保持自然宽度 —— 否则按钮会被拉宽，
        // "提问"两个字配一整条蓝色底，看起来像一个很重的按钮。
        HBox.setHgrow(input, Priority.ALWAYS);

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        statusLabel.getStyleClass().add("reader-ai-status");
        statusLabel.setWrapText(true);

        recheckButton.getStyleClass().add("reader-ai-recheck");
        recheckButton.setOnAction(e -> probeService());
        recheckButton.setVisible(false);
        recheckButton.setManaged(false);

        answerBox.setSpacing(6);
        answerBox.getStyleClass().add("reader-ai-answer-box");

        citationList.setPrefHeight(150);
        citationList.getStyleClass().add("reader-ai-citation-list");
        // ⚠️ setCellFactory 要的是 Callback（工厂函数），不是 ListCell 本身。
        // 直接 new ListCell<>() 传进去是类型不兼容 —— 编译期就报，不难发现。
        citationList.setCellFactory(listView -> new ListCell<AiModels.Citation>() {
            @Override
            protected void updateItem(AiModels.Citation item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label line = new Label("[" + item.index() + "] " + item.ref());
                line.getStyleClass().add("reader-ai-citation");
                line.setWrapText(true);
                // 🔴 点击引用要"跳章 + 高亮那句话"，不是只跳章。
                // 只跳章的话用户落地在章首，还得自己翻找 —— 引用存在的意义
                // 就是让人一眼核对，核对失败率高就等于没有引用。
                line.setOnMouseClicked(e -> {
                    host.jumpToChapter(item.chapterIndex(), lastKeyword);
                    host.setStatus("已跳到引用出处");
                });
                setText(null);
                setGraphic(line);
            }
        });
        citationPane.setCollapsible(true);
        citationPane.setExpanded(false);
        citationPane.setVisible(false);
        citationPane.setManaged(false);
        citationPane.getStyleClass().add("reader-ai-citation-pane");

        getChildren().addAll(inputRow, progress, statusLabel, answerBox, citationPane, recheckButton);
        setInputEnabled(false);
    }

    // ==================== 服务探测 ====================

    /**
     * 探测 AI 服务。
     *
     * <p>三种结果分别给三种提示（不要合并成一个"AI 不可用"）：
     * <ul>
     *   <li>探测不到 → 显示启动命令，因为用户最可能是忘了开服务</li>
     *   <li>服务活着但没配 key → 说清楚是 key 的问题，别让用户去启动服务</li>
     *   <li>就绪 → 正常可用</li>
     * </ul>
     */
    public void probeService() {
        setBusy(true);
        Thread t = new Thread(() -> {
            AiModels.Health health = client.health();
            Platform.runLater(() -> applyHealth(health));
        }, "ai-health-probe");
        t.setDaemon(true);
        t.start();
    }

    private void applyHealth(AiModels.Health health) {
        setBusy(false);
        if (health == null) {
            serviceReady = false;
            setInputEnabled(false);
            recheckButton.setVisible(true);
            recheckButton.setManaged(true);
            statusLabel.getStyleClass().add("reader-ai-status-warn");
            statusLabel.setText("AI 服务未启动。\n"
                    + "请在 qingdu-ai 目录执行：\n"
                    + AiServiceClient.START_COMMAND
                    + "\n（报 No module named 'fastapi' 说明依赖没装，见 qingdu-ai\\README.md）");
            host.setStatus("AI 服务未启动");
            return;
        }
        if (health.noApiKey()) {
            // 服务是活着的：这时候让用户去"启动服务"是纯粹的误导
            serviceReady = false;
            setInputEnabled(false);
            recheckButton.setVisible(true);
            recheckButton.setManaged(true);
            statusLabel.getStyleClass().add("reader-ai-status-warn");
            statusLabel.setText("AI 服务已启动，但未配置模型 API key。\n"
                    + "请设置 QINGDU_LLM_BASE_URL / QINGDU_LLM_API_KEY / QINGDU_LLM_MODEL"
                    + " 后重新启动服务。");
            host.setStatus("AI 服务未配置");
            return;
        }
        serviceReady = true;
        setInputEnabled(true);
        recheckButton.setVisible(false);
        recheckButton.setManaged(false);
        statusLabel.getStyleClass().remove("reader-ai-status-warn");
        statusLabel.setText("基于原文作答，答案会给出可核对的引用。");
    }

    // ==================== 提问 ====================

    private void onAsk() {
        if (!serviceReady || !busy.compareAndSet(false, true)) {
            return;
        }
        String question = input.getText() == null ? "" : input.getText().trim();
        if (question.isEmpty()) {
            setBusy(false);
            return;
        }
        Book book = host.currentBook();
        if (book == null) {
            setBusy(false);
            setWarn("请先打开一本书。");
            return;
        }

        setBusy(true);
        clearAnswer();
        statusLabel.getStyleClass().remove("reader-ai-status-warn");

        // 切片段与检索都在后台线程：读章要碰磁盘，不能卡 UI
        Thread worker = new Thread(() -> {
            List<AiModels.Chunk> chunks = buildChunks(book, question);
            Platform.runLater(() -> {
                if (chunks.isEmpty()) {
                    setBusy(false);
                    // 🔴 措辞要区分"没索引"与"召不回"，不能一律说"请建立索引"。
                    // 真机上就踩过这个：用户明明建了索引，却被反复引导去重建，
                    // 而真实原因是长问句在 FTS 的 AND 语义下召不回
                    // （见 AskKeyword 类注释）。把用户往错误方向引比报错更糟 ——
                    // 他会以为是自己操作错了。
                    setWarn("没有召回到相关原文片段。\n"
                            + "关键词检索要求书里出现过你问的词。\n"
                            + "试试：换成书里的人名、地名、门派名（越短越好），"
                            + "或先确认这本书已建立全文索引。");
                    return;
                }
                callService(question, chunks, List.of(book.id()));
            });
        }, "ai-chunk-build");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 检索 + 切片段。
     *
     * <p><b>🔴 检索词是一个"阶梯"，不是单个词。</b>
     * 见 {@link AskKeyword} 的类注释：FTS5 是 AND 语义，把整句问题当检索词时，
     * 「苏沐橙喜欢谁」会变成要求原文同时出现
     * {@code 苏沐 沐橙 橙喜 喜欢 欢谁} 五个相邻对 ——
     * 而「橙喜」这种跨词相邻对在正文里几乎不存在，于是<b>恒定 0 召回</b>。
     * 真机四本语料实测：短问句能召回 1523 章，同一批书上的长问句召回 0 章。
     *
     * <p><b>所以这里逐个试，召不回就降级到下一个候选</b>，
     * 凑够 {@link #MAX_HIT_CHAPTERS} 章立刻停 ——
     * 每试一个词都要花一次 FTS 查询（16~104 ms）加逐章随机读，不早停会卡住界面。
     *
     * <p><b>为什么不在这里做 n-gram 重排</b>：真正的重排在 Python 侧做。
     * 轻读这层只需要"大致相关"，两边都排就是两处真理。
     */
    private List<AiModels.Chunk> buildChunks(Book book, String question) {
        try {
            List<String> terms = AskKeyword.terms(question);
            if (terms.isEmpty()) {
                lastKeyword = "";
                return List.of();
            }
            var chapters = new TreeMap<Integer, List<ChapterBlock>>();
            for (String term : terms) {
                if (chapters.size() >= MAX_HIT_CHAPTERS) {
                    break;
                }
                var hitChapters = host.searchableChapters(term);
                if (hitChapters.isEmpty()) {
                    continue;
                }
                // ⚠️ 只取少数几个命中章：Python 侧会做章节打散，轻读多送
                // 只会让请求体变大，不影响最终选出的上下文
                for (int chapterIndex : hitChapters) {
                    if (chapters.size() >= MAX_HIT_CHAPTERS) {
                        break;
                    }
                    var blocks = host.chapterBlocks(chapterIndex);
                    if (blocks != null && !blocks.isEmpty()) {
                        chapters.put(chapterIndex, blocks);
                    }
                }
            }
            // 引用高亮用第一个"真的召回了章节"的词：
            // 拿整句去高亮会一个都标不出来（原文里没有整句话）。
            lastKeyword = chapters.isEmpty() ? "" : firstTermThatHit(terms);
            return ChunkSplitter.collect(book, chapters, MAX_CHUNKS, host::chapterTitle);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 找出第一个真的召回了章节的候选词。
     *
     * <p><b>为什么必须重新查一遍而不是顺便记下来</b>：{@code buildChunks}
     * 里每轮都调了 {@code searchableChapters}，把命中的那个词记下来
     * 顺手就能做。之所以没那么写，是因为高亮这个词<b>只影响界面观感</b>，
     * 而多存一个字段就多一处跨线程可变状态（面板方法都在后台线程跑）——
     * 那个字段还要被 FX 线程的 cellFactory 读。用一次廉价查询换掉一个共享状态，
     * 在这个规模下是更划算的交易：一次 FTS 查询 16~104 ms，
     * 而点一次引用的用户感知延迟远大于此。
     */
    private String firstTermThatHit(List<String> terms) {
        for (String term : terms) {
            try {
                if (!host.searchableChapters(term).isEmpty()) {
                    return term;
                }
            } catch (RuntimeException e) {
                return terms.get(0);
            }
        }
        return terms.isEmpty() ? "" : terms.get(0);
    }

    private void callService(String question, List<AiModels.Chunk> chunks, List<String> bookIds) {
        Thread t = new Thread(() -> {
            AskOutcome outcome = client.ask(question, chunks, bookIds, DEFAULT_TOP_K);
            Platform.runLater(() -> applyOutcome(outcome, chunks.size()));
        }, "ai-ask");
        t.setDaemon(true);
        t.start();
    }

    private void applyOutcome(AskOutcome outcome, int sentChunks) {
        setBusy(false);
        if (!outcome.ok()) {
            applyFailure(outcome.failure());
            return;
        }
        AiModels.AskResult r = outcome.result();

        // 🔴 answer == null 是"答不出"，不是错误 —— 界面必须给不同的提示，
        // 否则用户会以为是程序坏了
        if (!r.answered()) {
            clearAnswer();
            if (r.invalidCitations()) {
                setWarn("模型没能给出可核对的出处，换个说法试试。");
            } else {
                setWarn("没找到相关原文。\n"
                        + "试试用书名、人名等专有名词提问，或先在搜索页确认这本书已建立索引。");
            }
            return;
        }

        Label answer = new Label(r.answer());
        answer.getStyleClass().add("reader-ai-answer");
        answer.setWrapText(true);
        answerBox.getChildren().setAll(answer);

        if (!r.citations().isEmpty()) {
            citationList.getItems().setAll(r.citations());
            citationPane.setText("引用出处（" + r.citations().size() + " 条，点击跳转）");
            citationPane.setExpanded(true);
            citationPane.setVisible(true);
            citationPane.setManaged(true);
        }

        // 覆盖度：不显示它就是"沉默的错误答案"——
        // 用户搜不到时九成是"那本书没建索引"，没有这三个数字只能猜
        StringBuilder sb = new StringBuilder();
        sb.append("检索 ").append(r.searchedBooks()).append(" 本书 / 已索引 ")
                .append(r.indexedBooks()).append(" 本 / 召回 ").append(r.hits())
                .append(" 段，送出 ").append(sentChunks).append(" 段，用时 ")
                .append(r.elapsedMs()).append(" 毫秒");
        statusLabel.getStyleClass().remove("reader-ai-status-warn");
        statusLabel.setText(sb.toString());
        host.setStatus("AI 回答完成");
    }

    private void applyFailure(Failure failure) {
        clearAnswer();
        if (failure == null) {
            setWarn("AI 调用失败。");
            return;
        }
        switch (failure) {
            case SERVICE_DOWN -> {
                setWarn("AI 服务未启动或已断开。\n"
                        + "请确认它还在运行：\n"
                        + "cd qingdu-ai\n"
                        + AiServiceClient.START_COMMAND + "\n"
                        + "（首次使用需先装依赖，见 qingdu-ai\\README.md）");
                serviceReady = false;
                setInputEnabled(false);
                recheckButton.setVisible(true);
                recheckButton.setManaged(true);
            }
            case NOT_CONFIGURED -> {
                setWarn("AI 服务未配置模型 API key。\n"
                        + "请设置 QINGDU_LLM_API_KEY 等环境变量后重启服务。");
                serviceReady = false;
                setInputEnabled(false);
            }
            case LLM_ERROR -> setWarn("模型调用失败，检查网络或稍后重试。");
            case BAD_RESPONSE -> setWarn("AI 服务返回了无法解析的内容。\n"
                    + "可能是轻读与服务端版本不匹配。");
        }
    }

    // ==================== 内部小工具 ====================

    private void setInputEnabled(boolean enabled) {
        input.setDisable(!enabled);
        askButton.setDisable(!enabled);
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (busy) {
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        }
        askButton.setDisable(busy || !serviceReady);
    }

    private void setWarn(String text) {
        statusLabel.getStyleClass().add("reader-ai-status-warn");
        statusLabel.setText(text);
    }

    private void clearAnswer() {
        answerBox.getChildren().clear();
        citationList.getItems().clear();
        citationPane.setVisible(false);
        citationPane.setManaged(false);
    }

    /** 供测试与外部调用：当前服务是否可用。 */
    public boolean isServiceReady() {
        return serviceReady;
    }

    /** 供测试：模拟服务探测完成后的状态。 */
    void applyHealthForTest(AiModels.Health health) {
        applyHealth(health);
    }

    /** 供 {@code Host} 之外的场景调用（例如菜单"AI 问答"）。 */
    public void focusInput() {
        input.requestFocus();
    }
}
