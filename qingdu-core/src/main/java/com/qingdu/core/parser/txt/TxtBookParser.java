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

    /** 书名文本里的括号注释，如"斗破苍穹（完结）"里的"（完结）"。文件名与文件头通用。 */
    private static final Pattern TITLE_BRACKETS = Pattern.compile("[\\[【(（][^\\]】)）]{0,24}[\\]】)）]");

    /**
     * 文件头里用书名号标出来的书名，如 {@code 《元尊》}、{@code 『武动乾坤/作者:天蚕土豆』}。
     *
     * <p><b>为什么不能只认"书名：xxx"？</b>那种写法在下载站整理过的文件里反而是少数 ——
     * 四本真实语料里只有《斗破苍穹》写了"书名："，其余三本分别是
     * {@code 《元尊》}、{@code 《全职高手》（精校版全本）作者蝴蝶蓝}、
     * {@code 『武动乾坤/作者:天蚕土豆』}。少了这条，书架上的书名会退化成文件名，
     * 也就是 {@code 01 / 02 / 04} 这种排序编号。
     */
    private static final Pattern HEAD_TITLE_MARKED =
            Pattern.compile("[《『]([^《》『』\\r\\n]{1,40})[》』]");

    /**
     * 能当书名的行最长多少字 —— 这是"这行是书名还是正文"最可靠的判据。
     * 书名都短；正文里即便出现书名号（比如"本书原名《xxx》"），那一行通常也长得多。
     */
    private static final int HEAD_TITLE_MAX_CHARS = 30;

    /**
     * 候选书名里一出现就该被否决的字符。
     *
     * <p>文件头里"短行 + 书名号"的写法不止书名一种：{@code 『状态:已完结』} 就是。
     * 而书名里几乎不会出现冒号和句末标点，靠这几个字符把元数据行挡在外面。
     */
    private static final String TITLE_REJECT_CHARS = "：:。！？；…";

    /**
     * 作者名后面常见的分隔符 / 噪声字符，出现就截断。
     *
     * <p><b>闭括号与闭引号必须在内。</b>有些文件头写成
     * {@code 『武动乾坤/作者:天蚕土豆』} —— 作者名后面紧跟的是<b>闭</b>书名号。
     * 早期版本只列了开括号和常见分隔符，于是提取出来的是 {@code 天蚕土豆』}，
     * 那个多余的字符会一路显示到书架上。语料审计里《武动乾坤》就是这个症状。
     *
     * <p>刻意<b>不含</b>间隔号 {@code ·}：{@code 欧·亨利} 这类译名本身就带它，
     * 列进来会把真实作者名截断。
     */
    private static final String AUTHOR_SEPARATORS = " \t\u3000|,，;；/-—（(【[]】)}）』」”’'\">》〉";

    /** 书名文本里残留的"作者xxx"尾巴，如"《全职高手》作者蝴蝶蓝"。 */
    private static final Pattern TITLE_AUTHOR_TAIL =
            Pattern.compile("作\\s*者\\s*[:：]?\\s*[^\\r\\n]{0,12}$");

    /** 成对包裹整个书名的装饰，用来剥掉"《元尊》"两侧的书名号。 */
    private static final String BOOK_TITLE_MARKS = "《》『』“”‘’";

    /**
     * 书名文本里的营销后缀，都是下载站加上的，不是书名。
     *
     * <p>排序有讲究：先长后短。"TXT下载"必须在"下载"之前匹配，
     * 否则会先剪掉"下载"留下"TXT"。
     */
    private static final String[] TITLE_SUFFIXES = {
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
     * 提取书名。三条路，按可靠性从高到低：
     * <ol>
     *   <li>文件头里明确的 {@code 书名：xxx}；</li>
     *   <li>文件头里<b>用书名号标出来的短行</b> —— 书名号是作者留下的"这是书名"信号；</li>
     *   <li>文件名清洗后的结果。</li>
     * </ol>
     *
     * <p>之所以优先看文件内容，是因为从下载站批量下的文件经常被统一改名成
     * "1.txt"、"a_0001.txt"，反而内容里写着真书名。
     */
    private String extractTitle(Path file, String head) {
        Matcher labelled = BOOK_TITLE_PATTERN.matcher(head);
        if (labelled.find()) {
            String fromContent = cleanTitleText(labelled.group(1));
            if (!fromContent.isEmpty()) {
                return fromContent;
            }
        }

        String marked = titleFromMarkedLine(head);
        if (marked != null) {
            return marked;
        }

        return cleanFileName(file.getFileName().toString());
    }

    /**
     * 在文件头里找"用书名号标出来的那一行"，取它的内容当书名。
     *
     * <p>只认短行（{@link #HEAD_TITLE_MAX_CHARS} 字以内）：正文里引述书名时，
     * 那一行通常已经被句子的其余部分撑长了，靠长度就能把两者分开。
     *
     * <p>命中第一条就返回，不再往下找 —— 文件头里书名总在最前面，
     * 而后面跟着的 {@code 『状态:已完结』} 之类的元数据行不该反客为主。
     *
     * @return 找不到合适的行时返回 {@code null}
     */
    private String titleFromMarkedLine(String head) {
        for (String line : head.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.length() > HEAD_TITLE_MAX_CHARS) {
                continue;
            }
            Matcher m = HEAD_TITLE_MARKED.matcher(trimmed);
            if (!m.find()) {
                continue;
            }
            String candidate = cleanTitleText(m.group(0));
            if (candidate.isEmpty() || indexOfAny(candidate, TITLE_REJECT_CHARS) >= 0) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 从文件名推断书名 —— 提取书名的最后一条退路。
     *
     * <p>{@code 《元尊》.txt} 和文件头里那行 {@code 《元尊》} 是同一串噪声，
     * 所以清洗逻辑统一放在 {@link #cleanTitleText}，这里只多做一步"去掉扩展名"。
     */
    private String cleanFileName(String fileName) {
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String cleaned = cleanTitleText(name);
        return cleaned.isEmpty() ? "未命名" : cleaned;
    }

    /**
     * 清洗"像书名的一串文本"：括号注释、营销后缀、"作者xxx"尾巴、尾部分隔符、外层书名号。
     *
     * <p>要处理这几类噪声，它们在下载站的资源里几乎是标配，不清掉的话书架上会显示成
     * {@code 《斗破苍穹》（精校版全本）作者天蚕土豆TXT下载}：
     * <ol>
     *   <li>括号注释（{@code （完结）}）与营销后缀（{@code TXT下载}）；</li>
     *   <li>"作者xxx" 尾巴 —— 下载站常把作者名直接拼在书名后面；</li>
     *   <li>末尾残留的分隔符（文件头里的 {@code 武动乾坤/}）；</li>
     *   <li>包裹整个书名的书名号（{@code 《元尊》}）。</li>
     * </ol>
     *
     * <p><b>书名号要剥两次，一头一尾，中间的顺序也有讲究。</b>
     * <ul>
     *   <li><b>开头先剥一次</b>：{@code 『武动乾坤/作者:天蚕土豆』} 得先把壳剥掉，
     *       否则下面的"作者尾巴"会连闭书名号一起吃掉，剩下 {@code 『武动乾坤}
     *       这种只带半个壳的结果。</li>
     *   <li><b>末尾再剥一次</b>：{@code 《全职高手》（精校版全本）作者蝴蝶蓝}
     *       要先剪掉括号注释和作者尾巴，书名号才"成对"露出来。</li>
     *   <li><b>括号在书名号之前剥</b>：否则 {@code 《斗破苍穹》（精校版）} 中间的括号
     *       会把"是否成对包裹"的判断搞乱 —— 这一点由 {@link #stripWrappingMarks} 保证。</li>
     * </ul>
     */
    private String cleanTitleText(String raw) {
        String name = stripWrappingMarks(raw.strip());
        name = TITLE_BRACKETS.matcher(name).replaceAll("").strip();
        for (String suffix : TITLE_SUFFIXES) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length()).strip();
            }
        }
        // "作者xxx" 尾巴。start() > 0 这个前提不能省：
        // 否则《作者之死》这种书名会被整条当成作者信息删掉，只剩"未命名"。
        Matcher authorTail = TITLE_AUTHOR_TAIL.matcher(name);
        if (authorTail.find() && authorTail.start() > 0) {
            name = name.substring(0, authorTail.start()).strip();
        }
        // 末尾可能还残留着分隔符：文件名里的"斗破苍穹 -"、文件头里的"武动乾坤/"
        name = name.replaceAll("[\\-—－_·/]+$", "").strip();
        return stripWrappingMarks(name);
    }

    /**
     * 剥掉包裹整个书名的书名号与引号：{@code 《元尊》} → {@code 元尊}。
     *
     * <p><b>只剥"成对包裹整串"的。</b>{@code 重生之《红楼梦》} 里的书名号在中间，
     * 它不是装饰而是书名的一部分，动了就把书名改坏了 —— 所以必须首尾同时匹配才剥，
     * 并且支持嵌套写法 {@code 《『元尊』》}（逐层剥）。
     */
    private String stripWrappingMarks(String name) {
        String result = name;
        boolean changed = true;
        while (changed && result.length() >= 2) {
            changed = false;
            for (int i = 0; i + 1 < BOOK_TITLE_MARKS.length(); i += 2) {
                if (result.charAt(0) == BOOK_TITLE_MARKS.charAt(i)
                        && result.charAt(result.length() - 1) == BOOK_TITLE_MARKS.charAt(i + 1)) {
                    result = result.substring(1, result.length() - 1).strip();
                    changed = true;
                    break;
                }
            }
        }
        return result;
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
                        skeleton.volumeTitle(), start, end, List.of());
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
                    skeleton.volumeTitle(), start, end, blocks);

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
