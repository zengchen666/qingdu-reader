package com.qingdu.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 行扫描器 —— 把整个文件的字节切成"带偏移量的行"。
 *
 * <p><b>这一步在项目里处于什么位置？</b>
 * <pre>
 *   读文件字节 → 【本类：切成带偏移量的行】 → 识别章节标题 → 生成章节索引
 * </pre>
 *
 * <p><b>为什么不用 {@code String.split("\n")}？</b><br>
 * 因为它会丢掉位置信息。分章必须知道"第三章从第 12480 个字节开始"，
 * 而 {@code split} 只给你一堆字符串，想反推偏移量就得重新扫描一遍字符串
 * 并计算每个字符占几个字节 —— 又慢又容易算错。
 * 我们直接站在字节层面上扫描，偏移量是扫描的自然产物，零额外成本。
 *
 * <p><b>为什么不做"先读全文再切"而是逐行推进？</b><br>
 * 其实当前实现仍然是先拿到整个 byte[]（上层负责读文件），
 * 但本类的扫描是<b>单向推进</b>的：每行只访问自己那一段字节，
 * 不会回头。这样以后要改成"流式分块读取"时，只要替换上层的读文件部分，
 * 本类几乎不用改 —— 这是有意留出的演进空间。
 */
public final class LineScanner {

    private LineScanner() {
        // 工具类不允许实例化
    }

    /**
     * 扫描出所有行。
     *
     * @param data  文件内容
     * @param codec 编解码器（决定换行符怎么算、每行怎么解码）
     * @return 按文件顺序排列的行列表；空文件返回空列表
     */
    public static List<ByteLine> scan(byte[] data, TextCodec codec) {
        List<ByteLine> lines = new ArrayList<>(estimateLineCount(data));
        if (data == null || data.length == 0) {
            return lines;
        }

        // 从 BOM 之后开始扫：这样第一行的偏移量天然跳过了 BOM，
        // 后面按偏移量读正文时不会再读到那个看不见的字符
        int bom = TextCodec.bomLength(data);
        int lineStart = Math.min(bom, data.length);

        while (lineStart < data.length) {
            int breakAt = codec.findLineBreak(data, lineStart);
            if (breakAt < 0) {
                // 最后一行没有换行符收尾（很常见，别漏掉）
                lines.add(new ByteLine(lineStart, data.length,
                        codec.decode(data, lineStart, data.length - lineStart)));
                break;
            }
            int breakLen = codec.lineBreakLength(data, breakAt);
            lines.add(new ByteLine(lineStart, breakAt + breakLen,
                    codec.decode(data, lineStart, breakAt - lineStart)));
            lineStart = breakAt + breakLen;
        }
        return lines;
    }

    /**
     * 预估行数，预先分配 ArrayList 容量。
     *
     * <p>一本百万字的小说大约有 30 万行。如果让 {@code ArrayList} 自己慢慢扩容，
     * 会经历十余次"申请新数组 + 复制旧数据"，白白多拷贝几千万次引用。
     * 按"平均每行 24 字节"粗估一下，哪怕估不准也几乎没有代价 ——
     * 这正是"性能优化先看数据结构"的典型例子。
     */
    private static int estimateLineCount(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        return Math.max(16, data.length / 24);
    }
}
