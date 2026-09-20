package com.qingdu.common.domain;

/**
 * 图书格式枚举。
 *
 * <p>用枚举而不是字符串的好处：编译器能帮你检查拼写错误，
 * 而且 {@code switch} 语句可以穷举所有情况（配合 Java 17+ 的 switch 模式匹配）。
 */
public enum BookFormat {

    /** 纯文本，本项目重点优化的格式 */
    TXT(".txt"),

    /** 国际标准电子书格式，本质是 zip + OPF + XHTML */
    EPUB(".epub"),

    /** 老版 Kindle 格式 */
    MOBI(".mobi"),

    /** 新版 Kindle 格式 */
    AZW3(".azw3"),

    /** 版式固定的文档格式 */
    PDF(".pdf"),

    /** 漫画压缩包 */
    CBZ(".cbz"),

    /** 无法识别的格式 */
    UNKNOWN("");

    private final String extension;

    BookFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    /**
     * 根据文件名推断格式。
     *
     * <p>这里只做"扩展名"这一层判断，属于快速预判；
     * 真正的格式识别在 qingdu-core 模块里会结合文件头（magic bytes）做二次确认，
     * 因为用户完全可能把 .txt 文件改名成 .epub。
     *
     * @param fileName 文件名，例如 "斗破苍穹.txt"
     * @return 推断出的格式，识别不出返回 {@link #UNKNOWN}
     */
    public static BookFormat fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UNKNOWN;
        }
        String lower = fileName.toLowerCase();
        for (BookFormat format : values()) {
            if (!format.extension.isEmpty() && lower.endsWith(format.extension)) {
                return format;
            }
        }
        return UNKNOWN;
    }
}
