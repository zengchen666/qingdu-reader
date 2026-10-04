package com.qingdu.core.parser.epub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 造测试用的 EPUB。
 *
 * <p><b>为什么要在代码里现造，而不是放一个 .epub 二进制进仓库？</b>
 *
 * <ol>
 *   <li>二进制样例改不了 —— 想试"没有目录文件的书"就得再放一个文件，
 *       很快就变成一堆不知彼此差在哪的黑盒子；</li>
 *   <li>仓库里的二进制进不了 code review，谁也看不出它到底长什么样；</li>
 *   <li>这里是拼 zip，几行就能改出一个变体（EPUB 2 / EPUB 3 / 无目录 / 坏 XML）。</li>
 * </ol>
 *
 * <p>顺带说明：EPUB 的本质就是"一个 zip + 若干 XML"，
 * 这段造文件的代码本身也是对那个结构的注解。
 */
final class EpubTestBooks {

    private EpubTestBooks() {
    }

    /** 一章的内容：标题 + 若干段落（可选插图）。 */
    record Page(String fileName, String title, String bodyHtml, boolean withImage) {
    }

    /**
     * 造一本 EPUB 3（用 nav 文档做目录）。
     *
     * @param withToc 是否写目录文件 —— false 用来验"目录缺失时的回退"
     */
    static Path epub3(Path dir, String name, boolean withToc) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        entries.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/container.xml", ("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""").getBytes(StandardCharsets.UTF_8));

        String manifestItems = """
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="img1" href="images/cover.png" media-type="image/png"/>""";

        entries.put("OEBPS/content.opf", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>星尘纪</dc:title>
                    <dc:creator>佚名</dc:creator>
                    <dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                """ + manifestItems + """
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                    <itemref idref="nav" linear="no"/>
                  </spine>
                </package>""").getBytes(StandardCharsets.UTF_8));

        if (withToc) {
            entries.put("OEBPS/nav.xhtml", ("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body><nav epub:type="toc"><ol>
                        <li><a href="chap1.xhtml">第一章 落星</a></li>
                        <li><a href="chap2.xhtml">第二章 远行</a></li>
                      </ol></nav></body>
                    </html>""").getBytes(StandardCharsets.UTF_8));
        }

        entries.put("OEBPS/chap1.xhtml", page("第一章 落星",
                "<p>夜色像一块浸了水的布。</p><p>他推开门，风灌了进来。</p>", false)
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/chap2.xhtml", page("第二章 远行",
                "<p>天亮之后他就上路了。</p><img src=\"images/cover.png\" alt=\"封面\"/>", true)
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/images/cover.png", tinyPng());

        return write(dir, name, entries);
    }

    /** 造一本 EPUB 2（用 toc.ncx 做目录，且正文里带脚本 —— 用来验脚本被丢掉）。 */
    static Path epub2(Path dir, String name) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        entries.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/container.xml", ("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""").getBytes(StandardCharsets.UTF_8));

        entries.put("content.opf", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>旧版书</dc:title>
                    <dc:creator>某位作者</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                  </spine>
                </package>""").getBytes(StandardCharsets.UTF_8));

        entries.put("toc.ncx", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="p1">
                      <navLabel><text>序章</text></navLabel>
                      <content src="chap1.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>""").getBytes(StandardCharsets.UTF_8));

        entries.put("chap1.xhtml", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>序章</title>
                    <script>alert('不该被执行')</script>
                  </head>
                  <body>
                    <h1>序章</h1>
                    <p>这是第一段。</p>
                    <div><p>这是第二段，藏在一个 div 里。</p></div>
                  </body>
                </html>""").getBytes(StandardCharsets.UTF_8));

        return write(dir, name, entries);
    }

    /** 造一个"不是 EPUB"的文件：zip 里没有 container.xml。 */
    static Path notAnEpub(Path dir, String name) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("hello.txt", "这不是电子书".getBytes(StandardCharsets.UTF_8));
        return write(dir, name, entries);
    }

    /** 造一个正文 XML 坏掉的包：用来验"解析失败不崩，退化成纯文本"。 */
    static Path brokenChapter(Path dir, String name) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", ("""
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""").getBytes(StandardCharsets.UTF_8));
        entries.put("c.opf", ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>坏书</dc:title></metadata>
                  <manifest><item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>""").getBytes(StandardCharsets.UTF_8));
        // 故意不闭合标签
        entries.put("chap1.xhtml", "<html><body><p>没闭合的段落".getBytes(StandardCharsets.UTF_8));
        return write(dir, name, entries);
    }

    private static String page(String title, String bodyHtml, boolean unused) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>"""
                + title + """
                </title></head>
                  <body><h1>"""
                + title + "</h1>" + bodyHtml + """
                  </body>
                </html>""";
    }

    /** 一个 1×1 的合法 PNG。够验证图片被抽出来且能被 JavaFX 读进去。 */
    static byte[] tinyPng() {
        return new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00,
                (byte) 0x1F, (byte) 0x15, (byte) 0xC4, (byte) 0x89,
                0x00, 0x00, 0x00, 0x0A, 'I', 'D', 'A', 'T',
                0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
                0x00, 0x00, 0x00, 0x00, 'I', 'E', 'N', 'D',
                (byte) 0xAE, (byte) 0x42, (byte) 0x60, (byte) 0x82};
    }

    private static Path write(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /** 把若干 byte[] 拼起来（给测试里手工拼 zip 内容用）。 */
    static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }
}
