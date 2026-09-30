package com.qingdu.reader.ui;

import com.qingdu.common.settings.Theme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题样式表的静态检查。
 *
 * <p><b>为什么这个测试值得存在？</b>
 * 因为这个项目里"加一套主题"要同时改两个地方：{@link Theme} 枚举加一项、
 * 再新建一个 {@code /css/theme-xxx.css}。这两处都在编译器视野之外 ——
 * <ul>
 *   <li>只加了枚举、忘了加文件：{@link ThemeStyles#stylesheetsFor} 找不到资源时
 *       <b>只打一行警告、不抛异常</b>（这是有意的，样式表缺失不该让程序崩），
 *       于是菜单里多出一个选项，点了以后界面毫无变化；</li>
 *   <li>文件加了，但少写一个 {@code -qd-*} 变量：那个地方的切换主题后<b>不动</b>，
 *       因为它会静默退回 {@code base.css} 里的兜底色。这种"大部分都对了、
 *       就差一个滚动条颜色"的问题，肉眼在四套主题之间来回切也很难发现。</li>
 * </ul>
 * 这两类都是"不报错的错"，正是最该用测试钉住的那一类。
 *
 * <p>做法是把 CSS 当纯文本解析：取 {@code -qd-xxx} 的出现，
 * 后面跟冒号的算<b>定义</b>，否则算<b>引用</b>。不需要引进 CSS 解析库 ——
 * 我们只需要变量名的集合，正则足够，而且不用维护第三方依赖。
 *
 * <p><b>没有覆盖到的</b>：颜色值本身好不好看、对比度够不够。
 * 那是审美和人眼的事，测不了。
 */
class ThemeCssCompletenessTest {

    /** 样式表里的基础结构文件，它同时承担"所有变量的兜底值"这个角色。 */
    private static final String BASE_SHEET = "/css/base.css";

    /** 一个 -qd-* 变量名的出现（不含尾部冒号）。 */
    private static final Pattern VARIABLE = Pattern.compile("-qd-[a-z0-9-]+");

    @Test
    @DisplayName("每个主题都有一张能被解析到的样式表")
    void everyThemeResolvesItsStylesheet() {
        for (Theme theme : Theme.values()) {
            String path = ThemeStyles.themeSheetPath(theme);
            assertNotNull(resource(path),
                    theme.displayName() + "（id=" + theme.id() + "）找不到样式表：" + path
                            + " —— 加主题时枚举加了、文件忘了建？");
        }
    }

    @Test
    @DisplayName("每个主题都定义了 base.css 兜底用到的全部 -qd-* 变量")
    void everyThemeDefinesEveryFallbackVariable() throws IOException {
        Set<String> base = definedVariables(read(BASE_SHEET));
        // 先断言 base.css 自己没写错，否则下面拿它当基准就一起错了
        assertTrue(base.size() >= 15, "base.css 里只解析出 " + base.size() + " 个变量，解析逻辑或文件本身有问题");

        Map<String, Set<String>> perTheme = new LinkedHashMap<>();
        for (Theme theme : Theme.values()) {
            perTheme.put(theme.id(), definedVariables(read(ThemeStyles.themeSheetPath(theme))));
        }

        for (Map.Entry<String, Set<String>> entry : perTheme.entrySet()) {
            Set<String> missing = new TreeSet<>(base);
            missing.removeAll(entry.getValue());
            assertTrue(missing.isEmpty(),
                    "主题 " + entry.getKey() + " 少定义了这些变量（会静默退回 base.css 的兜底色，"
                            + "表现为「大部分地方都跟着主题变、就差这一处不动」）：" + missing);
        }
    }

    @Test
    @DisplayName("主题文件里没有 base.css 不认识的多余变量")
    void everyThemeVariableIsKnownToBaseCss() throws IOException {
        Set<String> base = definedVariables(read(BASE_SHEET));
        for (Theme theme : Theme.values()) {
            Set<String> extra = new TreeSet<>(definedVariables(read(ThemeStyles.themeSheetPath(theme))));
            extra.removeAll(base);
            assertTrue(extra.isEmpty(),
                    "主题 " + theme.id() + " 定义了 base.css 里没有的变量："
                            + extra + " —— 要么是拼错了，要么是忘了在 base.css 里补兜底值");
        }
    }

    @Test
    @DisplayName("base.css 引用的每个变量都在它自己里面定义过")
    void baseCssDoesNotUseUndefinedVariables() throws IOException {
        String css = read(BASE_SHEET);
        Set<String> referenced = referencedVariables(css);
        referenced.removeAll(definedVariables(css));
        assertTrue(referenced.isEmpty(),
                "base.css 用了没定义的变量（JavaFX 对认不出的颜色会整条属性忽略，"
                        + "所以不报错、只是没效果）：" + referenced);
    }

    // ==================== 解析 ====================

    /**
     * 逐个扫出 {@code -qd-xxx}，按"后面紧跟着冒号"区分定义与引用。
     *
     * <p>为什么不用两个正则？因为"引用"和"定义"的差别只有后面那一个字符，
     * 用一条正则扫一遍再就地判断，比写两条互相补集的正则更不容易出错 ——
     * 后者很难保证恰好互补（少一个模式就会漏掉一类）。
     */
    private static void collect(String css, Set<String> definitions, Set<String> references) {
        Matcher matcher = VARIABLE.matcher(css);
        while (matcher.find()) {
            int cursor = matcher.end();
            while (cursor < css.length() && Character.isWhitespace(css.charAt(cursor))) {
                cursor++;
            }
            boolean isDefinition = cursor < css.length() && css.charAt(cursor) == ':';
            (isDefinition ? definitions : references).add(matcher.group());
        }
    }

    private static Set<String> definedVariables(String css) {
        Set<String> definitions = new LinkedHashSet<>();
        collect(css, definitions, new LinkedHashSet<>());
        return definitions;
    }

    private static Set<String> referencedVariables(String css) {
        Set<String> references = new LinkedHashSet<>();
        collect(css, new LinkedHashSet<>(), references);
        return references;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = ThemeCssCompletenessTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("找不到样式表资源：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static java.net.URL resource(String path) {
        return ThemeCssCompletenessTest.class.getResource(path);
    }
}
