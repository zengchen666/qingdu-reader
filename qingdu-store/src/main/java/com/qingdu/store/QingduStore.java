package com.qingdu.store;

import java.nio.file.Path;

/**
 * 存储层的统一入口 —— 把三个 store 打包在一起。
 *
 * <p>界面层只需要持有一个 {@code QingduStore}，不用自己管
 * {@code Database} 的创建顺序（三个 store 都依赖同一个 Database 实例）。
 * 这属于<b>外观模式（Facade）</b>：把"一组必须按对的姿势组合起来的对象"
 * 收敛成一个好用的入口，避免每个调用方都重复那段组装代码。
 *
 * <p>它自己不实现任何业务逻辑，只是转发 —— 这一点很重要：
 * 外观类一旦开始写逻辑，就会变成谁都想改的"上帝类"。
 */
public final class QingduStore {

    private final Database database;
    private final BookStore books;
    private final BookmarkStore bookmarks;
    private final SettingStore settings;

    private QingduStore(Database database) {
        this.database = database;
        this.books = new BookStore(database);
        this.bookmarks = new BookmarkStore(database);
        this.settings = new SettingStore(database);
    }

    /** 打开默认位置的数据库（用户主目录下），供正式运行使用。 */
    public static QingduStore openDefault() {
        return open(Database.openDefault());
    }

    public static QingduStore open(Path dbFile) {
        return open(Database.open(dbFile));
    }

    public static QingduStore open(Database database) {
        return new QingduStore(database);
    }

    public BookStore books() {
        return books;
    }

    public BookmarkStore bookmarks() {
        return bookmarks;
    }

    public SettingStore settings() {
        return settings;
    }

    /** 数据库文件位置，界面上"关于"里会显示它，方便用户备份。 */
    public Path databaseFile() {
        return database.file();
    }

    public int schemaVersion() {
        return database.schemaVersion();
    }
}
