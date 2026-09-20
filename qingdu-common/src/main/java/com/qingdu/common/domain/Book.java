package com.qingdu.common.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * 一本书的元信息（不含正文）。
 *
 * <p>对应数据库里 book 表的一行。正文不存在这里 —— 正文要么在文件里
 * （靠 Chapter 的偏移量定位），要么在缓存里，总之不塞进这个对象。
 *
 * @param id           唯一标识。由文件路径推导（见 BookId），<b>不是随机 UUID</b> ——
 *                     因为阅读进度、书签都要靠它把"同一个文件"认出来
 * @param title        书名
 * @param author       作者，可能为空（很多 TXT 文件根本没有作者信息）
 * @param format       文件格式
 * @param filePath     文件在磁盘上的绝对路径
 * @param coverPath    封面缓存的路径，可能为空
 * @param chapterCount 已识别出的章节数，0 表示还没建索引
 * @param addedAt      加入书架的时间
 */
public record Book(
        String id,
        String title,
        String author,
        BookFormat format,
        Path filePath,
        Path coverPath,
        int chapterCount,
        Instant addedAt
) {

    /**
     * 不可见字符正则。
     *
     * <p>从网上批量下载的 TXT 文件，书名里经常混入 BOM、零宽空格等
     * 不可见字符，直接显示会出现"书名后面有个奇怪的小方块"。
     * {@code \p{C}} 匹配所有"其他/控制类"字符，正好用来清理它们。
     */
    private static final String INVISIBLE_CHARS = "\\p{C}";

    public Book {
        if (title == null || title.isBlank()) {
            // 书名优先取文件名，所以这里不抛异常，给个兜底值即可
            title = "未命名";
        }
        title = title.replaceAll(INVISIBLE_CHARS, "").trim();

        if (author == null || author.isBlank()) {
            author = null; // 统一用 null 表示"没有作者信息"，而不是空字符串
        } else {
            author = author.replaceAll(INVISIBLE_CHARS, "").trim();
        }
    }

    /** 是否已经建立过章节索引。没建索引的书，打开时需要先扫描一遍。 */
    public boolean isIndexed() {
        return chapterCount > 0;
    }

    public Optional<Path> cover() {
        return Optional.ofNullable(coverPath);
    }

    public Optional<String> authorName() {
        return Optional.ofNullable(author);
    }

    /** 文件名（含扩展名），用于界面显示和调试。 */
    public String fileName() {
        return filePath.getFileName().toString();
    }
}
