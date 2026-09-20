package com.qingdu.core.text;

/**
 * 一行文本，外加它在文件里的字节范围。
 *
 * <p><b>为什么行还需要记偏移量？</b><br>
 * 因为分章的本质就是"记住每一章从第几个字节开始"。
 * 扫描文件时顺手把每行的字节位置记下来，后面判断章节边界就只是
 * 简单的大小比较，不用反复回头数字节 —— 这就是"一次扫描、多次复用"。
 *
 * <p>三个字段的关系：
 * <pre>
 *   start ≤ contentEnd ≤ end
 *   ^     ^            ^
 *   |     |            └─ 下一行的起点（含换行符，所以 end 也是"本行+换行符"的终点）
 *   |     └─ 本行正文结束的位置（不含换行符）
 *   └─ 本行起点
 * </pre>
 * 注意 {@code end} 而不是 {@code contentEnd} 才是"下一章起点"要找的值：
 * 章节的 {@code endOffset} 定义为"下一章标题行的起点"，
 * 这样中间不会漏掉换行符。
 *
 * @param start   本行第一个字节在文件中的下标
 * @param end     下一行第一个字节的下标（即本行含换行符的终点）
 * @param text    本行的文本内容（不含换行符）
 */
public record ByteLine(long start, long end, String text) {

    /** 本行在文件里占用的字节数（含换行符）。 */
    public long byteLength() {
        return end - start;
    }

    /** 去掉空白后是不是空行。分章判断时大量用到。 */
    public boolean isBlank() {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 全角空格 \u3000 在中文排版里常被当作缩进，也算空白
            if (c != ' ' && c != '\t' && c != '\u3000' && c != '\uFEFF') {
                return false;
            }
        }
        return true;
    }

    /** 行文本的字符数（注意不是字节数 —— 中文一个字符占 2~3 字节）。 */
    public int charLength() {
        return text == null ? 0 : text.length();
    }

    /** 去掉首尾空白后的文本，供标题匹配使用。 */
    public String trimmed() {
        return text == null ? "" : text.strip();
    }
}
