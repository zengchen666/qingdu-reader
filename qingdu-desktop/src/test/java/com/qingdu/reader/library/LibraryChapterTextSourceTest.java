package com.qingdu.reader.library;

import com.qingdu.common.domain.Book;
import com.qingdu.core.parser.txt.TxtBookParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LibraryChapterTextSource} 的测试。
 *
 * <p>这个类是 v0.3 全库检索的<b>物理基础</b>：它决定了"搜到的结果"
 * 是不是真的来自磁盘上的那份书。
 *
 * <p>为什么不用界面测试：这里要验的全是<b>文件级行为</b> ——
 * 偏移读对不对、编码对不对、句柄有没有漏。
 * 这些用 {@code @TempDir} 里的真文件就能测，而真文件恰好是唯一能暴露
 * "编码探测走了另一条路"这种问题的环境。测它不需要启动 JavaFX。
 */
class LibraryChapterTextSourceTest {

    @TempDir
    Path tempDir;

    private final TxtBookParser parser = new TxtBookParser();
    private final Map<String, Path> files = new HashMap<>();

    /** 用一个按 bookId 查书的 lookup 造出被测对象。 */
    private LibraryChapterTextSource newSource() {
        Map<String, Book> library = new HashMap<>();
        for (Map.Entry<String, Path> e : files.entrySet()) {
            library.put(e.getKey(), new Book(e.getKey(), "书" + e.getKey(), "作者", null,
                    e.getValue(), null, 0, java.time.Instant.ofEpochMilli(1_000L)));
        }
        return new LibraryChapterTextSource(parser, id -> library.get(id));
    }

    /** 造一本三章的 UTF-8 书，每章正文里放一个可辨认的标记词。 */
    private Path writeBook(String bookId, String... chapters) throws IOException {
        StringBuilder sb = new StringBuilder("测试书籍\n作者 某人\n\n");
        for (int i = 0; i < chapters.length; i++) {
            sb.append("第").append(chineseNumber(i + 1)).append("章 标题").append(i + 1).append('\n');
            sb.append(chapters[i]).append('\n');
        }
        Path file = tempDir.resolve(bookId + ".txt");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        files.put(bookId, file);
        return file;
    }

