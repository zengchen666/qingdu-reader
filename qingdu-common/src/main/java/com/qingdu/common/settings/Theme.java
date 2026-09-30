package com.qingdu.common.settings;

/**
 * 阅读主题。
 *
 * <p>枚举里只放"这个主题叫什么"，<b>刻意不放 CSS 文件名</b>。
 * 因为 {@code qingdu-common} 是最底层模块，它不知道界面长什么样，
 * 更不该知道有个叫 CSS 的东西存在。样式表路径由桌面端自己映射
 * （见 {@code com.qingdu.reader.ui.ThemeStyles}）。
 *
 * <p>{@code id} 是持久化用的稳定键：中文名可以随时改文案，
 * 但 id 一旦写进数据库就不能变了，否则用户保存的主题会被重置。
 *
 * <p><b>声明顺序 = 主题菜单里的显示顺序</b>，按「浅色组 → 深色组」排。
 * 顺序可以随便调（持久化认的是 {@code id}，不是 ordinal），
 * 但保持这个分组能让菜单一眼看出哪些是亮底、哪些是暗底。
 */
public enum Theme {

    /** 日间：白底深字，默认主题。 */
    LIGHT("light", "日间"),

    /**
     * 护眼：灰绿底，长时间阅读更舒服。
     *
     * <p>底色是<b>降过饱和度</b>的 —— 早期版本用的是饱和绿（{@code #d3e8d5}），
     * 实测反馈"太绿了"，反而抢注意力。现在往灰里调，只留一点绿意。
     */
    GREEN("green", "护眼"),

    /** 雾蓝：冷调浅灰底，低饱和。给"不要暖黄、也不要绿色"的人一个中性选择。 */
    MIST("mist", "雾蓝"),

    /** 羊皮纸：米黄底，模拟纸质书的观感。 */
    SEPIA("sepia", "羊皮纸"),

    /** 夜间：深灰底浅字，关灯后看不刺眼。 */
    DARK("dark", "夜间"),

    /**
     * 墨黑：接近纯黑的底，配压低亮度的浅字。
     *
     * <p>和「夜间」的区别是<b>对比度和省电</b>：夜间用的是深灰（{@code #1e1f22}），
     * 墨黑压到 {@code #0d0d0f}，OLED 屏上黑像素不发光，更省电、对比更硬。
     * 代价是长时间盯着会比夜间累一些 —— 所以两者并存，让用户自己选。
     */
    INK("ink", "墨黑");

    private final String id;
    private final String displayName;

    Theme(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Theme defaultTheme() {
        return LIGHT;
    }

    /**
     * 按 id 找主题。
     *
     * <p>用"宽松解析 + 兜底"而不是抛异常，是因为它的输入来自数据库 ——
     * 那是一个可能被用户手改、也可能来自旧版本程序的地方。
     * 为了一个拼错的主题名就让程序起不来，是不值得的。
     */
    public static Theme fromId(String id) {
        return fromId(id, defaultTheme());
    }

    public static Theme fromId(String id, Theme fallback) {
        if (id != null) {
            for (Theme theme : values()) {
                if (theme.id.equalsIgnoreCase(id.trim())) {
                    return theme;
                }
            }
        }
        return fallback;
    }
}
