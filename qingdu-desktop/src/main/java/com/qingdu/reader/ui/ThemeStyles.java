package com.qingdu.reader.ui;

import com.qingdu.common.settings.Theme;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link Theme} 翻译成样式表，并负责安装到界面上。
 *
 * <p><b>为什么需要这个类？</b><br>
 * {@code Theme} 枚举住在最底层的 {@code qingdu-common} 模块里，
 * 那个模块不知道"CSS"是什么东西（也不该知道）。
 * 于是"哪个主题对应哪个文件"这种纯界面的事，就得由桌面端自己来回答 ——
 * 这个类就是那个回答。
 *
 * <p><b>为什么要缓存？</b><br>
 * {@code getResource} 每次都要走一遍类加载器的资源查找。
 * 切主题时会反复调用，缓存一下更省事。
 * 缓存同时把"资源不存在"这个结果也记住了，避免每次都打一遍警告日志。
 */
public final class ThemeStyles {

    private static final String BASE_SHEET = "/css/base.css";

    private static final String THEME_SHEET_PREFIX = "/css/theme-";

    /** key = 资源路径，value = 可用的 URL；值为 null 表示该资源不存在。 */
    private static final Map<String, String> CACHE = new HashMap<>();

    private ThemeStyles() {
        // 工具类不允许实例化
    }

    /**
     * 某个主题需要的全部样式表，顺序是「结构 → 配色」。
     *
     * <p>顺序有意义：主题表要放在后面，它才能覆盖 {@code base.css} 里
     * 那套兜底配色。JavaFX 对同一个选择器取最后一条匹配的规则。
     */
    public static List<String> stylesheetsFor(Theme theme) {
        Theme effective = (theme == null) ? Theme.defaultTheme() : theme;
        List<String> sheets = new ArrayList<>(2);
        String base = resolve(BASE_SHEET);
        if (base != null) {
            sheets.add(base);
        }
        String themeSheet = resolve(THEME_SHEET_PREFIX + effective.id() + ".css");
        if (themeSheet != null) {
            sheets.add(themeSheet);
        }
        return sheets;
    }

    /** 给整个窗口换肤。 */
    public static void apply(Scene scene, Theme theme) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().setAll(stylesheetsFor(theme));
    }

    /**
     * 给对话框换肤。
     *
     * <p><b>这一步很容易漏</b>：{@code Alert} 之类的对话框有<b>自己的 Scene</b>，
     * 不会继承主窗口的样式表。不单独加一遍的话，夜间主题下会突然弹出一个
     * 惨白的对话框 —— 而且这种不一致只有在真正触发错误提示时才会被发现。
     */
    public static void apply(DialogPane pane, Theme theme) {
        if (pane == null) {
            return;
        }
        pane.getStylesheets().setAll(stylesheetsFor(theme));
    }

    /**
     * 把资源路径解析成 URL。找不到时返回 {@code null} 并打一行警告 ——
     * <b>不抛异常</b>。理由：样式表缺失只会让界面难看，
     * 但如果在换肤时抛异常，程序会直接崩，代价完全不成比例。
     */
    private static String resolve(String path) {
        if (CACHE.containsKey(path)) {
            return CACHE.get(path);
        }
        URL url = ThemeStyles.class.getResource(path);
        String external = (url == null) ? null : url.toExternalForm();
        if (external == null) {
            System.err.println("[轻读] 找不到样式表资源：" + path + "，界面将使用默认外观");
        }
        CACHE.put(path, external);
        return external;
    }
}