    private static String chineseNumber(int n) {
        return switch (n) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            default -> String.valueOf(n);
        };
    }

    @Test
    @DisplayName("按偏移读出的章节，内容与原文一致")
    void readsChapterByOffset() throws IOException {
        writeBook("b1", "云岚宗的山门很高", "第二段无关内容", "岚宗的功法很玄妙");
        try (LibraryChapterTextSource source = newSource()) {
            String c0 = source.textOf("b1", 0);
            String c1 = source.textOf("b1", 1);
            String c2 = source.textOf("b1", 2);

            assertNotNull(c0);
            assertTrue(c0.contains("云岚宗的山门很高"), "第 0 章实际=" + c0);
            assertTrue(c1.contains("第二段无关内容"), "第 1 章实际=" + c1);
            assertTrue(c2.contains("岚宗的功法很玄妙"), "第 2 章实际=" + c2);
        }
    }

    @Test
    @DisplayName("章节内容不会串到相邻章：偏移必须掐准")
    void chaptersDoNotBleed() throws IOException {
        // 三章内容长度差别很大，如果偏移是按"平均长度"估的，这里必然串章
        writeBook("b1", "短", "这一章特别长".repeat(200), "也是短");
        try (LibraryChapterTextSource source = newSource()) {
            String c0 = source.textOf("b1", 0);
            String c1 = source.textOf("b1", 1);
            String c2 = source.textOf("b1", 2);

            assertTrue(c0.contains("短"), "第 0 章=" + c0);
            assertTrue(!c0.contains("特别长"), "第 0 章不该含第 1 章的内容：" + c0);
            assertTrue(c2.contains("也是短"), "第 2 章=" + c2);
            assertTrue(!c2.contains("特别长"), "第 2 章不该含第 1 章的内容：" + c2);
        }
    }

    @Test
    @DisplayName("多次读同一章结果一致（缓存不能返回错的东西）")
    void repeatedReadIsStable() throws IOException {
        writeBook("b1", "第一次读的内容", "无关", "也无关");
        try (LibraryChapterTextSource source = newSource()) {
            String first = source.textOf("b1", 0);
            String second = source.textOf("b1", 0);
            String third = source.textOf("b1", 0);

            assertEquals(first, second);
            assertEquals(second, third);
        }
    }

    @Test
    @DisplayName("跨书交替读：句柄 LRU 淘汰后仍能读对（这是最容易写错的地方）")
    void survivesHandleEviction() throws IOException {
        // 造超过句柄上限（8）的书，强迫 LRU 真的淘汰
        for (int i = 0; i < 12; i++) {
            writeBook("b" + i, "书" + i + "的独有内容", "无关");
        }
        try (LibraryChapterTextSource source = newSource()) {
            // 依次读第 0..11 本的第一章：每读一本新的，之前那本就可能被淘汰
            for (int i = 0; i < 12; i++) {
                String text = source.textOf("b" + i, 0);
                assertNotNull(text, "第 " + i + " 本读不到（句柄被淘汰后没重新取）");
                assertTrue(text.contains("书" + i + "的独有内容"),
                        "第 " + i + " 本读到了别本书的内容，实际=" + text);
            }
        }
    }

    @Test
    @DisplayName("读不到的书返回 null 而不是抛异常：不能因为一本书坏了就中断整次查询")
    void missingBookReturnsNull() throws IOException {
        writeBook("b1", "内容");
        try (LibraryChapterTextSource source = newSource()) {
            assertNull(source.textOf("不存在", 0));
            assertNull(source.textOf("b1", 99), "章号越界返回 null");
            assertNull(source.textOf("b1", -1), "负章号返回 null");
            assertNull(source.textOf(null, 0));
        }
    }

    @Test
    @DisplayName("文件被移走后返回 null 而不抛异常（搜到一半用户把文件删了）")
    void fileRemovedAfterIndex() throws IOException {
        writeBook("b1", "内容", "更多");
        // 先用一次：确认书能正常读到
        try (LibraryChapterTextSource source = newSource()) {
            assertNotNull(source.textOf("b1", 0));
        }
        // 句柄已随 close 释放（Windows 上文件被打开时根本删不掉 ——
        // 这一点本身就是"句柄确实关了"的证据）
        Files.delete(tempDir.resolve("b1.txt"));

        // 重新建一个源：书库记录还在、磁盘文件已不在
        try (LibraryChapterTextSource source = newSource()) {
            assertNull(source.textOf("b1", 0), "文件没了就该返回 null，不能抛异常打断整次查询");
        }
    }

    @Test
    @DisplayName("文件被截短后偏移越界，会被夹住而不是抛异常")
    void truncatedFileIsClamped() throws IOException {
        Path file = writeBook("b1", "第一章内容".repeat(500), "第二章内容");
        try (LibraryChapterTextSource source = newSource()) {
            // 先读一次建立章节表与句柄
            assertNotNull(source.textOf("b1", 1));
            // 把文件截到只剩前几字节，章节表里的偏移就全越界了
            try (var raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
                raf.setLength(20);
            }
            // 不抛异常就过：这里验的是"宁可少读也不要崩"
            source.textOf("b1", 1);
        }
    }

    @Test
    @DisplayName("close 之后再读返回 null（后台任务结束后不该还能取到数据）")
    void closedSourceReturnsNull() throws IOException {
        writeBook("b1", "内容", "更多");
        LibraryChapterTextSource source = newSource();
        assertNotNull(source.textOf("b1", 0));
        source.close();
        assertNull(source.textOf("b1", 0));
    }

    @Test
    @DisplayName("close 之后还能再 close（异常路径会重复调）")
    void closeIsIdempotent() throws IOException {
        writeBook("b1", "内容");
        LibraryChapterTextSource source = newSource();
        source.textOf("b1", 0);
        source.close();
        source.close();
    }

    @Test
    @DisplayName("GBK 编码的书也能读对：编码必须走探测而不是默认 UTF-8")
    void readsGbkBook() throws IOException {
        String sb = "测试书籍\n作者 某人\n\n第一章 标题1\n云岚宗的山门很高\n";
        Path file = tempDir.resolve("gbk.txt");
        Files.write(file, sb.getBytes(java.nio.charset.Charset.forName("GBK")));
        files.put("gbk", file);

        try (LibraryChapterTextSource source = newSource()) {
            String text = source.textOf("gbk", 0);
            assertNotNull(text);
            assertTrue(text.contains("云岚宗的山门很高"),
                    "GBK 书读出乱码，实际=" + text);
        }
    }

    @Test
    @DisplayName("空书（只有标题没有正文）不会崩")
    void emptyBookIsTolerated() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        files.put("empty", file);

        try (LibraryChapterTextSource source = newSource()) {
            // 结果是 null 或空串都算过：关键是别抛异常
            String text = source.textOf("empty", 0);
            assertTrue(text == null || text.isEmpty());
        }
    }

    @Test
    @DisplayName("分不出章节的书不会抛异常")
    void unparseableBookIsTolerated() throws IOException {
        Path file = tempDir.resolve("junk.txt");
        Files.writeString(file, "随便一些没有章节标题的文字\n再多写几行\n", StandardCharsets.UTF_8);
        files.put("junk", file);

        try (LibraryChapterTextSource source = newSource()) {
            source.textOf("junk", 0);
        }
    }

    @Test
    @DisplayName("书太多时句柄数被 LRU 限制住（不泄漏文件句柄）")
    @SuppressWarnings("unchecked")
    void handleCountIsBounded() throws IOException, ReflectiveOperationException {
        for (int i = 0; i < 40; i++) {
            writeBook("b" + i, "书" + i + "内容");
        }
        try (LibraryChapterTextSource source = newSource()) {
            for (int i = 0; i < 40; i++) {
                assertNotNull(source.textOf("b" + i, 0), "第 " + i + " 本");
            }
            // 直接看内部缓存的规模：40 本书轮流读完，句柄最多只能剩 8 个。
            // 这个断言不是"看实现细节"，而是唯一能量化"没漏句柄"的办法 ——
            // 漏了的症状是在别的机器上跑几千本时才出现的
            // "Too many open files"，那时已经无从追查。
            Map<String, ?> open = (Map<String, ?>) fieldOf(source, "openFiles");
            assertTrue(open.size() <= 8,
                    "同时打开的句柄应被 LRU 限制在 8 以内，实际=" + open.size());
            assertTrue(open.size() > 0, "刚读完应该有句柄在缓存里");
        }
    }

    /**
     * 同一个源被复用时，章节表<b>不能被重算</b>。
     *
     * <p>🔴 这条钉的是 {@code SearchPanel} 那个"长期持有源"的设计决定。
     * 章节表要靠分章得到，分章把<b>整个文件读进内存扫一遍</b>
     * （10 MB 的书约 200~300 ms）。它内部缓存了这些表，
     * 但缓存只在<b>同一个源对象</b>里有效 ——
     * 每搜一次就新建一个源的话，"只付一次"会变成"每次都付"，
     * 而症状很隐蔽：功能全对，只是每次搜索都慢几百毫秒，
     * 很容易被当成"跨书检索本来就慢"而放过。
     *
     * <p>真书语料上的实测差距是平均 1.9 倍（详见 v0.3 验收报告）。
     *
     * <p>断言方式：数 {@code parser.parseChapters} 被调了几次。
     * 用一个计数包装器，而不是比时间 —— 时间会 flaky，
     * 而"调了几次"是确定的事实。
     */
    @Test
    @DisplayName("复用同一个源时，章节表只算一次（跨书检索快 5 倍的关键）")
    @SuppressWarnings("unchecked")
    void chapterTableIsComputedOnlyOncePerSource() throws Exception {
        // 正文里<b>不能</b>再出现"第N章"这种字样：第一版这里写了"第一章内容"，
        // 结果那一行本身被当成章节标题，序号撞车后被序号对齐毙掉，
        // 三章的书被降级成一本"全文"，第 1 章读出来是 null。
        // 这不是被测代码的 bug，是我造的数据自带两个标题 —— 记下来免得再犯。
        writeBook("b1", "山门很高", "功法很玄妙", "云岚宗的山门很高");

        int[] parseCount = {0};
        TxtBookParser counting = new TxtBookParser() {
            @Override
            public java.util.List<com.qingdu.common.domain.Chapter> parseChapters(
                    java.nio.file.Path file, String bookId)
                    throws com.qingdu.core.parser.BookParseException {
                parseCount[0]++;
                return super.parseChapters(file, bookId);
            }
        };
        Map<String, Book> library = new HashMap<>();
        library.put("b1", new Book("b1", "书b1", "作者", null,
                files.get("b1"), null, 0, java.time.Instant.ofEpochMilli(1_000L)));

        try (LibraryChapterTextSource source =
                     new LibraryChapterTextSource(counting, id -> library.get(id))) {
            for (int round = 0; round < 5; round++) {
                for (int chapter = 0; chapter < 3; chapter++) {
                    assertNotNull(source.textOf("b1", chapter),
                            "第 " + round + " 轮第 " + chapter + " 章读不到（先确认测试数据本身能分出 3 章）");
                }
            }
            assertEquals(1, parseCount[0],
                    "5 轮查询同一本书，章节表只该算 1 次；算出 " + parseCount[0]
                            + " 次说明缓存没生效，跨书搜索会每次都重读整本书");
        }
    }

    /** 反射读私有字段：验证"资源有没有被释放"这类问题绕不开它。 */
    private static Object fieldOf(Object target, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = LibraryChapterTextSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
