package com.qingdu.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CharsetDetector} 的单元测试。
 *
 * <p>测试数据全部在内存里现场构造（把中文字符串按指定编码转成字节），
 * 不依赖任何外部文件，所以走到哪台机器上都能跑。
 */
@DisplayName("字符编码探测")
class CharsetDetectorTest {

    private static final Charset GBK = Charset.forName("GBK");

    /** 一段足够长的中文，保证编码统计特征稳定（太短的样本统计不可靠）。 */
    private static final String CHINESE_SAMPLE = """
            第一章 星尘之始
            夜空中的星光落下来的时候，没有人注意到。
            他站在窗边，看着远处的城市灯火，心里想着一些很久以前的事情。
            那时候他还年轻，以为时间是可以随意挥霍的东西。
            现在他明白了，时间是这个世界上最公平的东西，也是最残酷的东西。
            第二章 远行
            第二天清晨，他收拾好行李，准备离开这座生活了二十年的城市。
            母亲站在门口，什么也没说，只是把一包dry粮塞进他的背包里。
            """;

    @Test
    @DisplayName("UTF-8 BOM 文件：直接靠 BOM 判定")
    void detectUtf8WithBom() {
        byte[] bytes = withBom(CHINESE_SAMPLE.getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_8, d.charset());
        assertEquals(CharsetDetector.Source.BOM, d.source());
    }

    @Test
    @DisplayName("UTF-16LE BOM 文件：不能被误判成 UTF-16BE")
    void detectUtf16LeWithBom() {
        byte[] bytes = withBom(CHINESE_SAMPLE.getBytes(StandardCharsets.UTF_16LE),
                new byte[]{(byte) 0xFF, (byte) 0xFE});

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_16LE, d.charset());
        assertEquals(CharsetDetector.Source.BOM, d.source());
    }

    @Test
    @DisplayName("UTF-16BE BOM 文件")
    void detectUtf16BeWithBom() {
        byte[] bytes = withBom(CHINESE_SAMPLE.getBytes(StandardCharsets.UTF_16BE),
                new byte[]{(byte) 0xFE, (byte) 0xFF});

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_16BE, d.charset());
        assertEquals(CharsetDetector.Source.BOM, d.source());
    }

    @Test
    @DisplayName("无 BOM 的 UTF-8 中文：靠语法校验判定")
    void detectPlainUtf8() {
        byte[] bytes = CHINESE_SAMPLE.getBytes(StandardCharsets.UTF_8);

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_8, d.charset());
        assertEquals(CharsetDetector.Source.STRICT_UTF8, d.source());
    }

    @Test
    @DisplayName("GBK 中文：UTF-8 校验不通过，应落到 GB18030")
    void detectGbk() {
        byte[] bytes = CHINESE_SAMPLE.getBytes(GBK);

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        // GB18030 是 GBK 的超集，用它解码 GBK 字节是完全正确的
        assertEquals(CharsetDetector.GB18030, d.charset());
    }

    @Test
    @DisplayName("GBK 文件用探测结果解码后，文字应与原文完全一致")
    void gbkRoundTrip() {
        byte[] bytes = CHINESE_SAMPLE.getBytes(GBK);

        TextCodec codec = CharsetDetector.detect(bytes).codec();

        assertEquals(CHINESE_SAMPLE, codec.decodeAll(bytes));
    }

    @Test
    @DisplayName("纯 ASCII 文件：当作 UTF-8 处理（ASCII 是 UTF-8 的子集）")
    void detectAsciiOnly() {
        byte[] bytes = "Chapter 1\nHello world, this is a plain ASCII text file.\n"
                .getBytes(StandardCharsets.US_ASCII);

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_8, d.charset());
    }

    @Test
    @DisplayName("无 BOM 的 UTF-16LE：靠 0x00 奇偶规律救回来")
    void detectUtf16WithoutBom() {
        // 用英文为主的内容，ASCII 字符在 UTF-16LE 里的高字节恒为 0x00
        byte[] bytes = ("Chapter 1 The Beginning\nHe walked out of the door.\n"
                + "Chapter 2 The Journey\nHe never came back.\n")
                .getBytes(StandardCharsets.UTF_16LE);

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertEquals(StandardCharsets.UTF_16LE, d.charset());
    }

    @Test
    @DisplayName("空文件不抛异常，返回 UTF-8 兜底")
    void detectEmpty() {
        CharsetDetector.Detection d = CharsetDetector.detect(new byte[0]);

        assertNotNull(d.charset());
        assertEquals(CharsetDetector.Source.FALLBACK, d.source());
    }

    @Test
    @DisplayName("探测结果自带可读的中文编码名与判定依据")
    void detectionCarriesReadableInfo() {
        byte[] bytes = CHINESE_SAMPLE.getBytes(GBK);

        CharsetDetector.Detection d = CharsetDetector.detect(bytes);

        assertNotNull(d.displayName());
        assertTrue(d.reason().length() > 0, "判定依据不能是空字符串");
    }

    /** 在字节数组前面拼接一段 BOM。 */
    private static byte[] withBom(byte[] content, byte[] bom) {
        byte[] result = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(content, 0, result, bom.length, content.length);
        return result;
    }
}
