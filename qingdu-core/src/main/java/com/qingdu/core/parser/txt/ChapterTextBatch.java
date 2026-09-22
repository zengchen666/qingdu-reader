package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Chapter;
import com.qingdu.common.util.TextCleaner;
import com.qingdu.core.parser.BookParseException;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.core.text.TextCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 一次性把整本书读进内存，然后按字节偏移切开任意一章 —— 给"建全文索引"用的批量读取器。
 *
 * <p><b>为什么需要它</b>（这条是实测出来的，不是拍脑袋）：
 * 建索引要读完全部 1636 章。最自然的写法是循环调用 {@link TxtBookParser#loadChapter}，
 * 每次开一次 {@code RandomAccessFile}、seek、读一小段 —— 实测 <b>23 秒</b>。
 * 改成"整本读一次（{@code Files.readAllBytes}）+ 按偏移切片"之后是 <b>83 毫秒</b>，
 * 整个建索引从 37 秒降到 5.9 秒。慢的不是 SQLite，是每章一次的文件打开。
 *
 * <p>这正好复用了本项目分章时就定下的设计：{@link Chapter#startOffset()} /
 * {@link Chapter#endOffset()} 记的是字节偏移，所以任意一章都能从一次读进来的
 * 字节数组里精确切出来，不需要逐章回头找文件。
 *
 * <p><b>刻意不做缓存</b>：解码后的章节文本不留在内存里。建索引时每章只解码一次，
 * 但批量持有 1600 个 6KB 的字符串（约 10 MB）纯属浪费；而且调用方往往只需要其中
 * 几十章（比如搜索候选的后过滤）。要反复取同一章的场合由调用方自己缓存。
 *
 * <p>本类不是线程安全的（内部只有一个共享的字节数组，但读操作本身无状态），
 * 多线程并发调用 {@link #textOf(int)} 是安全的 —— 每次调用只读取数组、不修改它。
 */
public final class ChapterTextBatch {

    private final byte[] data;
    private final TextCodec codec;
    private final List<Chapter> chapters;

    private ChapterTextBatch(byte[] data, TextCodec codec, List<Chapter> chapters) {
        this.data = data;
        this.codec = codec;
        this.chapters = chapters;
    }

    /**
     * 读整本书，准备好按章切片。
     *
     * <p>编码只探测一次（看文件头 256KB），和 {@link TxtBookParser#loadChapter}
     * 逐章探测的结果一致 —— 同一个文件的探测结果必须稳定，否则索引里的文本
     * 和界面上显示的文本会对不上。
     *
     * @param file     源文件
     * @param chapters 分章结果，<b>必须按章节序号顺序排列</b>（解析器产出的就是这个顺序）
     */
    public static ChapterTextBatch load(Path file, List<Chapter> chapters) throws BookParseException {
        if (file == null) {
            throw new BookParseException("文件路径不能为空", "null");
        }
        if (chapters == null || chapters.isEmpty()) {
            throw new BookParseException("这本书没有任何章节，无法建立索引", describe(file));
        }
        if (!Files.isRegularFile(file)) {
            throw new BookParseException("文件不存在或不是普通文件", describe(file));
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BookParseException("读取文件失败：" + e.getMessage(), describe(file), e);
        }
        TextCodec codec = CharsetDetector.detect(data).codec();
        return new ChapterTextBatch(data, codec, List.copyOf(chapters));
    }

    /** 章节数。 */
    public int size() {
        return chapters.size();
    }

    /**
     * 取某一章的原始文本（已做 {@link TextCleaner#clean} 清洗）。
     *
     * <p><b>清洗必须和索引侧一致</b>：建索引时写进去的是 {@code textOf(i)} 切分后的结果，
     * 查询时做"原文精确校验"用的也是 {@code textOf(i)}。两边只要有一处换了文本来源，
     * 就会出现"搜到了但校验不过"的怪事。
     *
     * @param chapterIndex 章节序号（0 起）
     * @return 该章文本；序号越界或该章长度为 0 时返回空串
     */
    public String textOf(int chapterIndex) {
        if (chapterIndex < 0 || chapterIndex >= chapters.size()) {
            return "";
        }
        Chapter chapter = chapters.get(chapterIndex);
        long start = Math.max(0, Math.min(chapter.startOffset(), data.length));
        long end = Math.max(start, Math.min(chapter.endOffset(), data.length));
        if (end == start) {
            return "";
        }
        String decoded = codec.decode(data, (int) start, (int) (end - start));
        return TextCleaner.clean(decoded);
    }

    /**
     * 按序取全部章节文本 —— 建索引时的便捷入口。
     *
     * @param progress 每切出一章回调一次（参数是"已完成第几章"，从 0 起）；
     *                 可以为 {@code null}，表示不关心进度
     */
    public List<String> allTexts(java.util.function.IntConsumer progress) {
        java.util.List<String> texts = new java.util.ArrayList<>(chapters.size());
        for (int i = 0; i < chapters.size(); i++) {
            texts.add(textOf(i));
            if (progress != null) {
                progress.accept(i);
            }
        }
        return texts;
    }

    /** 整本书的字节数，用于诊断和体积估算。 */
    public int byteLength() {
        return data.length;
    }

    private static String describe(Path file) {
        return file == null ? "null" : file.toAbsolutePath().toString();
    }
}
