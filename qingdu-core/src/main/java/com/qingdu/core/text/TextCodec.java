package com.qingdu.core.text;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 文本编解码器 —— 把"字节"和"字符"之间的换算关系封装起来。
 *
 * <p><b>它解决什么问题？</b><br>
 * 我们要按字节偏移量去文件里 seek 出一章的内容，就必须回答两个问题：
 * <ol>
 *   <li><b>换行符在字节层面长什么样？</b><br>
 *       UTF-8 / GB18030 里换行就是 <b>1 个字节</b> {@code 0A}；
 *       但 UTF-16 里换行是 <b>2 个字节</b> {@code 0A 00}（小端）或 {@code 00 0A}（大端）。
 *       如果一律按单字节找 {@code 0A}，UTF-16 文件会被从中间劈开，
 *       读出来的正文全是乱码 —— 这是编码处理里最经典的坑。</li>
 *   <li><b>怎么把一段字节范围还原成字符串？</b><br>
 *       {@code new String(data, offset, length, charset)} 就够了，
 *       但前提是 offset 必须正好落在字符边界上。我们的偏移量来自"行的起点"，
 *       天然满足这个条件。</li>
 * </ol>
 *
 * <p>所以本类引入 <b>编码单元宽度（unit width）</b> 概念：
 * <pre>
 *   UTF-8 / GB18030 / Big5  → 宽度 1（逐字节扫描）
 *   UTF-16LE / UTF-16BE     → 宽度 2
 *   UTF-32LE / UTF-32BE     → 宽度 4
 * </pre>
 * 扫描换行符时按这个宽度步进，就不会劈坏字符。
 *
 * <p><b>为什么 GB18030 宽度是 1 也能安全扫描？</b><br>
 * GB18030 是变长编码（1/2/4 字节），看起来按单字节扫很危险。但关键在于：
 * 它的多字节序列里，首字节范围是 {@code 0x81-0xFE}，
 * 后续字节范围是 {@code 0x40-0x7E} 和 {@code 0x80-0xFE}（4 字节形式的第二段是 {@code 0x30-0x39}）。
 * <b>{@code 0x0A} 永远不可能出现在多字节序列内部</b>，所以逐字节找 {@code 0A} 是安全的。
 * UTF-8 同理（续字节都在 {@code 0x80} 以上）。
 */
public final class TextCodec {

    /** 换行相关常量。注意这里说的是"字符码点"，不是字节。 */
    private static final int LF = '\n';
    private static final int CR = '\r';

    private final Charset charset;
    /** 一个编码单元的字节数：1（单字节系）、2（UTF-16）、4（UTF-32）。 */
    private final int unitWidth;
    /** 仅对宽度 > 1 的编码有意义：true 表示低字节在前（小端）。 */
    private final boolean littleEndian;

    private TextCodec(Charset charset, int unitWidth, boolean littleEndian) {
        this.charset = charset;
        this.unitWidth = unitWidth;
        this.littleEndian = littleEndian;
    }

    /**
     * 根据字符集构造编解码器。
     *
     * <p>宽度和字节序都是从字符集<b>名字</b>推断的。这不是取巧：
     * Java 的字符集命名是规范化的，{@code UTF-16LE} 这种名字本身就携带了字节序信息，
     * 比查询内部实现可靠得多。
     */
    public static TextCodec of(Charset charset) {
        String name = charset.name().toUpperCase();
        if (name.startsWith("UTF-16")) {
            return new TextCodec(charset, 2, !name.contains("BE"));
        }
        if (name.startsWith("UTF-32")) {
            return new TextCodec(charset, 4, !name.contains("BE"));
        }
        return new TextCodec(charset, 1, false);
    }

    /** 便捷方法：UTF-8 编码器。 */
    public static TextCodec utf8() {
        return of(StandardCharsets.UTF_8);
    }

    public Charset charset() {
        return charset;
    }

    /** 编码单元宽度（字节）。 */
    public int unitWidth() {
        return unitWidth;
    }

    /** 是否属于 UTF-16 / UTF-32 这类"宽字符"族。 */
    public boolean isWideUnit() {
        return unitWidth > 1;
    }

    /**
     * 给用户看的编码名称。
     *
     * <p>Java 的规范名字（{@code GB18030}）对普通用户没有意义，
     * 而 GBK 和 GB18030 在小说文件里其实是一回事（GB18030 是 GBK 的超集），
     * 所以统一显示成"GBK / GB18030"。
     */
    public String displayName() {
        String name = charset.name().toUpperCase();
        return switch (name) {
            case "UTF-8" -> "UTF-8";
            case "GB18030", "GBK", "GB2312" -> "GBK / GB18030";
            case "BIG5", "BIG5-HKSCS" -> "Big5（繁体）";
            case "UTF-16", "UTF-16BE" -> "UTF-16 BE";
            case "UTF-16LE" -> "UTF-16 LE";
            case "UTF-32", "UTF-32BE" -> "UTF-32 BE";
            case "UTF-32LE" -> "UTF-32 LE";
            default -> charset.name();
        };
    }

