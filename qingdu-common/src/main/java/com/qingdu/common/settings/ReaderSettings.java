package com.qingdu.common.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阅读设置 —— 用户在「视图 → 阅读设置」里能调的东西。
 *
 * <p>用 record 而不是一堆 setter，是因为它天然是"一个整体"：
 * 界面上改一项、立刻生效、存一份。不可变 + {@code withXxx()} 复制修改
 * 比可变对象更好推理 —— 不会出现"以为改了其实改的是同一个引用"。
 *
 * <p><b>关于"边界值处理"的取舍</b>：这个对象的来源是数据库，
 * 而数据库里的值可能是旧版本写的、也可能被用户手工改过（就是个 sqlite 文件）。
 * 所以紧凑构造器里做的是<b>钳制</b>（把越界值拉回合法区间）而不是<b>抛异常</b>。
 * 理由很简单：字号写成了 9999，正确的反应是"按最大字号显示"，
 * 而不是"程序打不开"。不过反过来，如果这个类是给程序员用的构造入口，
 * 抛异常会更好 —— 这两处的取舍不同，值得留意。
 */
public record ReaderSettings(
        Theme theme,
        String fontFamily,
        int fontSize,
        double paragraphSpacing
) {

    public static final int MIN_FONT_SIZE = 12;
    public static final int MAX_FONT_SIZE = 40;
    public static final double MIN_PARAGRAPH_SPACING = 0;
    public static final double MAX_PARAGRAPH_SPACING = 40;

    /** 持久化用的键名。集中在这里，避免各处写字符串导致拼错。 */
    public static final String KEY_THEME = "reader.theme";
    public static final String KEY_FONT_FAMILY = "reader.font.family";
    public static final String KEY_FONT_SIZE = "reader.font.size";
    public static final String KEY_PARAGRAPH_SPACING = "reader.paragraph.spacing";

    /**
     * 本组设置涉及的全部键。
     *
     * <p>用途是"整组覆盖保存"：先把这些键从表里删干净，
     * 再写回当前值。这样"字体改回系统默认"这种<b>从有到无</b>的变化
     * 才不会被漏掉（详见 {@code SettingStore.saveSettings}）。
     */
    public static final java.util.List<String> KNOWN_KEYS = java.util.List.of(
            KEY_THEME, KEY_FONT_FAMILY, KEY_FONT_SIZE, KEY_PARAGRAPH_SPACING);

    public ReaderSettings {
        theme = (theme == null) ? Theme.defaultTheme() : theme;

        // 空字符串统一成 null，表示"跟随系统默认字体"。
        // 用 null 而不是 ""，是因为 JavaFX 的 -fx-font-family 不认空串。
        if (fontFamily != null && fontFamily.isBlank()) {
            fontFamily = null;
        }

        fontSize = (int) clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
        paragraphSpacing = clamp(paragraphSpacing, MIN_PARAGRAPH_SPACING, MAX_PARAGRAPH_SPACING);
    }

    public static ReaderSettings defaults() {
        return new ReaderSettings(Theme.defaultTheme(), null, 17, 14);
    }

    // ==================== 复制修改 ====================

    public ReaderSettings withTheme(Theme newTheme) {
        return new ReaderSettings(newTheme, fontFamily, fontSize, paragraphSpacing);
    }

    public ReaderSettings withFontFamily(String newFamily) {
        return new ReaderSettings(theme, newFamily, fontSize, paragraphSpacing);
    }

    public ReaderSettings withFontSize(int newSize) {
        return new ReaderSettings(theme, fontFamily, newSize, paragraphSpacing);
    }

    public ReaderSettings withParagraphSpacing(double newSpacing) {
        return new ReaderSettings(theme, fontFamily, fontSize, newSpacing);
    }

    // ==================== 与键值表互转 ====================

    /**
     * 转成"键 → 值"的扁平表，直接写进 {@code setting} 表。
     *
     * <p>{@code fontFamily} 为 null 时<b>不写入这个键</b>，
     * 而不是写一个空值。这样"没设置过"和"设置成系统默认"是同一种状态，
     * 读回来时自然就落到 null 分支。
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(KEY_THEME, theme.id());
        if (fontFamily != null) {
            map.put(KEY_FONT_FAMILY, fontFamily);
        }
        map.put(KEY_FONT_SIZE, String.valueOf(fontSize));
        map.put(KEY_PARAGRAPH_SPACING, String.valueOf(paragraphSpacing));
        return map;
    }

    /**
     * 从键值表还原。
     *
     * <p><b>每一项都单独兜底</b>：缺项或值非法时只让那一项退回默认值，
     * 不影响其它项。比起"整份配置有一个字段坏了就全丢"，
     * 这个粒度对用户友好得多 —— 至少字号不会因为主题名被写错而一起被重置。
     */
    public static ReaderSettings from(Map<String, String> map) {
        ReaderSettings def = defaults();
        if (map == null || map.isEmpty()) {
            return def;
        }
        return new ReaderSettings(
                Theme.fromId(map.get(KEY_THEME), def.theme()),
                map.containsKey(KEY_FONT_FAMILY) ? map.get(KEY_FONT_FAMILY) : def.fontFamily(),
                parseInt(map.get(KEY_FONT_SIZE), def.fontSize()),
                parseDouble(map.get(KEY_PARAGRAPH_SPACING), def.paragraphSpacing()));
    }

    /** 界面上显示字体名用：null 换成"系统默认"。 */
    public String fontFamilyLabel() {
        return fontFamily == null ? "系统默认" : fontFamily;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
