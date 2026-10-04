package com.qingdu.core.parser.epub;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.domain.ChapterBlock;
import com.qingdu.common.util.BookId;
import com.qingdu.core.parser.BookParseException;
import com.qingdu.core.parser.spi.BookParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * EPUB 图书解析器 —— {@link BookParser} 的第二个实现（v0.5）。
 *
 * <p><b>为什么没有引 epub4j / jsoup？</b>
 *
 * <p>先看 EPUB 到底是什么：它<b>就是一个 zip 包</b>，里面装着
 * 一个 OPF 清单文件（XML）、若干 XHTML 正文文件、若干图片。
 * 也就是说，需要的三样能力 JDK 全都有：
 *
 * <pre>
 *   zip 容器   → java.util.zip.ZipFile        （java.base）
 *   XML 解析   → javax.xml.parsers            （java.xml 模块，已在打包清单里）
 *   XHTML 提纯 → 见下面的"安全"一节
 * </pre>
 *
 * <p>而 epublib（epub4j 的后继）最后一个版本停在 2017 年的 3.1，
 * 已经九年没人维护，还要连带拖进来几个老依赖。为了一个"读 zip + 读 XML"
 * 的需求引一个停更九年的库，换来的是打包体积变大、多一份许可证要核对、
 * 以及一条说不清的技术选型理由。
 *
 * <p><b>安全：为什么"不渲染原始 HTML"比"白名单过滤"更硬。</b><br>
 * EPUB 里可以内嵌 {@code <script>}。常见的做法是拿 jsoup 过一遍白名单，
 * 但那是在"先把不信任的内容解析出来，再挑掉危险的部分"。
 * 这里换了个更彻底的做法：<b>从头到尾只往外抽文本节点和图片引用</b>，
 * 解析出的 DOM 只用于取 {@code getTextContent()}，
 * 不产生任何可被执行的节点、不交给任何 WebView。
 * 脚本在这种流程里<b>没有机会被执行</b>，不是"被执行前被拦下"。
 *
 * <p>另外 XML 解析做了 XXE 加固（{@link #newBuilder()}）：
 * 关掉外部实体与 XInclude。EPUB 是来源不可信的输入，
 * 不做这一步的话，一个构造过的 .epub 就能让解析器去读本机文件或打内网。
 *
 * <p><b>章节定位：为什么偏移量退化成 spine 序号。</b><br>
 * TXT 的 {@code startOffset/endOffset} 是字节偏移，因为整本书是一个文件。
 * EPUB 每章是独立文件，字节偏移没有意义 —— 所以这两个字段在这里
 * 存的是<b>该章在 spine 中的序号</b>（{@link Chapter} 的类注释里预留了这个语义）。
 * 代价是"按偏移 seek"的性能优势在 EPUB 上不成立，
 * 但 EPUB 单章文件本来就只有几十 KB，读一章的成本与 TXT 是同一量级。
 */
public class EpubBookParser implements BookParser {

    /** OPF 文件的入口。所有 EPUB 都必须有它，路径是规范写死的。 */
    private static final String CONTAINER_PATH = "META-INF/container.xml";

    /** 目录文件的 media-type（EPUB 2）。 */
    private static final String NCX_MEDIA_TYPE = "application/x-dtbncx+xml";

    /** 单张图片的上限。超过就跳过 —— 防止一个恶意/损坏的包撑爆磁盘缓存。 */
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    /** 书名缺失时的兜底。 */
    private static final String FALLBACK_TITLE = "未命名电子书";

    /**
     * 图片缓存目录。
     *
     * <p>必须落到真实文件系统，因为 {@code ChapterBlock.Image} 只带一个
     * {@code resourcePath} 字符串，而渲染器是"格式无关"的 ——
     * 它不认识 zip，只会按路径去加载一张图片。
     * 所以正文解析时把图片抽出来写进这个目录，再把绝对路径交给渲染层。
     * 这样统一章节模型的抽象就不会被破坏。
     */
    private final Path cacheRoot;

    public EpubBookParser() {
        this(defaultCacheRoot());
    }

    public EpubBookParser(Path cacheRoot) {
        this.cacheRoot = (cacheRoot == null) ? defaultCacheRoot() : cacheRoot;
    }

    private static Path defaultCacheRoot() {
        String home = System.getProperty("user.home");
        Path base = (home == null || home.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir", "."))
                : Path.of(home);
        return base.resolve(".qingdu-reader").resolve("epub-cache");
    }

    // ==================== BookParser 接口 ====================

    @Override
    public BookFormat supportedFormat() {
        return BookFormat.EPUB;
    }

    @Override
    public Book parseMetadata(Path file) throws BookParseException {
        requireReadableFile(file);
        try (ZipFile zip = openZip(file)) {
            Structure structure = readStructure(zip, file);
            return new Book(
                    BookId.of(file),
                    structure.title(),
                    structure.author(),
                    BookFormat.EPUB,
                    file.toAbsolutePath().normalize(),
                    null,
                    0,
                    Instant.now());
        } catch (BookParseException e) {
            throw e;
        } catch (IOException e) {
            throw new BookParseException("读取 EPUB 失败：" + e.getMessage(), pathOf(file), e);
        }
    }

    @Override
    public List<Chapter> parseChapters(Path file, String bookId) throws BookParseException {
        requireReadableFile(file);
        try (ZipFile zip = openZip(file)) {
            Structure structure = readStructure(zip, file);
            List<Chapter> chapters = new ArrayList<>(structure.spine().size());

            for (int i = 0; i < structure.spine().size(); i++) {
                String href = structure.spine().get(i);
                String title = structure.tocTitles().get(href);
                if (title == null) {
                    // 目录里没有这一项（很多 EPUB 的 TOC 并不完整），
                    // 退回到文档内部的第一个标题，再不行才用序号兜底
                    title = firstHeading(zip, href);
                }
                if (title == null) {
                    title = "第 " + (i + 1) + " 章";
                }
                chapters.add(Chapter.indexOnly(bookId, i, title, null, i, i));
            }
            return chapters;
        } catch (BookParseException e) {
            throw e;
        } catch (IOException e) {
            throw new BookParseException("读取 EPUB 失败：" + e.getMessage(), pathOf(file), e);
        }
    }

    @Override
    public Chapter loadChapter(Path file, Chapter skeleton) throws BookParseException {
        requireReadableFile(file);
        int index = (int) skeleton.startOffset();
        try (ZipFile zip = openZip(file)) {
            Structure structure = readStructure(zip, file);
            if (index < 0 || index >= structure.spine().size()) {
                // 包在索引之后被换过，序号越界了。返回空章而不是抛异常 ——
                // 用户看到"这一章没有内容"，比整个程序崩掉好
                return new Chapter(skeleton.bookId(), skeleton.index(), skeleton.title(),
                        skeleton.volumeTitle(), skeleton.startOffset(), skeleton.endOffset(), List.of());
            }
            String href = structure.spine().get(index);
            byte[] raw = readEntry(zip, href, file);
            List<ChapterBlock> blocks = (raw == null)
                    ? List.of()
                    : toBlocks(raw, href, zip, skeleton.bookId(), file);

            return new Chapter(skeleton.bookId(), skeleton.index(), skeleton.title(),
                    skeleton.volumeTitle(), skeleton.startOffset(), skeleton.endOffset(), blocks);
        } catch (BookParseException e) {
            throw e;
        } catch (IOException e) {
            throw new BookParseException("读取章节正文失败：" + e.getMessage(), pathOf(file), e);
        }
    }

    // ==================== EPUB 结构 ====================

    /**
     * 一份 EPUB 的骨架信息。
     *
     * @param opfDir    OPF 所在目录，所有相对路径都基于它解析
     * @param title     书名
     * @param author    作者
     * @param spine     正文阅读顺序（已解析成 zip 内的完整路径）
     * @param tocTitles 目录标题表：正文路径 → 标题
     */
    private record Structure(String opfDir, String title, String author,
                             List<String> spine, Map<String, String> tocTitles) {
    }

    private Structure readStructure(ZipFile zip, Path file) throws BookParseException {
        String opfPath = readOpfPath(zip, file);
        byte[] opfBytes = readEntry(zip, opfPath, file);
        if (opfBytes == null) {
            throw new BookParseException("包里找不到清单文件：" + opfPath, pathOf(file));
        }
        Document opf = parseXml(opfBytes, "清单文件 " + opfPath, file);
        String opfDir = directoryOf(opfPath);

        String title = firstText(opf, "title");
        String author = firstText(opf, "creator");

        Map<String, ManifestItem> manifest = readManifest(opf, opfDir);
        List<String> spine = readSpine(opf, manifest, file);
        Map<String, String> tocTitles = readTocTitles(zip, opf, manifest, opfDir, file);

        return new Structure(
                opfDir,
                (title == null || title.isBlank()) ? FALLBACK_TITLE : title.strip(),
                (author == null || author.isBlank()) ? null : author.strip(),
                spine,
                tocTitles);
    }

    /**
     * 读 {@code META-INF/container.xml} 拿到真正的清单文件路径。
     *
     * <p>为什么不硬编码 {@code OEBPS/content.opf}：规范只规定 container.xml 的位置，
     * OPF 的路径和文件名由出版社自己定（见过 {@code content.opf}、
     * {@code package.opf}、{@code book.opf} 三种）。
     */
    private String readOpfPath(ZipFile zip, Path file) throws BookParseException {
        byte[] raw = readEntry(zip, CONTAINER_PATH, file);
        if (raw == null) {
            throw new BookParseException(
                    "这不是一个 EPUB 文件（找不到 META-INF/container.xml）", pathOf(file));
        }
        Document doc = parseXml(raw, "容器文件", file);
        for (Element rootfile : byLocalName(doc.getDocumentElement(), "rootfile")) {
            String fullPath = rootfile.getAttribute("full-path");
            if (!fullPath.isBlank()) {
                return fullPath.trim();
            }
        }
        throw new BookParseException("容器文件里没有声明清单文件", pathOf(file));
    }

    private record ManifestItem(String href, String mediaType, String properties) {
    }

    private Map<String, ManifestItem> readManifest(Document opf, String opfDir) {
        Map<String, ManifestItem> items = new LinkedHashMap<>();
        for (Element item : byLocalName(opf.getDocumentElement(), "item")) {
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            if (id.isBlank() || href.isBlank()) {
                continue;
            }
            items.put(id.trim(), new ManifestItem(
                    resolve(opfDir, href),
                    item.getAttribute("media-type"),
                    item.getAttribute("properties")));
        }
        return items;
    }

    /**
     * 读 spine（正文顺序）。
     *
     * <p>跳过 {@code linear="no"} 的条目：EPUB 3 会把导航文档放进 spine，
     * 但它是"目录"不是"正文"，读出来是一堆链接列表。
     */
    private List<String> readSpine(Document opf, Map<String, ManifestItem> manifest, Path file)
            throws BookParseException {
        List<Element> spines = byLocalName(opf.getDocumentElement(), "spine");
        if (spines.isEmpty()) {
            throw new BookParseException("清单文件里没有 spine（正文顺序）", pathOf(file));
        }
        List<String> hrefs = new ArrayList<>();
        for (Element itemref : byLocalName(spines.get(0), "itemref")) {
            if ("no".equalsIgnoreCase(itemref.getAttribute("linear").trim())) {
                continue;
            }
            ManifestItem item = manifest.get(itemref.getAttribute("idref").trim());
            if (item != null) {
                hrefs.add(item.href());
            }
        }
        return hrefs;
    }

    /**
     * 目录标题表。EPUB 3 用 {@code nav} 文档，EPUB 2 用 {@code toc.ncx}。
     *
     * <p>两个都读是必要的：现实里的 EPUB 两种混着来，而且相当一部分
     * 只有其中一种。读不到目录不算失败 —— {@link #parseChapters} 会退回
     * 用文档内的第一个标题。
     */
    private Map<String, String> readTocTitles(ZipFile zip, Document opf,
                                              Map<String, ManifestItem> manifest,
                                              String opfDir, Path file) {
        Map<String, String> titles = new LinkedHashMap<>();

        // EPUB 3：manifest 里 properties 带 nav 的那一项
        for (ManifestItem item : manifest.values()) {
            if (item.properties() != null && item.properties().contains("nav")) {
                collectNavTitles(zip, item.href(), opfDir, titles, file);
            }
        }
        // EPUB 2：spine 的 toc 属性指向 ncx
        if (titles.isEmpty()) {
            List<Element> spines = byLocalName(opf.getDocumentElement(), "spine");
            if (!spines.isEmpty()) {
                ManifestItem ncx = manifest.get(spines.get(0).getAttribute("toc").trim());
                if (ncx != null) {
                    collectNcxTitles(zip, ncx.href(), opfDir, titles, file);
                }
            }
        }
        // 少数包既没写 properties 也没写 toc 属性，但确实有一份 ncx
        if (titles.isEmpty()) {
            for (ManifestItem item : manifest.values()) {
                if (NCX_MEDIA_TYPE.equalsIgnoreCase(item.mediaType())) {
                    collectNcxTitles(zip, item.href(), opfDir, titles, file);
                    break;
                }
            }
        }
        return titles;
    }

    /** EPUB 3 的 nav 文档：取 {@code <nav>} 下的 {@code <a href>}。 */
    private void collectNavTitles(ZipFile zip, String navHref, String opfDir,
                                  Map<String, String> titles, Path file) {
        byte[] raw = readEntry(zip, navHref, file);
        if (raw == null) {
            return;
        }
        Document doc;
        try {
            doc = parseXml(raw, "导航文件 " + navHref, file);
        } catch (BookParseException e) {
            return; // 导航文件坏了不影响正文，静默跳过
        }
        String navDir = directoryOf(navHref);
        for (Element anchor : byLocalName(doc.getDocumentElement(), "a")) {
            String href = anchor.getAttribute("href");
            String label = normalize(anchor.getTextContent());
            if (!href.isBlank() && !label.isBlank()) {
                titles.putIfAbsent(resolve(navDir, href), label);
            }
        }
    }

    /** EPUB 2 的 ncx：{@code <navPoint>} 里有 content@src 和 navLabel/text。 */
    private void collectNcxTitles(ZipFile zip, String ncxHref, String opfDir,
                                  Map<String, String> titles, Path file) {
        byte[] raw = readEntry(zip, ncxHref, file);
        if (raw == null) {
            return;
        }
        Document doc;
        try {
            doc = parseXml(raw, "目录文件 " + ncxHref, file);
        } catch (BookParseException e) {
            return;
        }
        String ncxDir = directoryOf(ncxHref);
        for (Element point : byLocalName(doc.getDocumentElement(), "navPoint")) {
            String label = null;
            List<Element> labels = byLocalName(point, "navLabel");
            if (!labels.isEmpty()) {
                label = normalize(labels.get(0).getTextContent());
            }
            List<Element> contents = byLocalName(point, "content");
            if (label != null && !label.isBlank() && !contents.isEmpty()) {
                String src = contents.get(0).getAttribute("src");
                if (!src.isBlank()) {
                    titles.putIfAbsent(resolve(ncxDir, src), label);
                }
            }
        }
    }

    /** 目录里没记这一章时，退回用文档内的第一个标题。 */
    private String firstHeading(ZipFile zip, String href) {
        byte[] raw = readEntry(zip, href, null);
        if (raw == null) {
            return null;
        }
        Document doc;
        try {
            doc = parseXml(raw, "正文 " + href, null);
        } catch (BookParseException e) {
            return null;
        }
        for (String tag : List.of("h1", "h2", "h3", "h4", "h5", "h6")) {
            List<Element> found = byLocalName(doc.getDocumentElement(), tag);
            if (!found.isEmpty()) {
                String text = normalize(found.get(0).getTextContent());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        List<Element> titles = byLocalName(doc.getDocumentElement(), "title");
        if (!titles.isEmpty()) {
            String text = normalize(titles.get(0).getTextContent());
            return text.isBlank() ? null : text;
        }
        return null;
    }

    // ==================== XHTML → 内容块 ====================

    /**
     * 把一章的 XHTML 转成内容块。
     *
     * <p><b>只抽文本和图片，不保留任何结构或脚本</b> —— 这既是简化，
     * 也是安全边界（见类注释）。
     *
     * <p>解析失败时不抛异常，退化成"把标签全部剥掉当成一段文字"。
     * 现实里的 EPUB 有大量不严格合规的 XHTML，严格解析会把整本书判死；
     * 而用户需要的是"能读"，不是"合规"。
     */
    private List<ChapterBlock> toBlocks(byte[] raw, String href, ZipFile zip,
                                       String bookId, Path file) {
        List<ChapterBlock> blocks = new ArrayList<>();
        Document doc;
        try {
            doc = parseXml(raw, "正文 " + href, file);
        } catch (BookParseException e) {
            String text = stripTags(new String(raw, StandardCharsets.UTF_8));
            if (!text.isBlank()) {
                blocks.add(new ChapterBlock.Paragraph(text));
            }
            return blocks;
        }
        Element body = bodyOf(doc);
        walk(body, blocks, zip, bookId, directoryOf(href), href);
        return blocks;
    }

    private void walk(Element element, List<ChapterBlock> out, ZipFile zip, String bookId,
                      String docDir, String docHref) {
        String tag = localName(element.getTagName());

        // 脚本与样式：整棵子树丢掉。它们不是内容，留着只会变成奇怪的正文
        if (tag.equals("script") || tag.equals("style") || tag.equals("head")) {
            return;
        }

        int headingLevel = headingLevel(tag);
        if (headingLevel > 0) {
            // 标题必须产 Heading 而不是 Paragraph：渲染层靠块类型决定字号与留白，
            // 降级成段落的话整章看起来就是一坨同样大小的文字（与 TXT 解析器对齐）
            addHeading(out, headingLevel, element.getTextContent());
            return;
        }
        if (tag.equals("img")) {
            ChapterBlock.Image image = imageBlock(element, zip, bookId, docDir);
            if (image != null) {
                out.add(image);
            }
            return;
        }
        if (tag.equals("p") || tag.equals("blockquote") || tag.equals("li") || tag.equals("td")) {
            addParagraph(out, element.getTextContent());
            // 段落里可能还夹着插图（<p><img/></p> 很常见）
            for (Element nested : byLocalName(element, "img")) {
                ChapterBlock.Image image = imageBlock(nested, zip, bookId, docDir);
                if (image != null) {
                    out.add(image);
                }
            }
            return;
        }

        // 其它容器（div / section / body …）：递归进去
        int before = out.size();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                walk(childElement, out, zip, bookId, docDir, docHref);
            }
        }
        // 递归没产出任何块，但这个容器自己有文字（<div>直接放正文的情况），
        // 那就把它自己当成一段，免得丢内容
        if (out.size() == before) {
            addParagraph(out, element.getTextContent());
        }
    }

    private void addParagraph(List<ChapterBlock> out, String rawText) {
        String text = normalize(rawText);
        if (!text.isBlank()) {
            out.add(new ChapterBlock.Paragraph(text));
        }
    }

    private void addHeading(List<ChapterBlock> out, int level, String rawText) {
        String text = normalize(rawText);
        if (!text.isBlank()) {
            out.add(new ChapterBlock.Heading(level, text));
        }
    }

    private static int headingLevel(String tag) {
        return switch (tag) {
            case "h1" -> 1;
            case "h2" -> 2;
            case "h3" -> 3;
            case "h4" -> 4;
            case "h5" -> 5;
            case "h6" -> 6;
            default -> 0;
        };
    }

    /**
     * 抽一张插图：从 zip 里读出来写进缓存目录，返回绝对路径。
     *
     * <p>抽出来的原因见 {@link #cacheRoot} 的说明 —— 渲染器只认文件路径。
     * 已经抽过且大小一致就跳过，翻页来回切不会重复写盘。
     *
     * <p>抽不出来就返回 null（跳过这一张），不报错：
     * 缺一张图的用户体验远好于"整章打不开"。
     */
    private ChapterBlock.Image imageBlock(Element img, ZipFile zip, String bookId, String docDir) {
        String src = img.getAttribute("src");
        if (src.isBlank()) {
            return null;
        }
        String entryName = resolve(docDir, src);
        ZipEntry entry = findEntry(zip, entryName);
        if (entry == null || entry.getSize() > MAX_IMAGE_BYTES) {
            return null;
        }
        Path target = cacheFile(bookId, entryName);
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != entry.getSize()) {
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new ChapterBlock.Image(target.toAbsolutePath().toString());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 缓存文件名：把 zip 内的路径压平成单层。
     *
     * <p>不能直接照搬 zip 里的路径 —— 它可能带 {@code ..}，
     * 拼出来会写到缓存目录外面去（路径穿越）。压平之后天然没有这个问题。
     */
    private Path cacheFile(String bookId, String entryName) {
        String flat = entryName.replace('/', '_').replace('\\', '_');
        flat = flat.replaceAll("[^A-Za-z0-9._-]", "_");
        if (flat.length() > 120) {
            flat = flat.substring(flat.length() - 120);
        }
        return cacheRoot.resolve(bookId).resolve(flat);
    }

    // ==================== 低层工具 ====================

    private ZipFile openZip(Path file) throws BookParseException {
        try {
            return new ZipFile(file.toFile());
        } catch (IOException e) {
            throw new BookParseException("打不开这个 EPUB（它可能不是有效的 zip 包）："
                    + e.getMessage(), pathOf(file), e);
        }
    }

    /**
     * 读一个 zip 条目。
     *
     * <p>先按名字精确找，找不到再<b>忽略大小写</b>找一遍：
     * 有些打包工具会改动大小写，而 zip 的条目名是区分大小写的，
     * 只精确匹配的话会"文件明明在里面却读不出来"。
     */
    private byte[] readEntry(ZipFile zip, String name, Path fileForError) {
        ZipEntry entry = findEntry(zip, name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) {
            return direct;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析 XML，顺手做 XXE 加固。
     *
     * <p>三件事必须都做：禁止外部实体、禁止 XInclude、不展开实体引用。
     * 只做一件是不够的 —— 这类漏洞的组合很多，
     * 而 EPUB 是典型"用户从网上下载、来源完全不可信"的输入。
     */
    private static Document parseXml(byte[] raw, String what, Path file) throws BookParseException {
        try {
            return newBuilder().parse(new ByteArrayInputStream(raw));
        } catch (Exception e) {
            throw new BookParseException("解析" + what + "失败：" + e.getMessage(), pathOf(file), e);
        }
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            } catch (Exception ignored) {
                // 个别实现不支持这两个开关。宁可用默认配置继续解析，
                // 也不要因为加固失败就让整本书打不开 —— 上面的三件事仍然生效
            }
            return factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("创建 XML 解析器失败", e);
        }
    }

    /** 按"去掉命名空间前缀后的本地名"找元素。兼容各种前缀写法。 */
    private static List<Element> byLocalName(Element root, String name) {
        List<Element> found = new ArrayList<>();
        if (root == null) {
            return found;
        }
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element element
                    && localName(element.getTagName()).equals(name)) {
                found.add(element);
            }
        }
        return found;
    }

    private static String localName(String tagName) {
        int colon = tagName.indexOf(':');
        String local = (colon < 0) ? tagName : tagName.substring(colon + 1);
        return local.toLowerCase(Locale.ROOT);
    }

    private static String firstText(Document doc, String localTagName) {
        List<Element> found = byLocalName(doc.getDocumentElement(), localTagName);
        return found.isEmpty() ? null : found.get(0).getTextContent();
    }

    private static Element bodyOf(Document doc) {
        List<Element> bodies = byLocalName(doc.getDocumentElement(), "body");
        return bodies.isEmpty() ? doc.getDocumentElement() : bodies.get(0);
    }

    /**
     * 归一化文本：合并空白、去掉首尾。
     *
     * <p>🔴 必须显式带上 {@code 　}（U+3000 全角空格）和 {@code  }
     * （{@code &nbsp;}）—— 它们都不在 Java 正则的 {@code \s} 里。
     * 漏掉的症状是段落里藏着一堆看不见的字符，界面上表现为"莫名其妙的缩进"。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\s\\u3000\\u00a0]+", " ").trim();
    }

    /** 兜底路径：XML 解析不了时，把标签全剥掉当纯文本。 */
    private static String stripTags(String html) {
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        return normalize(text);
    }

    /** 取路径里的目录部分，用于解析相对引用。 */
    private static String directoryOf(String zipPath) {
        int slash = zipPath.lastIndexOf('/');
        return slash < 0 ? "" : zipPath.substring(0, slash);
    }

    /**
     * 解析相对路径。
     *
     * <p>自己实现而不是用 {@code Path.resolve}，因为 zip 内的路径
     * 一律是 {@code /} 分隔，而 Windows 的 {@code Path} 会把 {@code /}
     * 认成合法分隔符、却在 {@code ..} 的处理上带来平台差异。
     * 这里要的是"URL 语义"，不是"文件系统语义"。
     *
     * <p>顺手做了两件事：去掉 {@code #} 片段（同一章内的锚点不是文件名），
     * 以及 URL 解码（中文文件名在包里是 {@code %E4%B8%AD} 这种形式）。
     */
    private static String resolve(String baseDir, String href) {
        String raw = href.trim().replace('\\', '/');
        int hash = raw.indexOf('#');
        if (hash >= 0) {
            raw = raw.substring(0, hash);
        }
        if (raw.isEmpty()) {
            return "";
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = raw;
        }
        String combined = decoded.startsWith("/")
                ? decoded.substring(1)
                : (baseDir.isEmpty() ? decoded : baseDir + "/" + decoded);

        Deque<String> parts = new ArrayDeque<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private static void requireReadableFile(Path file) throws BookParseException {
        if (file == null) {
            throw new BookParseException("文件路径为空", "");
        }
        if (!Files.isRegularFile(file)) {
            throw new BookParseException("文件不存在或不是普通文件", pathOf(file));
        }
    }

    private static String pathOf(Path file) {
        return file == null ? "" : file.toString();
    }
}
