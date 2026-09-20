package com.qingdu.store;

import com.qingdu.common.settings.ReaderSettings;
import com.qingdu.common.settings.Theme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SettingStore} 的测试。 */
class SettingStoreTest {

    @TempDir
    Path tempDir;

    private SettingStore settings;

    @BeforeEach
    void setUp() {
        settings = new SettingStore(Database.open(tempDir.resolve("test.db")));
    }

    @Test
    @DisplayName("没存过的键返回默认值")
    void missingKeyReturnsDefault() {
        assertEquals("fallback", settings.get("nothing.here", "fallback"));
        assertNull(settings.get("nothing.here", null));
    }

    @Test
    @DisplayName("同一个键重复写会覆盖，不会插出两行")
    void putOverwrites() {
        settings.put("k", "v1");
        settings.put("k", "v2");

        assertEquals("v2", settings.get("k", null));
        assertEquals(1, settings.all().size());
    }

    @Test
    @DisplayName("第一次打开程序时读出来的是默认设置")
    void emptyDatabaseYieldsDefaults() {
        ReaderSettings loaded = settings.loadSettings();

        assertEquals(ReaderSettings.defaults(), loaded);
        assertEquals(Theme.LIGHT, loaded.theme());
        assertNull(loaded.fontFamily());
    }

    @Test
    @DisplayName("设置能完整地存进去、读回来")
    void roundTrip() {
        ReaderSettings saved = new ReaderSettings(Theme.DARK, "楷体", 22, 20);

        settings.saveSettings(saved);

        assertEquals(saved, settings.loadSettings());
    }

    @Test
    @DisplayName("字体从「楷体」改回「系统默认」之后，库里不该还留着楷体")
    void switchingBackToSystemFontRemovesKey() {
        settings.saveSettings(ReaderSettings.defaults().withFontFamily("楷体"));
        assertEquals("楷体", settings.loadSettings().fontFamily());

        settings.saveSettings(ReaderSettings.defaults().withFontFamily(null));

        // 这就是 saveSettings 里"先删后写"要解决的问题：
        // 只写不删的话，reader.font.family 这条记录会一直留着，
        // 用户下次启动又会被改回楷体
        assertNull(settings.loadSettings().fontFamily());
        assertFalse(settings.all().containsKey(ReaderSettings.KEY_FONT_FAMILY));
    }

    @Test
    @DisplayName("保存设置不会顺手把别的键删掉")
    void saveSettingsKeepsUnrelatedKeys() {
        settings.put("unrelated.key", "keep me");

        settings.saveSettings(new ReaderSettings(Theme.SEPIA, "宋体", 20, 18));

        assertEquals("keep me", settings.get("unrelated.key", null));
        assertEquals(Theme.SEPIA, settings.loadSettings().theme());
    }

    @Test
    @DisplayName("库里被手改成非法值时不崩，那一项退回默认、其它项保留")
    void corruptedValueFallsBackPerField() {
        settings.put(ReaderSettings.KEY_THEME, "不存在的主题");
        settings.put(ReaderSettings.KEY_FONT_SIZE, "很大");
        settings.put(ReaderSettings.KEY_PARAGRAPH_SPACING, "18");

        ReaderSettings loaded = settings.loadSettings();

        assertEquals(Theme.LIGHT, loaded.theme(), "认不出的主题退回默认");
        assertEquals(ReaderSettings.defaults().fontSize(), loaded.fontSize(), "解析不出的数字退回默认");
        assertEquals(18, loaded.paragraphSpacing(), 1e-9, "合法的那一项要保留");
    }

    @Test
    @DisplayName("remove 能单独删一个键")
    void removeKey() {
        settings.put("k", "v");

        settings.remove("k");

        assertNull(settings.get("k", null));
    }
}
