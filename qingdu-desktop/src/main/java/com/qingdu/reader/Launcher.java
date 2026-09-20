package com.qingdu.reader;

import javafx.application.Application;

/**
 * 程序启动入口。
 *
 * <p><b>为什么不直接让主类继承 Application？</b> 这是一个很多人都踩过的坑。
 *
 * <p>当主类 {@code extends Application} 时，Java 启动器会去检查 JavaFX
 * 运行时是否以"模块"方式加载。如果我们是普通的 classpath 方式运行
 * （比如打成 fat jar 双击运行），启动器就会直接报错：
 * <pre>
 *   Error: JavaFX runtime components are missing,
 *   and are required to run this application
 * </pre>
 *
 * <p>解决办法就是加这么一个"中间人"：它本身不继承 {@code Application}，
 * 启动器就不会做那个检查；进去之后再手动调用
 * {@link Application#launch} 把真正的界面类拉起来。
 *
 * <p>这样一来，无论是用 Maven 插件运行、还是打成可执行 jar 双击运行，
 * 都能正常工作。这也是 JavaFX 项目的通行做法。
 */
public final class Launcher {

    private Launcher() {
        // 纯入口类，不需要被实例化
    }

    public static void main(String[] args) {
        // 把启动工作转交给真正的 JavaFX 应用类
        Application.launch(QingduApplication.class, args);
    }
}
