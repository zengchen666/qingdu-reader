package com.qingdu.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TextCodec} 的单元测试。
 *
 * <p>重点验证"宽字符编码下换行符占两个字节"这件事 ——
 * 这是最容易出错、也最难靠肉眼发现的地方。
 */
@DisplayName("文本编解码器")
class TextCodecTest {

    @Test
    @DisplayName("UTF-8 / GB18030 的编码单元宽度是 1")
    void singleByteWidth() {
        assertEquals(1, TextCodec.utf8().unitWidth());
        assertEquals(1, TextCodec.of(CharsetDetector.GB18030).unitWidth());
    }

    @Test
    @DisplayName("UTF-16 的编码单元宽度是 2，且能区分大小端")
    void wideUnitWidth() {
        assertEquals(2, TextCodec.of(StandardCharsets.UTF_16LE).unitWidth());
        assertEquals(2, TextCodec.of(StandardCharsets.UTF_16BE).unitWidth());
        assertEquals(true, TextCodec.of(StandardCharsets.UTF_16LE).isWideUnit());
    }

    @Test
    @DisplayName("BOM 识别：优先匹配更长的序列")
    void bomLengthDetection() {
        assertEquals(3, TextCodec.bomLength(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}));
        assertEquals(2, TextCodec.bomLength(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0}));
        assertEquals(2, TextCodec.bomLength(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'a'}));
        // UTF-32LE 的 BOM 以 FF FE 开头，必须先被识别成 4 字节
        assertEquals(4, TextCodec.bomLength(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}));
        // 没有 BOM
        assertEquals(0, TextCodec.bomLength("第一章".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("UTF-8 下换行符占 1 个字节")
    void lineBreakInUtf8() {
        byte[] data = "第一行\n第二行".getBytes(StandardCharsets.UTF_8);
        TextCodec codec = TextCodec.utf8();

        int lf = codec.findLineBreak(data, 0);

        assertEquals("第一行".getBytes(StandardCharsets.UTF_8).length, lf);
        assertEquals(1, codec.lineBreakLength(data, lf));
    }

    @Test
    @DisplayName("UTF-16LE 下换行符占 2 个字节（0A 00）")
    void lineBreakInUtf16Le() {
        byte[] data = "第一行\n第二行".getBytes(StandardCharsets.UTF_16LE);
        TextCodec codec = TextCodec.of(StandardCharsets.UTF_16LE);

        int lf = codec.findLineBreak(data, 0);

        // "第一行" 共 3 个字符 × 2 字节 = 6
        assertEquals(6, lf);
        assertEquals(2, codec.lineBreakLength(data, lf));
    }

    @Test
    @DisplayName("UTF-16BE 下换行符是 00 0A，同样占 2 个字节")
    void lineBreakInUtf16Be() {
        byte[] data = "第一行\n第二行".getBytes(StandardCharsets.UTF_16BE);
        TextCodec codec = TextCodec.of(StandardCharsets.UTF_16BE);

        int lf = codec.findLineBreak(data, 0);

        assertEquals(6, lf);
        assertEquals(2, codec.lineBreakLength(data, lf));
    }

    @Test
    @DisplayName("Windows 换行 \\r\\n 按 2 个字符处理，不会多出一个空行")
    void crlfCountsAsOneBreak() {
        byte[] data = "第一行\r\n第二行".getBytes(StandardCharsets.UTF_8);
        TextCodec codec = TextCodec.utf8();

        int cr = codec.findLineBreak(data, 0);

        assertEquals("第一行".getBytes(StandardCharsets.UTF_8).length, cr);
        assertEquals(2, codec.lineBreakLength(data, cr));
    }

    @Test
    @DisplayName("老 Mac 的单独 \\r 也识别为换行")
    void loneCarriageReturnIsALineBreak() {
        byte[] data = "第一行\r第二行".getBytes(StandardCharsets.UTF_8);
        TextCodec codec = TextCodec.utf8();

        int cr = codec.findLineBreak(data, 0);

        assertEquals("第一行".getBytes(StandardCharsets.UTF_8).length, cr);
        assertEquals(1, codec.lineBreakLength(data, cr));
    }

    @Test
    @DisplayName("没有换行符时返回 -1")
    void noLineBreakReturnsMinusOne() {
        byte[] data = "一整行没有换行的文字".getBytes(StandardCharsets.UTF_8);

        assertEquals(-1, TextCodec.utf8().findLineBreak(data, 0));
    }

    @Test
    @DisplayName("解码一段字节范围")
    void decodeRange() {
        // 布局说明（UTF-8 下中文每字 3 字节）：
        //   "前言" = 6 字节 [0,6)
        //   "|||"  = 3 字节 [6,9)
        //   "正文" = 6 字节 [9,15)
        byte[] data = "前言|||正文".getBytes(StandardCharsets.UTF_8);
        TextCodec codec = TextCodec.utf8();

        String body = codec.decode(data, 9, data.length - 9);

        assertEquals("正文", body);
    }

    @Test
    @DisplayName("给用户看的编码名是中文可读的")
    void friendlyDisplayName() {
        assertEquals("UTF-8", TextCodec.utf8().displayName());
        assertEquals("GBK / GB18030", TextCodec.of(CharsetDetector.GB18030).displayName());
        assertEquals("UTF-16 LE", TextCodec.of(StandardCharsets.UTF_16LE).displayName());
    }
}
