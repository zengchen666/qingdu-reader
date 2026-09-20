package com.qingdu.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
    private static final int SCHEMA_VERSION = 1;

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
     * 建表。
     *
     * <p>全部用 {@code CREATE TABLE IF NOT EXISTS}，所以这个方法是<b>幂等</b>的 ——
     * 每次启动都跑一遍，已有数据不受影响。这是最简单也最不容易出错的做法：
     * 不引入 Flyway / Liquibase 这类迁移框架，就为了让一个单文件桌面程序
     * 能自己把表建好。
     *
     * <p>版本号写进 {@code PRAGMA user_version}。等 schema 真的要改的时候
     * （比如加字段），就可以靠它来判断"这是老库，需要升级"。
     * 现在只有版本 1，所以还没有升级逻辑 —— 但有这个数字在，
     * 以后加逻辑时不用回头改历史代码。
     */
    private void initSchema() {
        try (Connection conn = connection(); Statement st = conn.createStatement()) {

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

            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);

        } catch (SQLException e) {
            throw new StoreException("初始化数据库表结构失败：" + e.getMessage(), e);
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
