package com.qingdu.reader;

import com.qingdu.reader.ui.ReaderView;
import com.qingdu.store.Database;
import com.qingdu.store.QingduStore;
import com.qingdu.store.StoreException;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 轻读阅读器主窗口。
 *
 * <p>这个类只做四件事：打开数据库、建立舞台、装配界面、把命令行参数传进去。
 * 真正的界面逻辑都搬到了 {@link ReaderView} —— 保持"启动类"足够薄，
 * 是为了让界面代码能被单独测试和复用（比如以后加一个多标签页版本，
 * 直接 new 多个 ReaderView 就行，不用动启动流程）。
 *
 * <p>继承 {@code Application} 是 JavaFX 的固定要求，它规定了应用的生命周期：
 * {@code init → start → (运行中) → stop}。我们只需要实现 {@link #start(Stage)}
 * 和 {@link #stop()}。
 */
public class QingduApplication extends Application {

    private static final String APP_TITLE = "轻读阅读器";
    private static final double WINDOW_WIDTH = 1100;
    private static final double WINDOW_HEIGHT = 740;

    /**
     * 窗口图标的尺寸档位，要跟 {@code scripts/gen-icon.py} 里生成的 PNG 文件名对应。
     * 从大到小排列，让 JavaFX 优先挑尺寸够用的那几张。
     */
    private static final String[] ICON_SIZES = {"256", "128", "64", "48", "32", "24", "16"};

    private QingduStore store;
    private ReaderView readerView;

    @Override
    public void start(Stage stage) {
        // 数据库打不开时仍然让程序跑起来：不保存进度是遗憾，
        // 但因为一个本地文件的问题就让人读不了书，那是本末倒置。
        store = openStoreOrWarn();

        readerView = new ReaderView(store);
        Scene scene = new Scene(readerView, WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.setTitle(APP_TITLE);
        stage.setScene(scene);
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        applyWindowIcons(stage);
        stage.centerOnScreen();

        // 样式表要装到 Scene 上，而 Scene 是围绕 ReaderView 创建的，
        // 所以这一步只能在组装完成之后回调
        readerView.onSceneReady();

        stage.show();

        // 支持"把文件拖到 exe 图标上"启动 / 或用 java -jar book.txt 直接打开
        openFromArguments();
    }

    /**
     * 给窗口装上图标。
     *
     * <p><b>注意这里换的不是 exe 的图标。</b>一个打包成 jpackage 程序的 JavaFX 应用有两处
     * 互相独立的图标，改一处另一处不会跟着变：
     * <ul>
     *   <li>这里管的是<b>运行时的窗口</b> —— 标题栏、任务栏、Alt-Tab 切换器；</li>
     *   <li>{@code exe} 文件自身在资源管理器里的样子，由打包脚本的
     *       {@code jpackage --icon assets/app.ico} 决定，跟这段代码无关。</li>
     * </ul>
     * 只改代码，别人拿到的 exe 在资源管理器里还是默认图标；只改 ico，开发模式下的
     * 任务栏又还是 JavaFX 图标。两处都设才一致。
     *
     * <p>一次给多档尺寸，让 JavaFX 按当前屏幕缩放挑最合适的那张。只给一张大图的话，
     * 在高 DPI 屏上会被拉伸得发虚 —— 这跟网页里只给一张 1024px 图当 favicon 是同一个问题。
     *
     * <p>用 {@code getResource} 拿 URL 交给 {@link Image}，而不是 {@code getResourceAsStream}：
     * 后者交给 {@code new Image(InputStream)} 时，万一以后改成后台加载，
     * 读到的就是一个已经被关掉的流。让 JavaFX 自己开流最省心。
     *
     * <p>图标资源打包在 jar 里，所以开发模式（{@code mvn javafx:run}）和打包版用的是同一份，
     * 不需要为两种运行方式分别处理路径。
     */
    private void applyWindowIcons(Stage stage) {
        for (String size : ICON_SIZES) {
            URL url = QingduApplication.class.getResource("/icon/icon-" + size + ".png");
            // 找不到就跳过。这段运行在 start() 里，一旦抛异常整个窗口都起不来 ——
            // 图标只是锦上添花，不值得让它升级成"程序打不开"。
            if (url != null) {
                stage.getIcons().add(new Image(url.toExternalForm()));
            }
        }
    }

    /**
     * 关窗口时把最后一段阅读进度落库。
     *
     * <p>{@code stop()} 是 JavaFX 在退出前给的最后一个回调。
     * 界面里的进度回写是"延迟 700 毫秒"的防抖设计，用户如果刚滚动完就关窗口，
     * 那一次回写还没执行 —— 不在这里补一下，最后一段进度就丢了。
     */
    @Override
    public void stop() {
        if (readerView != null) {
            readerView.flushProgress();
        }
    }

    /**
     * 打开本地数据库，失败时给出解释并返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常，是因为"没有存储层"是一种合法的运行状态 ——
     * {@code ReaderView} 里所有持久化调用都会挡掉它。
     * 反过来，如果在这里抛异常，整个窗口都起不来，用户连书都读不了。
     */
    private QingduStore openStoreOrWarn() {
        try {
            return QingduStore.openDefault();
        } catch (StoreException e) {
            Alert alert = new Alert(Alert.AlertType.WARNING, null, ButtonType.OK);
            alert.setTitle(APP_TITLE);
            alert.setHeaderText("无法启用本地数据存储");
            alert.setContentText("""
                    阅读进度、书签和阅读设置这次不会被保存，但看书功能一切正常。

                    原因：""" + e.getMessage() + """

                    数据文件默认放在：""" + Database.defaultDataDir());
            alert.showAndWait();
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 处理启动参数。
     *
     * <p>{@code getParameters().getRaw()} 拿到的是原始参数列表。
     * 这里只取第一个能当文件路径用的参数 —— 多文件同时打开要等到
     * 书架功能做好之后再说，现在做只会引入一堆"先开哪本"的判定逻辑。
     *
     * <p>整段用 try-catch 包住：启动参数来自外部，可能是任意字符串。
     * 如果它不是合法路径，{@code Path.of} 会抛 InvalidPathException，
     * 而这里是在 {@code start()} 里调用的 —— 一旦抛出，整个窗口都起不来。
     * 参数错误只是一件小事，不值得让它升级成"程序打不开"。
     */
    private void openFromArguments() {
        List<String> args = getParameters().getRaw();
        if (args.isEmpty()) {
            return;
        }
        try {
            Path candidate = Path.of(args.get(0));
            if (Files.isRegularFile(candidate)) {
                readerView.open(candidate);
            }
        } catch (RuntimeException e) {
            System.err.println("启动参数不是有效的文件路径，已忽略：" + args.get(0));
        }
    }
}
