package com.qingdu.store;

/**
 * 存储层统一的受检异常的替代品。
 *
 * <p>继承 {@link RuntimeException} 而不是 {@link Exception}：<br>
 * 这个项目的持久化只有两种失败情形 ——
 * ① 数据库文件建不出来（磁盘满、没权限）；
 * ② SQL 写错了（这是程序缺陷，应该立刻暴露）。
 * 两者都不是"调用方可以合理恢复"的状况，用受检异常只会逼着每一层
 * 都写一遍 {@code try-catch}，把真正的业务代码淹掉。
 *
 * <p>但注意：<b>存储失败绝不能让界面崩掉</b>。所以 {@code ReaderView}
 * 里对写操作都是"捕获 → 提示 → 继续"，读操作才允许它向上冒。
 * 理由很实际：存进度失败只是下次要重新翻，而如果因此把整个窗口关掉，
 * 用户会以为读了半天的书丢了。
 */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(String message) {
        super(message);
    }
}
