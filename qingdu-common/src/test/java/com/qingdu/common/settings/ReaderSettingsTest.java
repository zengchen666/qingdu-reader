package com.qingdu.common.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReaderSettings} 与 {@link Theme} 的测试。 */
class ReaderSettingsTest {

    // ==================== Theme ====================

    @Test
    @DisplayName("主题按 id 解析，认不出就用兜底值")
    void themeFromId() {
        assertEquals(Theme.DARK, Theme.fromId("dark"));
        assertEquals(Theme.SEPIA, Theme.fromId("  SEPIA  "), "应该容忍大小写和空白");
        assertEquals(Theme.LIGHT, Theme.fromId("没这个主题"));
        assertEquals(Theme.GREEN, Theme.fromId(null, Theme.GREEN), "null 时用调用方给的兜底值");
    }

    @Test
    @DisplayName("主题 id 和显示名都非空，且 id 不重复")
    void themeMetadataIsSane() {
        for (Theme theme : Theme.values()) {
            assertFalse(theme.id().isBlank());
            assertFalse(theme.displayName().isBlank());
        }
        assertEquals(Theme.values().length,
                java.util.Arrays.stream(Theme.values()).map(Theme::id).distinct().count(),
                "id 是持久化的键，不能重复");
    }

    // ==================== 默认值 ====================

    @Test
    @DisplayName("默认设置的字体是「跟随系统」")
    void defaults() {
        ReaderSettings defaults = ReaderSettings.defaults();

        assertEquals(Theme.LIGHT, defaults.theme());
        assertNull(defaults.fontFamily());
        assertEquals("系统默认", defaults.fontFamilyLabel());
        assertTrue(defaults.fontSize() >= ReaderSettings.MIN_FONT_SIZE);
        assertTrue(defaults.fontSize() <= ReaderSettings.MAX_FONT_SIZE);
    }

    // ==================== 边界钳制 ====================

    @Test
    @DisplayName("越界的字号被夹到合法区间，而不是抛异常")
    void fontSizeIsClamped() {
        // 这一条是刻意的取舍：值来自数据库，可能被手改过。
        // 字号写成了 9999，正确的反应是"按最大字号显示"，而不是"程序打不开"
        assertEquals(ReaderSettings.MAX_FONT_SIZE,
                new ReaderSettings(null, null, 9999, 10).fontSize());
        assertEquals(ReaderSettings.MIN_FONT_SIZE,
                new ReaderSettings(null, null, 2, 10).fontSize());
    }

    @Test
    @DisplayName("段间距同样被钳制")
    void spacingIsClamped() {
        assertEquals(ReaderSettings.MAX_PARAGRAPH_SPACING,
                new ReaderSettings(null, null, 16, 999).paragraphSpacing(), 1e-9);
        assertEquals(ReaderSettings.MIN_PARAGRAPH_SPACING,
                new ReaderSettings(null, null, 16, -5).paragraphSpacing(), 1e-9);
    }

    @Test
    @DisplayName("空白的字体名统一成 null")
    void blankFontFamilyBecomesNull() {
        // 统一成 null 很关键：JavaFX 的 -fx-font-family 遇到空串会解析失败，
        // 连带整条行内样式（包括字号）一起失效
        assertNull(new ReaderSettings(null, "   ", 17, 14).fontFamily());
    }

    // ==================== 复制修改 ====================

    @Test
    @DisplayName("withXxx 返回新对象，原对象不动")
    void withMethodsAreImmutable() {
        ReaderSettings base = ReaderSettings.defaults();

        ReaderSettings bigger = base.withFontSize(base.fontSize() + 5);
        ReaderSettings dark = base.withTheme(Theme.DARK);

        assertNotEquals(base.fontSize(), bigger.fontSize());
        assertEquals(base.fontSize(), ReaderSettings.defaults().fontSize(), "原对象不该被改");
        assertEquals(Theme.DARK, dark.theme());
        assertEquals(Theme.LIGHT, base.theme());
        // 改一项不该影响其它项
        assertEquals(base.fontSize(), dark.fontSize());
        assertEquals(base.paragraphSpacing(), bigger.paragraphSpacing(), 1e-9);
    }

    // ==================== 与键值表互转 ====================

    @Test
    @DisplayName("toMap / from 往返之后完全相等")
    void mapRoundTrip() {
        ReaderSettings original = new ReaderSettings(Theme.GREEN, "楷体", 21, 16);

        ReaderSettings restored = ReaderSettings.from(original.toMap());

        assertEquals(original, restored);
    }

    @Test
    @DisplayName("字体为 null 时不写这个键，读回来自然还是 null")
    void nullFontFamilyIsOmitted() {
        ReaderSettings settings = ReaderSettings.defaults().withFontFamily(null);

        Map<String, String> map = settings.toMap();

        // "没设置过"和"设置成系统默认"是同一种状态，没必要存两条不同的表示
        assertFalse(map.containsKey(ReaderSettings.KEY_FONT_FAMILY));
        assertNull(ReaderSettings.from(map).fontFamily());
    }

    @Test
    @DisplayName("空表读出默认设置")
    void emptyMapYieldsDefaults() {
        assertEquals(ReaderSettings.defaults(), ReaderSettings.from(Map.of()));
        assertEquals(ReaderSettings.defaults(), ReaderSettings.from(null));
    }

    @Test
    @DisplayName("缺项只让那一项退回默认，不影响其它项")
    void partialMapKeepsTheRest() {
        Map<String, String> partial = new HashMap<>();
        partial.put(ReaderSettings.KEY_THEME, "dark");
        partial.put(ReaderSettings.KEY_FONT_SIZE, "24");
        // 故意不提供字号字体和段间距

        ReaderSettings loaded = ReaderSettings.from(partial);

        assertEquals(Theme.DARK, loaded.theme());
        assertEquals(24, loaded.fontSize());
        assertNull(loaded.fontFamily());
        assertEquals(ReaderSettings.defaults().paragraphSpacing(), loaded.paragraphSpacing(), 1e-9);
    }

    @Test
    @DisplayName("已知键清单覆盖 toMap 里所有非空项")
    void knownKeysCoverToMap() {
        ReaderSettings full = new ReaderSettings(Theme.DARK, "宋体", 20, 18);

        // SettingStore.saveSettings 靠 KNOWN_KEYS 做"先删后写"，
        // 少列一个键就会留下脏数据，所以这里把两者对齐关系锁住
        assertTrue(ReaderSettings.KNOWN_KEYS.containsAll(full.toMap().keySet()));
        assertEquals(4, ReaderSettings.KNOWN_KEYS.size());
    }
}
