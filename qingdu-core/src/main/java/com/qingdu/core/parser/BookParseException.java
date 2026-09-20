package com.qingdu.core.parser;

/**
 * 图书解析异常。
 *
 * <p>为什么自定义异常，而不是直接抛 {@code IOException}？
 * <ul>
 *   <li>区分"业务错误"和"系统错误"：文件损坏是业务错误，
 *       应该给用户一句人话提示（"这个文件已损坏，无法打开"），
 *       而不是把 Java 堆栈甩到界面上。</li>
 *   <li>可以携带更多上下文，比如出错的文件路径和格式。</li>
 * </ul>
 *
 * <p>继承 {@code Exception}（受检异常）而不是 {@code RuntimeException}，
 * 是为了强制调用方处理 —— 解析文件失败是这个项目里必然会发生的正常情况，
 * 不应该被忽略。
 */
public class BookParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String filePath;

    public BookParseException(String message, String filePath) {
        super(message);
        this.filePath = filePath;
    }

    public BookParseException(String message, String filePath, Throwable cause) {
        super(message, cause);
        this.filePath = filePath;
    }

    /** 出错的文件路径，便于日志定位。 */
    public String filePath() {
        return filePath;
    }
}
