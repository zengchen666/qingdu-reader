package com.qingdu.store;

import com.qingdu.common.settings.ReaderSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置存储 —— 一个朴素的"键 → 值"表。
 *
 * <p><b>为什么设置用键值表，而不是给每个设置项建一个字段？</b><br>
 * 因为设置项会不断增加（以后还有页宽、自动滚动速度、快捷键……），
 * 每加一项就要改表结构。键值表把"结构的演进"变成了"多插一行"，
 * 程序不需要为了新增一个设置项而升级数据库。
 *
 * <p>代价是<b>类型和校验都得自己扛</b>：数据库里所有值都是 TEXT。
 * 所以这个类对外提供两套接口：
 * <ul>
 *   <li>底层的 {@link #get}/{@link #put}，给简单场景用；</li>
 *   <li>上层的 {@link #loadSettings()}/{@link #saveSettings}，
 *       负责和 {@link ReaderSettings} 互相转换，
 *       类型转换和非法值兜底都在那个 record 的构造器里完成。</li>
 * </ul>
 */
public class SettingStore {

    private final Database database;

    public SettingStore(Database database) {
        if (database == null) {
            throw new IllegalArgumentException("Database 不能为空");
        }
        this.database = database;
    }

    public String get(String key, String defaultValue) {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : defaultValue;
            }
        } catch (SQLException e) {
            throw new StoreException("读取设置失败（" + key + "）：" + e.getMessage(), e);
        }
    }

    public void put(String key, String value) {
        String sql = """
                INSERT INTO setting (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("写入设置失败（" + key + "）：" + e.getMessage(), e);
        }
    }

    /** 全部设置项，供"关于 / 诊断"之类的地方展示。 */
    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT key, value FROM setting ORDER BY key");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("key"), rs.getString("value"));
            }
            return map;
        } catch (SQLException e) {
            throw new StoreException("读取全部设置失败：" + e.getMessage(), e);
        }
    }

    public void remove(String key) {
        try (Connection conn = database.connection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException("删除设置失败（" + key + "）：" + e.getMessage(), e);
        }
    }

    // ==================== 阅读设置 ====================

    /**
     * 读取阅读设置。任何一项缺失或非法都会退回默认值，不会抛异常。
     */
    public ReaderSettings loadSettings() {
        return ReaderSettings.from(all());
    }

    /**
     * 保存阅读设置。
     *
     * <p><b>为什么要先删后写？</b><br>
     * {@link ReaderSettings#toMap()} 在字体为"系统默认"时
     * <b>不会输出字体那个键</b>。如果只做写入，用户把字体从"楷体"改回"系统默认"后，
     * 数据库里那条 {@code reader.font.family=楷体} 还留着，下次启动又会变回楷体。
     * 所以这里把"这一组设置里已知的所有键"先清掉，再写新值 ——
     * 删和写放在同一个事务里，保证不会出现中间态。
     */
    public void saveSettings(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        Map<String, String> values = settings.toMap();
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM setting WHERE key = ?")) {
                    for (String key : ReaderSettings.KNOWN_KEYS) {
                        del.setString(1, key);
                        del.addBatch();
                    }
                    del.executeBatch();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO setting (key, value, updated_at) VALUES (?, ?, ?)")) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        ins.setString(1, entry.getKey());
                        ins.setString(2, entry.getValue());
                        ins.setLong(3, now);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("保存阅读设置失败：" + e.getMessage(), e);
        }
    }
}
