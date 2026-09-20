package com.qingdu.core.parser.txt;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.util.BookId;
import com.qingdu.common.util.TextCleaner;
import com.qingdu.core.parser.BookParseException;
import com.qingdu.core.parser.spi.BookParser;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.core.text.TextCodec;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 图书解析器 —— {@link BookParser} 的第一个实现。
 *
 * <p>它把前面几个零件组装成完整的三步流程：
 * <pre>
 *   parseMetadata  →  探测编码 → 读文件头 → 提取书名/作者
 *   parseChapters  →  探测编码 → 扫描行 → 三重校验分章 → 得到字节偏移索引
 *   loadChapter    →  按偏移量随机读取那一段字节 → 解码 → 拆成段落块
 * </pre>
 *
 * <p><b>性能的关键在第三步。</b><br>
 * 有了 {@code startOffset / endOffset}，读一章只需要
 * {@code RandomAccessFile.seek()} 到指定位置再读那一小段，
 * 不需要把整个文件重新读一遍。一本 50MB 的小说，翻一章只读几十 KB，
 * 这是"毫秒级翻页"的物理基础。
 *
 * <p><b>当前实现的取舍</b>：建索引时会一次性把文件读进内存。
 * 对几十 MB 的小说完全没问题（现代 JVM 默认堆足够），
 * 但真正的 GB 级文件需要改成"分块流式扫描"。
 * 之所以先这样做，是因为 {@link com.qingdu.core.text.LineScanner}
 * 的扫描本身是单向推进的，将来换成流式只需要替换"读文件"这一步，
 * 分章逻辑不用动 —— 这是有意留出的演进空间。
 */
public class TxtBookParser implements BookParser {

    /** 提取作者信息时只扫文件开头这么多字节，避免正文里的"作者"二字造成误判。 */
    private static final int METADATA_SCAN_BYTES = 4096;

    private static final Pattern AUTHOR_PATTERN =
            Pattern.compile("作\\s*者\\s*[:：]\\s*([^\\r\\n]{1,30})");

    private static final Pattern BOOK_TITLE_PATTERN =
            Pattern.compile("书\\s*名\\s*[:：]\\s*([^\\r\\n]{1,40})");

    /** 文件名里的括号注释，如"斗破苍穹（完结）"里的"（完结）"。 */
    private static final Pattern FILENAME_BRACKETS = Pattern.compile("[\\[【(（][^\\]】)）]{0,24}[\\]】)）]");

    /** 作者名后面常见的分隔符，出现就截断。 */
    private static final String AUTHOR_SEPARATORS = " \t\u3000|,，;；/-—（(【[";

    /**
     * 文件名里的营销后缀，都是下载站加上的，不是书名。
     *
     * <p>排序有讲究：先长后短。"TXT下载"必须在"下载"之前匹配，
     * 否则会先剪掉"下载"留下"TXT"。
     */
    private static final String[] FILENAME_SUFFIXES = {
            "TXT下载", "txt下载", "全本完结", "全本", "完结", "全集", "精校版", "校对版",
            "无删减", "未删节", "无弹窗", "免费阅读", "全集下载", "在线阅读", "下载"
    };

    private final TxtChapterSplitter splitter;

    public TxtBookParser() {
        this(new TxtChapterSplitter());
    }

    /** 允许注入自定义参数的切分器，方便测试和后续调参。 */
    public TxtBookParser(TxtChapterSplitter splitter) {
        this.splitter = splitter == null ? new TxtChapterSplitter() : splitter;
    }

    @Override
    public BookFormat supportedFormat() {
        return BookFormat.TXT;
    }

    // ==================== 第一步：元信息 ====================

    @Override
    public Book parseMetadata(Path file) throws BookParseException {
        requireReadableFile(file);

        String head;
        try {
            head = readHeadText(file);
        } catch (IOException e) {
            throw new BookParseException("读取文件失败：" + e.getMessage(), pathOf(file), e);
        }

        String title = extractTitle(file, head);
        String author = extractAuthor(head);

        return new Book(
                // ID 必须由文件路径推导，不能随机生成 ——
                // 否则同一个文件每次打开都是新 ID，阅读进度和书签就永远找不到。
                // 详见 BookId 的注释。
                BookId.of(file),
                title,
                author,
                BookFormat.TXT,
                file.toAbsolutePath().normalize(),
                null,
                0, // 章节数要等 parseChapters 之后才知道，这里不猜
                Instant.now());
    }

    /**
     * 提取书名。
     *
     * <p>优先顺序：文件头里的"书名：xxx" → 文件名清洗后的结果。
     * 之所以优先看文件内容，是因为从下载站批量下的文件经常被统一改名成
     * "1.txt"、"a_0001.txt"，反而内容里写着真书名。
     */
    private String extractTitle(Path file, String head) {
        Matcher m = BOOK_TITLE_PATTERN.matcher(head);
        if (m.find()) {
            String fromContent = m.group(1).strip();
            if (!fromContent.isEmpty()) {
                return fromContent;
            }
        }
        return cleanFileName(file.getFileName().toString());
    }