    /**
     * 解码一段字节。
     *
     * @param data   原始字节数组
     * @param offset 起始位置，必须是字符边界
     * @param length 字节长度
     */
    public String decode(byte[] data, int offset, int length) {
        if (length <= 0) {
            return "";
        }
        return new String(data, offset, length, charset);
    }

    /** 解码整个数组。 */
    public String decodeAll(byte[] data) {
        return decode(data, 0, data.length);
    }

    /**
     * 把字符串按本编解码器的字符集重新编码成字节。
     *
     * <p><b>为什么需要"反向"操作？</b><br>
     * 平时我们只解码，从不需要编码。但有一类情况例外：<b>标题被拼在段落中间</b>时，
     * 需要把"标题在这行里的第几个字符"换算成"文件里的第几个字节"，
     * 才能给这一章定出正确的 {@code startOffset}。换算方式就是把标题前面的
     * 那段文字重新编码、数它的字节数。
     *
     * <p><b>只在单字节字符集下可靠。</b>UTF-16 / UTF-32 的 {@code String.getBytes()}
     * 会带上 BOM、且字节序可能与原文件不一致，换算出来的长度会偏。——
     * 所以调用方必须先检查 {@link #isWideUnit()}，宽字符编码直接跳过这类处理
     * （那种编码的小说文件本身就是极少数，保守放弃比算错强）。
     */
    public byte[] encode(String text) {
        return text.getBytes(charset);
    }

    /**
     * 识别文件开头的 BOM，返回它占用的字节数（没有 BOM 时返回 0）。
     *
     * <p>判断顺序必须<b>先长后短</b>：{@code FF FE} 是 UTF-16LE 的 BOM，
     * 而 {@code FF FE 00 00} 是 UTF-32LE 的 BOM。如果先匹配短的那个，
     * UTF-32 文件会被误判成 UTF-16，后面全部错乱。
     */
    public static int bomLength(byte[] data) {
        if (data == null || data.length < 2) {
            return 0;
        }
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        if (b0 == 0x00 && b1 == 0x00 && data.length >= 4
                && (data[2] & 0xFF) == 0xFE && (data[3] & 0xFF) == 0xFF) {
            return 4; // UTF-32BE
        }
        if (b0 == 0xFF && b1 == 0xFE) {
            if (data.length >= 4 && (data[2] & 0xFF) == 0x00 && (data[3] & 0xFF) == 0x00) {
                return 4; // UTF-32LE
            }
            return 2; // UTF-16LE
        }
        if (b0 == 0xFE && b1 == 0xFF) {
            return 2; // UTF-16BE
        }
        if (b0 == 0xEF && b1 == 0xBB && data.length >= 3 && (data[2] & 0xFF) == 0xBF) {
            return 3; // UTF-8
        }
        return 0;
    }

    /**
     * 从 {@code from} 位置开始找下一个换行符，返回它的起始字节下标；找不到返回 -1。
     *
     * <p>{@code from} 应为编码单元宽度的整数倍，并且落在字符边界上
     * （我们的调用方总是传入"行的开头"，满足条件）。
     */
    public int findLineBreak(byte[] data, int from) {
        int i = alignTo(from);
        while (i + unitWidth <= data.length) {
            if (matchesUnit(data, i, LF) || matchesUnit(data, i, CR)) {
                return i;
            }
            i += unitWidth;
        }
        return -1;
    }

    /**
     * 计算 {@code at} 位置上的换行符占多少字节。
     *
     * <p>要区分三种情况：
     * <ul>
     *   <li>{@code \r\n}（Windows）→ 2 个单元</li>
     *   <li>单独的 {@code \n}（Unix）→ 1 个单元</li>
     *   <li>单独的 {@code \r}（老 Mac）→ 1 个单元</li>
     * </ul>
     * 老 Mac 的 {@code \r} 至今还能在网上下载的老资源里遇到，
     * 漏掉它会导致整本书变成"只有一行"。
     */
    public int lineBreakLength(byte[] data, int at) {
        if (matchesUnit(data, at, CR) && matchesUnit(data, at + unitWidth, LF)) {
            return unitWidth * 2; // \r\n
        }
        return unitWidth;
    }

    /** 编码单元的对齐修正：把任意下标向下对齐到 unitWidth 的整数倍。 */
    private int alignTo(int offset) {
        if (unitWidth == 1) {
            return offset;
        }
        return (offset / unitWidth) * unitWidth;
    }

    /** 判断 {@code at} 位置上的一个编码单元是否等于给定码点。 */
    private boolean matchesUnit(byte[] data, int at, int codePoint) {
        if (at < 0 || at + unitWidth > data.length) {
            return false;
        }
        if (unitWidth == 1) {
            return (data[at] & 0xFF) == codePoint;
        }
        // 宽字符：按字节序把 unitWidth 个字节拼成一个整数再比较
        int value = 0;
        if (littleEndian) {
            for (int k = unitWidth - 1; k >= 0; k--) {
                value = (value << 8) | (data[at + k] & 0xFF);
            }
        } else {
            for (int k = 0; k < unitWidth; k++) {
                value = (value << 8) | (data[at + k] & 0xFF);
            }
        }
        return value == codePoint;
    }

    @Override
    public String toString() {
        return "TextCodec[" + charset.name() + ", unit=" + unitWidth + "]";
    }
}
