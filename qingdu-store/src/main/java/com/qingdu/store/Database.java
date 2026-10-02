package com.qingdu.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库入口：负责"数据库文件在哪、表长什么样、怎么拿连接"。
 *
 * <p><b>为什么不搞连接池？</b><br>
 * 这是个单用户桌面程序，全部数据操作加起来也就每秒几次，而且 SQLite 本身就是
 * 进程内嵌的，打开一个连接的开销比 TCP 数据库小几个数量级（本质就是打开一次文件）。
 * 引入 HikariCP 只会多一堆配置和一类新的故障模式（池满了、连接泄漏），
 * 换不来任何可感知的收益。所以这里采用最简单的策略：
 * <b>每次操作现开一个连接，用完立刻关</b>（见 {@link #connection()}）。
 *
 * <p>这么做还顺手解决了一个并发问题：{@link java.sql.Connection} 不是线程安全的，
 * 而本项目的建索引跑在后台线程、界面操作跑在 JavaFX 应用线程。
 * 每次操作各自开连接，就不存在"两个线程共用一个 Connection"的可能。
 */
public final class Database {

    /** 当前 schema 版本，写进 SQLite 内置的 {@code user_version} 里。 */
    private static final int SCHEMA_VERSION = 2;

    /** 数据库文件所在目录的名字，放在用户主目录下。 */
    private static final String DATA_DIR_NAME = ".qingdu-reader";

    private static final String DB_FILE_NAME = "library.db";

    /** 覆盖数据目录的系统属性，测试靠它把数据库丢到临时目录去。 */
    private static final String DATA_DIR_PROPERTY = "qingdu.data.dir";

    private final Path file;

    private Database(Path file) {
        this.file = file;
    }

    /**
     * 打开（不存在则创建）指定路径的数据库，并确保表结构就绪。
     */
    public static Database open(Path dbFile) {
        if (dbFile == null) {
            throw new StoreException("数据库文件路径不能为空");
        }
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new StoreException("无法创建数据目录：" + parent, e);
            }
        }
        Database database = new Database(dbFile.toAbsolutePath());
        database.initSchema();
        return database;
    }

    /**
     * 打开默认位置的数据库：{@code <用户主目录>/.qingdu-reader/library.db}。
     *
     * <p>之所以不放在程序自己的目录下，是因为程序可能是从
     * {@code Program Files} 或者一个只读的共享目录启动的 ——
     * 那些位置写不进去。用户主目录则一定可写，而且卸载程序时
     * 也不会把用户攒了很久的阅读进度一起删掉。
     *
     * <p>需要换位置时（测试、或者想做成"便携版"），
     * 加启动参数 {@code -Dqingdu.data.dir=某个目录} 即可。
     */
    public static Database openDefault() {
        return open(defaultDataDir().resolve(DB_FILE_NAME));
    }

    /** 解析数据目录：优先用系统属性覆盖，否则用用户主目录。 */
    public static Path defaultDataDir() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home"), DATA_DIR_NAME);
    }

    // ==================== 连接 ====================

    /**
     * 取一个新连接。<b>调用方负责关闭</b>（用 try-with-resources）。
     *
     * <p>每次都会重新设置 PRAGMA，因为其中一部分（比如外键开关）
     * 是<b>连接级</b>而不是数据库级的，不会跟着文件走。
     */
    public Connection connection() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = conn.createStatement()) {
                // WAL 模式：读写不互相堵塞。对阅读器这种"一边在后台建索引、
                // 一边在界面上写进度"的场景很重要。
                st.execute("PRAGMA journal_mode = WAL");
                // SQLite 默认不强制外键约束 —— 这个开关必须每个连接都打开，
                // 否则删书时留下的书签不会被级联清除。
                st.execute("PRAGMA foreign_keys = ON");
                // 万一真的撞上写锁，等一会儿再报错，而不是立刻失败
                st.execute("PRAGMA busy_timeout = 3000");
            }
            return conn;
        } catch (SQLException e) {
            throw new StoreException("无法连接数据库：" + file + "（" + e.getMessage() + "）", e);
        }
    }

    public Path file() {
        return file;
    }

    // ==================== 表结构 ====================

    /**
     * 建表 + 迁移。
     *
     * <p><b>整体结构是"基线表 + 迁移链"，而不是"直接建最新表"。</b>
     * {@link #createBaselineTables()} 里的 DDL 是 <b>v1 形态</b>、<b>永远不要改</b>；
     * 从 v1 往后的每一步都在 {@link #migrate} 里。这么分是为了让
     * <b>新库和老库走完全同一条代码路径</b>：新库的 {@code user_version} 是 0，
     * 照样先建 v1 形态的表、再被迁移升到最新版。
     *
     * <p>代价是新库会多做一次 {@code ALTER TABLE}（几毫秒）。
     * 换来的是"只有一条路径" —— 如果分成"新库直接建最新表"和"老库走迁移"两条，
     * 就得为两条路径各写一套测试，而且以后每加一个字段都要想一遍"新库走哪条"。
     *
     * <p>全部 DDL 与迁移放在<b>同一个事务</b>里：进程在
     * {@code ALTER TABLE} 之后、{@code user_version = 2} 之前被杀的话，
     * 下次启动会回滚到干净状态重新来，不会卡在"改了一半"的库上。
     */
    private void initSchema() {
        try (Connection conn = connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                createBaselineTables(st);
                int current = readUserVersion(conn);
                if (current < 1) {
                    // 0 → 1：v1 就是基线本身，没有需要改的东西。
                    // 这个分支留着是为了让版本号与迁移步骤一一对应 ——
                    // 将来有人问"v1 的库能不能直接用"，答案是"能，它就是基线"。
                    current = 1;
                }
                if (current < 2) {
                    migrateV1ToV2(conn, st);
                }
                st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                conn.commit();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw new StoreException("初始化数据库表结构失败：" + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new StoreException("初始化数据库表结构失败（无法连接数据库）：" + e.getMessage(), e);
        }
    }

    /**
     * 基线表（v1 形态）。
     *
     * <p>🔴 <b>这里的 DDL 是历史快照，不要再改。</b>
     * 所有新版本要加的列 / 表，都去 {@link #migrate} 里加。
     * 改这里等于让"老用户升级"和"新用户安装"走出两种不同的库结构。
     *
     * <p>全部用 {@code CREATE TABLE IF NOT EXISTS}，所以这个方法是<b>幂等</b>的 ——
     * 每次启动都跑一遍，已有数据不受影响。这是最简单也最不容易出错的做法：
     * 不引入 Flyway / Liquibase 这类迁移框架，就为了让一个单文件桌面程序
     * 能自己把表建好。
     */
    private void createBaselineTables(Statement st) throws SQLException {
        st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS book (
                    id                 TEXT    PRIMARY KEY,
                    path               TEXT    NOT NULL,
                    title              TEXT    NOT NULL,
                    author             TEXT,
                    format             TEXT    NOT NULL DEFAULT 'TXT',
                    chapter_count      INTEGER NOT NULL DEFAULT 0,
                    added_at           INTEGER NOT NULL,
                    last_chapter_index INTEGER NOT NULL DEFAULT 0,
                    last_chapter_title TEXT,
                    last_scroll_ratio  REAL    NOT NULL DEFAULT 0,
                    last_read_at       INTEGER NOT NULL
                )""");

        // 书是"按路径"找的，但刻意不加 UNIQUE：
        // BookId 在文件不存在时会退化成"绝对路径归一化"，而文件后来又出现了
        // 就可能算出另一个 ID。加了唯一约束会让这种边缘情况直接抛异常写不进去，
        // 得不偿失。代价是极小概率下同一个路径出现两行，可以接受。
        st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_book_path ON book(path)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_book_last_read ON book(last_read_at DESC)");

        st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bookmark (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    book_id       TEXT    NOT NULL REFERENCES book(id) ON DELETE CASCADE,
                    chapter_index INTEGER NOT NULL,
                    chapter_title TEXT    NOT NULL,
                    scroll_ratio  REAL    NOT NULL DEFAULT 0,
                    note          TEXT,
                    created_at    INTEGER NOT NULL
                )""");

        // 书签永远是"按某本书 + 按位置"查的，索引就按这个组合建
        st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_bookmark_book "
                + "ON bookmark(book_id, chapter_index, scroll_ratio)");

        st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS setting (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
    }

    /**
     * v1 → v2：分组、阅读时长。
     *
     * <p><b>为什么 {@code book} 表加列要写成"检查 → 没有才 ALTER"？</b>
     * {@code ALTER TABLE ADD COLUMN} 撞上已有的列会直接抛
     * {@code duplicate column name}，而"用户库里已经有这一列"是完全可能的正常情况 ——
     * 上一版程序崩在 {@code ALTER} 之后、{@code user_version} 之前，
     * 这次启动就会撞上。多查一次 {@code PRAGMA table_info} 换掉一种崩溃，不亏。
     *
     * <p>新增的 {@code reading_session} 不覆盖任何列，所以没有这个风险。
     */
    private void migrateV1ToV2(Connection conn, Statement st) throws SQLException {
        if (!hasColumn(conn, "book", "group_name")) {
            // 刻意不加索引：书库是"几十到几百本"的量级，全表扫一遍的代价可以忽略，
            // 而多一个索引就多一处"建库时忘了建索引"的失败点。等真的攒到几千本再说。
            st.executeUpdate("ALTER TABLE book ADD COLUMN group_name TEXT");
        }
        if (!hasColumn(conn, "book", "reading_millis")) {
            st.executeUpdate("ALTER TABLE book ADD COLUMN reading_millis INTEGER NOT NULL DEFAULT 0");
        }

        st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reading_session (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    book_id    TEXT    NOT NULL REFERENCES book(id) ON DELETE CASCADE,
                    started_at INTEGER NOT NULL,
                    ended_at   INTEGER NOT NULL,
                    millis     INTEGER NOT NULL
                )""");
        // "这本书最近读了多久"是唯一会高频问的问题
        st.executeUpdate("CREATE INDEX IF NOT EXISTS ix_session_book ON reading_session(book_id)");
    }

    /** 这张表里有没有这一列？（{@code PRAGMA table_info} 的第 2 列是列名） */
    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString(2))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 读出库当前的 schema 版本；读不到当 0 处理。 */
    private static int readUserVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 回滚失败已经无能为力，不能让它盖掉真正的异常
        }
    }

    /** 读出当前 schema 版本，供诊断用。 */
    public int schemaVersion() {
        try (Connection conn = connection();
             Statement st = conn.createStatement();
             var rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StoreException("读取 schema 版本失败：" + e.getMessage(), e);
        }
    }
}
