package com.qingdu.common.util;

/**
 * 文本清洗工具。
 *
 * <p>从网上下载的 TXT 小说普遍存在这些问题：换行符不统一（Windows 是
 * {@code \r\n}、老 Mac 是 {@code \r}）、开头带 BOM 导致首行显示异常、
 * 行尾有大量空格、段落之间空行数量乱七八糟。这个类集中处理这些"脏活"。
 *
 * <p><b>使用范围提醒：</b>这些方法是为"单个章节的文本"设计的，
 * 不要拿它去处理整个 100MB 的文件 —— 每一步都会生成新字符串，
 * 在大文件上会带来明显的内存压力。整本书的处理应该在
 * qingdu-core 的分章索引阶段用流式方式完成。
 *
 * <p>本类是无状态工具类，所以构造方法设为私有，不允许被实例化。
 */
public final class TextCleaner {

    /** 字节顺序标记（Byte Order Mark）。有些编辑器保存 UTF-8 时会偷偷加上它。 */
    private static final char BOM = '\uFEFF';

    /** 全角空格。中文文本里常用来做缩进，也算空白。 */
    private static final char IDEOGRAPHIC_SPACE = '\u3000';

    private TextCleaner() {
        // 工具类不允许实例化
    }

    /**
     * 把各种换行符统一成 {@code \n}。
     *
     * <p>顺序很重要：必须先处理 {@code \r\n}，再处理单独的 {@code \r}。
     * 如果反过来，{@code \r\n} 会被拆成两个 {@code \n}，凭空多出一个空行。
     */
    public static String normalizeLineEndings(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 去掉开头的 BOM 字符。
     *
     * <p>BOM 在文件里占 3 个字节，如果不清掉，界面上的第一个字
     * 会变成一个看不见但真实存在的字符，导致章节标题匹配失败。
     */
    public static String stripBom(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.charAt(0) == BOM) {
            return text.substring(1);
        }
        return text;
    }

    /**
     * 去掉每一行末尾的空格与制表符。
     *
     * <p>{@code split} 的第二个参数传 -1，是为了保留末尾的空字符串。
     * 否则 {@code "abc\n".split("\n")} 会变成 {@code ["abc"]}，
     * 重新拼接后就把最后一行的换行符弄丢了。
     */
    public static String trimTrailingSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            sb.append(trimRight(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 把连续 3 个以上的换行压成 2 个（即最多保留一个空行）。
     *
     * <p>原始文本里经常有七八个连续空行，读起来像翻了两页空白。
     */
    public static String collapseBlankLines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("\n{3,}", "\n\n");
    }

    /**
     * 组合清洗：去 BOM → 统一换行 → 去行尾空格 → 压缩空行。
     *
     * <p>这是日常最常用的入口。顺序不能随意调换：
     * 必须先把换行符统一，后面的规则才有一套稳定的判断依据。
     */
    public static String clean(String text) {
        String result = stripBom(text);
        result = normalizeLineEndings(result);
        result = trimTrailingSpaces(result);
        return collapseBlankLines(result);
    }

    /** 去掉单个字符串右侧的所有空白（含半角空格、制表符、全角空格）。 */
    private static String trimRight(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == IDEOGRAPHIC_SPACE) {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }
}