    /**
     * 从文件名推断书名。
     *
     * <p>要处理三类噪声：扩展名、括号注释（"（完结）"）、营销后缀（"TXT下载"）。
     * 这三样在下载站的资源里几乎是标配，不清掉的话书架上会显示成
     * "斗破苍穹（全本完结）TXT下载"。
     */
    private String cleanFileName(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = FILENAME_BRACKETS.matcher(name).replaceAll("");
        name = name.strip();
        for (String suffix : FILENAME_SUFFIXES) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length()).strip();
            }
        }
        // 末尾可能还残留着分隔符，比如"斗破苍穹 -"
        name = name.replaceAll("[\\-—－_·]+$", "").strip();
        return name.isEmpty() ? "未命名" : name;
    }

    /** 从文件头提取作者。找不到就返回 null —— 大量 TXT 小说确实没有作者信息。 */
    private String extractAuthor(String head) {
        Matcher m = AUTHOR_PATTERN.matcher(head);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1).strip();
        int cut = indexOfAny(raw, AUTHOR_SEPARATORS);
        if (cut > 0) {
            raw = raw.substring(0, cut).strip();
        }
        // 有些文件写的是"作者：未知"或"作者：  "
        if (raw.isEmpty() || "未知".equals(raw) || "不详".equals(raw)) {
            return null;
        }
        return raw;
    }

    private int indexOfAny(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 第二步：章节索引 ====================

    @Override
    public List<Chapter> parseChapters(Path file, String bookId) throws BookParseException {
        requireReadableFile(file);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BookParseException("读取文件失败：" + e.getMessage(), pathOf(file), e);
        }

        // 探测只看前 256KB，保证和 loadChapter 时的探测结果一致
        CharsetDetector.Detection detection = CharsetDetector.detect(data);
        return splitter.split(data, detection.codec(), bookId);
    }

    /** 带诊断信息的建索引入口，界面和调试用。 */
    public TxtChapterSplitter.Report index(Path file, String bookId) throws BookParseException {
        requireReadableFile(file);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BookParseException("读取文件失败：" + e.getMessage(), pathOf(file), e);
        }
        CharsetDetector.Detection detection = CharsetDetector.detect(data);
        return splitter.analyze(data, detection.codec(), bookId);
    }

    // ==================== 第三步：按需读正文 ====================

    @Override
    public Chapter loadChapter(Path file, Chapter skeleton) throws BookParseException {
        requireReadableFile(file);
        long start = skeleton.startOffset();
        long end = skeleton.endOffset();

        try {
            long fileSize = Files.size(file);
            // 文件在索引之后被改过时，偏移量可能越界，这里夹一下，宁可少读也不要崩
            start = Math.max(0, Math.min(start, fileSize));
            end = Math.max(start, Math.min(end, fileSize));
            if (end == start) {
                return new Chapter(skeleton.bookId(), skeleton.index(), skeleton.title(),
                        start, end, List.of());
            }

            byte[] buffer = new byte[(int) (end - start)];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                raf.readFully(buffer);
            }

            CharsetDetector.Detection detection = CharsetDetector.detect(file);
            TextCodec codec = detection.codec();
            List<ChapterBlock> blocks = toBlocks(codec.decodeAll(buffer), skeleton.title());

            return new Chapter(skeleton.bookId(), skeleton.index(), skeleton.title(),
                    start, end, blocks);

        } catch (IOException e) {
            throw new BookParseException("读取章节正文失败：" + e.getMessage(), pathOf(file), e);
        }
    }

    /**
     * 把一章的原始文本转成内容块列表。
     *
     * <p>规则：
     * <ul>
     *   <li>空行只起分隔作用，不产生块 —— 段落间距交给界面控制，
     *       排版更可控，也避免"满屏空行"。</li>
     *   <li>第一行如果就是章节标题，转成 Heading 块而不是普通段落。
     *       这样界面能把它渲染成标题样式，用户一眼能看出"这是本章开头"。</li>
     * </ul>
     */
    private List<ChapterBlock> toBlocks(String rawText, String chapterTitle) {
        String cleaned = TextCleaner.clean(rawText);
        List<ChapterBlock> blocks = new ArrayList<>();
        boolean firstContentSeen = false;

        for (String line : cleaned.split("\n", -1)) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (!firstContentSeen) {
                firstContentSeen = true;
                if (isChapterHeadingOf(text, chapterTitle)) {
                    blocks.add(new ChapterBlock.Heading(1, text));
                    continue;
                }
            }
            blocks.add(new ChapterBlock.Paragraph(text));
        }
        return blocks;
    }

    /** 判断某一行是不是本章自己的标题行。 */
    private boolean isChapterHeadingOf(String line, String chapterTitle) {
        if (chapterTitle == null || chapterTitle.isEmpty()) {
            return false;
        }
        return ChapterTitleMatcher.match(line)
                .map(m -> m.displayText().equals(chapterTitle))
                .orElse(false);
    }

    // ==================== 公共工具 ====================

    /**
     * 读取文件头部的文本，用于提取书名作者。
     *
     * <p>只读前 {@link #METADATA_SCAN_BYTES} 字节：一来快，
     * 二来避免正文里出现的"作者"二字被误当成图书信息。
     * 注意这里必须多读一点再截断字符，否则可能把一个多字节汉字劈成两半。
     */
    private String readHeadText(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = in.readNBytes(METADATA_SCAN_BYTES);
            TextCodec codec = CharsetDetector.detect(buffer).codec();
            return TextCleaner.stripBom(codec.decodeAll(buffer));
        }
    }

    private void requireReadableFile(Path file) throws BookParseException {
        if (file == null) {
            throw new BookParseException("文件路径不能为空", "null");
        }
        if (!Files.exists(file)) {
            throw new BookParseException("文件不存在", pathOf(file));
        }
        if (!Files.isRegularFile(file)) {
            throw new BookParseException("这不是一个文件", pathOf(file));
        }
        if (!Files.isReadable(file)) {
            throw new BookParseException("文件没有读取权限", pathOf(file));
        }
    }

    private String pathOf(Path file) {
        return file == null ? "null" : file.toAbsolutePath().toString();
    }
}
