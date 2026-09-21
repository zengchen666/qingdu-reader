package com.qingdu.reader.library;

import com.qingdu.store.Database;
import com.qingdu.store.QingduStore;
import com.qingdu.store.model.RecentBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookImporter} 的测试。
 *
 * <p>它测的是这个类里<b>唯一值得测</b>的那部分：递归走目录、认哪种文件、
 * 怎么判重、上限到了怎么办。这些都不依赖界面，所以能像存储层那样
 * 用一个临时目录 + 一个临时数据库直接验完 —— 不用起窗口、不用 mock 文件系统。
 *
 * <p>书的内容刻意写成真实文件头的样子（{@code 书名：xxx} / {@code 作者：xxx}），
 * 因为导入的产出就是"书名和作者有没有提对"，随便写点乱码测不出这个。
 *
 * <p><b>没有覆盖到的一条</b>：单个文件在解析时抛异常（例如扫描到一半文件被删掉）
 * 那条 {@code catch} 分支。它需要一次真实的 I/O 故障才能触发 ——
 * 用测试去造这个（比如依赖"Windows 上建符号链接要管理员权限"）会让测试
 * 变得不可移植，得不偿失。那一段的逻辑只有一行：记一笔、继续下一本。
 */
class BookImporterTest {

    @TempDir
    Path tempDir;

    private QingduStore store;
    private BookImporter importer;

    @BeforeEach
    void setUp() {
        // 每个测试一套全新的库，互不干扰
        store = QingduStore.open(Database.open(tempDir.resolve("library.db")));
        importer = new BookImporter(store);
    }

    /** 造一本"看起来像真的"的 TXT：带书名、作者、章节标题和正文。 */
    private void writeBook(Path path, String title) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                "书名：" + title + "\n作者：某作者\n\n第一章 开始\n正文……\n",
                StandardCharsets.UTF_8);
    }

    private Path booksDir() {
        return tempDir.resolve("books");
    }

    @Test
    @DisplayName("递归导入：子目录、孙目录里的 TXT 都会被收进来")
    void recursiveImport() throws IOException {
        writeBook(booksDir().resolve("甲.txt"), "测试小说甲");
        writeBook(booksDir().resolve("作者A").resolve("乙.txt"), "测试小说乙");
        writeBook(booksDir().resolve("作者A").resolve("系列1").resolve("丙.txt"), "测试小说丙");

        BookImporter.Report report = importer.importFolder(booksDir());

        assertEquals(3, report.scanned());
        assertEquals(3, report.imported());
        assertEquals(0, report.failed());
        assertFalse(report.truncated());
        assertEquals(3, store.books().count());
    }

    @Test
    @DisplayName("非 TXT 一律不碰")
    void ignoresNonTxt() throws IOException {
        writeBook(booksDir().resolve("甲.txt"), "测试小说甲");
        Files.writeString(booksDir().resolve("说明.md"), "# 不是小说");
        Files.writeString(booksDir().resolve("备份.txt.bak"), "也不是");
        Files.writeString(booksDir().resolve("老格式.epub"), "PK");

        BookImporter.Report report = importer.importFolder(booksDir());

        assertEquals(1, report.scanned());
        assertEquals(1, store.books().count());
    }

    @Test
    @DisplayName("再导一次：已有的书被跳过，不会多出第二份记录")
    void skipsBooksAlreadyOnShelf() throws IOException {
        writeBook(booksDir().resolve("甲.txt"), "测试小说甲");
        writeBook(booksDir().resolve("乙.txt"), "测试小说乙");

        importer.importFolder(booksDir());
        BookImporter.Report second = importer.importFolder(booksDir());

        assertEquals(2, second.scanned());
        assertEquals(0, second.imported());
        assertEquals(2, second.skipped());
        // 判重靠的是"由文件路径推出来的 ID"，和阅读进度、书签用的是同一个，
        // 所以"导入两次"和"打开两次"在数据层是同一件事
        assertEquals(2, store.books().count(), "判重之后总数不该变");
    }

    @Test
    @DisplayName("导入只记元信息，章节索引留到真正打开那本书时再建")
    void importDoesNotBuildChapterIndex() throws IOException {
        writeBook(booksDir().resolve("甲.txt"), "测试小说甲");

        importer.importFolder(booksDir());

        RecentBook item = store.books().list().get(0);
        assertEquals("测试小说甲", item.book().title());
        assertEquals("某作者", item.book().authorName().orElseThrow());
        assertEquals(0, item.book().chapterCount());
        assertFalse(item.book().isIndexed());
        // 刚导入 = 还没读过。这条会显示在书架卡片上
        assertFalse(item.progress().started());
    }

    @Test
    @DisplayName("文件夹不存在时给一份能说明问题的报告，而不是抛异常")
    void missingFolderIsReported() {
        BookImporter.Report report = importer.importFolder(tempDir.resolve("不存在的目录"));

        assertEquals(0, report.scanned());
        assertEquals(0, report.imported());
        assertFalse(report.failures().isEmpty(), "得说清楚为什么一本都没导进来");
    }

    @Test
    @DisplayName("空文件夹：报告说「没找到 TXT」，而不是静默什么都不做")
    void emptyFolderIsReported() throws IOException {
        Path root = Files.createDirectories(booksDir());

        BookImporter.Report report = importer.importFolder(root);

        assertEquals(0, report.scanned());
        assertTrue(report.summary().contains("没有找到 TXT"));
    }

    @Test
    @DisplayName("触到文件数上限就停下，并在报告里说明没扫全")
    void stopsAtMaxFiles() throws IOException {
        for (int i = 0; i < 5; i++) {
            writeBook(booksDir().resolve("第" + i + "本.txt"), "书" + i);
        }
        BookImporter small = new BookImporter(store, null, 2);

        BookImporter.Report report = small.importFolder(booksDir());

        assertEquals(2, report.scanned());
        assertEquals(2, report.imported());
        assertTrue(report.truncated(), "被截断这件事必须让用户知道");
        assertTrue(report.summary().contains("只处理了前 2 个"));
    }

    @Test
    @DisplayName("汇总文案把新导入的和跳过的都算进去")
    void summaryMentionsEverything() throws IOException {
        writeBook(booksDir().resolve("甲.txt"), "测试小说甲");
        importer.importFolder(booksDir());
        writeBook(booksDir().resolve("乙.txt"), "测试小说乙");

        BookImporter.Report report = importer.importFolder(booksDir());

        assertEquals(1, report.imported());
        assertEquals(1, report.skipped());
        assertTrue(report.summary().contains("已导入 1 本"));
        assertTrue(report.summary().contains("跳过 1 本"));
    }
}
