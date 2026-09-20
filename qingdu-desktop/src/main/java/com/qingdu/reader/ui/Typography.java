package com.qingdu.reader.ui;

import com.qingdu.common.settings.ReaderSettings;

/**
 * 把阅读设置翻译成 JavaFX 的行内 CSS。
 *
 * <p><b>为什么字体和字号走行内样式，而不是写进 CSS 文件？</b><br>
 * 因为这两个值是<b>用户随时会改</b>的，而 CSS 文件是打包进 jar 的静态资源。
 * 想用 CSS 实现，就得在运行时动态生成一份样式表 —— 那比直接设行内样式
 * 复杂得多，还会多出一层"样式表字符串拼接"可能出的错。
 *
 * <p>反过来，<b>颜色必须走 CSS</b>（见 {@code base.css} 的 {@code -qd-*}
 * 查表颜色），因为行内样式的优先级高于样式表，一旦把颜色写死在行内，
 * 换主题就再也不起作用了。所以这里只处理"字体族 / 字号"，
 * 一个字都不碰颜色 —— 这条界线是有意划出来的。
 */
public final class Typography {

    /** 章标题相对正文字号的放大倍数。 */
    public static final double HEADING1_SCALE = 1.35;
    public static final double HEADING2_SCALE = 1.18;
    public static final double HEADING3_SCALE = 1.05;

    private Typography() {
        // 工具类不允许实例化
    }

    /**
     * 生成行内样式字符串。
     *
     * @param settings 阅读设置
     * @param sizeScale 字号倍数，正文传 1.0，标题传对应的放大倍数
     */
    public static String css(ReaderSettings settings, double sizeScale) {
        ReaderSettings s = (settings == null) ? ReaderSettings.defaults() : settings;
        StringBuilder sb = new StringBuilder(64);

        // 字体为 null 表示"跟随系统默认"，这时【不能输出 -fx-font-family】——
        // 输出一个空值会让 JavaFX 解析失败，连带整条行内样式（包括字号）一起被丢掉。
        if (s.fontFamily() != null) {
            sb.append("-fx-font-family: \"").append(escape(s.fontFamily())).append("\"; ");
        }

        long size = Math.round(s.fontSize() * sizeScale);
        sb.append("-fx-font-size: ").append(size).append("px;");
        return sb.toString();
    }

    /** 只需要字号时的快捷方法。 */
    public static String fontSizeCss(long size) {
        return "-fx-font-size: " + size + "px;";
    }

    /**
     * 转义字体名里的双引号。
     *
     * <p>字体名会被包在一对双引号里写进 CSS，所以名字内部的双引号必须转义，
     * 否则会把字符串提前截断，后面的字号设置跟着一起失效。
     * 中文字体名基本不会带引号，但"能不写防御性代码"和"已经确认不可能发生"
     * 是两回事 —— 成本只有一行。
     */
    private static String escape(String fontFamily) {
        return fontFamily.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
