package com.qingdu.reader.library;

import com.qingdu.common.domain.Book;
import com.qingdu.common.util.BookId;
import com.qingdu.core.parser.BookParseException;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.QingduStore;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「一键导入一个文件夹」背后的那段逻辑。
 *
 * <p><b>它只做一件事：把磁盘上的 TXT 登记进书库。不建索引。</b>
 * 这个取舍是这一段设计里最要紧的地方 ——
 * {@link TxtBookParser#parseMetadata} 只读文件头 4KB，
 * 一本几毫秒；而 {@code index()} 要把整个文件读进内存扫一遍找章节，
 * 一本几十到几百毫秒。一个装了两百本的文件夹，
 * "逐个建索引"是几十秒的卡顿，"只记元信息"是一秒内的事。
 *
 * <p>于是导入的代价和书的<b>数量</b>成正比、和书的<b>体积</b>无关。
 * 目录留到用户真正点开那本书时再建（{@code ReaderView.open} 本来就是这个流程），
 * 卡片先显示"尚未建立目录"即可 —— 用户根本感觉不到差别，
 * 因为他不会一次读完两百本。
 *
 * <p><b>为什么继承不了的东西都不抛异常？</b>
 * 导入是一批操作，而"批处理"最忌讳的就是一个坏元素把整批带崩。
 * 某个文件没有读权限、内容损坏、或者是个 0 字节的空壳，
 * 都不该让其余九十九本导不进来。所以单个文件失败只记进
 * {@link Report#failures()}，循环继续。
 *
 * <p>这个类<b>不碰界面</b>，也不开线程 —— 它是个同步的、可以被单元测试
 * 直接调用的纯逻辑类。放进后台线程、更新状态栏这些事由 {@code ReaderView} 负责。
 */
public class BookImporter {

    /**
     * 一次最多扫描多少个文件。
     *
     * <p>不是性能上限，而是<b>误操作的护栏</b>：文件夹选择框里一不小心选到盘符根目录，
     * 或者选到一个几十万文件的下载目录，递归走一遍能把界面卡到用户以为死机。
     * 到了这个数就停下来并在报告里说明，让用户知道"没扫全"，
     * 这比默默扫半小时然后弹出"导入 8 本"要好得多。
     */
    public static final int DEFAULT_MAX_FILES = 2000;

    private final QingduStore store;
    private final TxtBookParser parser;
    private final int maxFiles;

    public BookImporter(QingduStore store) {
        this(store, new TxtBookParser(), DEFAULT_MAX_FILES);
    }

    /** 允许注入切分器和上限，方便测试。 */
    public BookImporter(QingduStore store, TxtBookParser parser, int maxFiles) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为空");
        }
        this.store = store;
        this.parser = (parser == null) ? new TxtBookParser() : parser;
        this.maxFiles = maxFiles <= 0 ? DEFAULT_MAX_FILES : maxFiles;
    }

    /**
     * 导入结果。
     *
     * @param scanned   扫描到的 TXT 文件数（已经过上限截断）
     * @param imported  新入库的书数
     * @param skipped   书库里已有、跳过的书数
     * @param failed    解析或入库失败的文件数
     * @param truncated 是否因为触到 {@link #maxFiles} 上限而没扫全
     * @param failures  失败明细（文件名 + 原因），给用户看的
     */
    public record Report(int scanned, int imported, int skipped, int failed,
                         boolean truncated, List<String> failures) {

        public Report {
            failures = List.copyOf(failures);
        }

        /** 一条给用户看的汇总。界面上直接显示它，不用自己拼字符串。 */
        public String summary() {
            if (scanned == 0) {
                return "这个文件夹里没有找到 TXT 文件";
            }
            StringBuilder sb = new StringBuilder("已导入 ").append(imported).append(" 本");
            if (skipped > 0) {
                sb.append("，跳过 ").append(skipped).append(" 本（书架上已有）");
            }
            if (failed > 0) {
                sb.append("，").append(failed).append(" 本读取失败");
            }
            if (truncated) {
                sb.append("；文件太多，只处理了前 ").append(scanned).append(" 个");
            }
            return sb.toString();
        }
    }

    /**
     * 递归导入一个文件夹里的所有 TXT。
     *
     * <p>不抛异常：文件夹不存在、没有权限这类情况会被翻译成一份
     * {@code scanned = 0} 且带失败说明的报告，交给界面用同一套文案呈现。
     *
     * @param folder 要导入的文件夹
     */
    public Report importFolder(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return new Report(0, 0, 0, 0, false, List.of("不是一个文件夹"));
        }

        List<Path> candidates;
        try {
            candidates = scan(folder);
        } catch (IOException e) {
            return new Report(0, 0, 0, 0, false, List.of("无法读取文件夹：" + e.getMessage()));
        }

        List<String> failures = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (Path file : candidates) {
            try {
                if (isAlreadyOnShelf(file)) {
                    skipped++;
                    continue;
                }
                // 只读文件头拿书名作者；章节索引留到真正打开那本书的时候再建
                Book book = parser.parseMetadata(file);
                store.books().save(book, 0, null);
                imported++;
            } catch (BookParseException | RuntimeException e) {
                // 这里刻意catch得很宽（RuntimeException 已经涵盖了
                // StoreException 等入库失败的异常）：导入是一批操作，
                // 一个坏文件不该让其余九十九本导不进来 ——
                // 所以宁可把它的错误记下来继续走，也不要让整批中断。
                failed++;
                failures.add(file.getFileName() + "：" + e.getMessage());
            }
        }

        boolean truncated = candidates.size() >= maxFiles;
        return new Report(candidates.size(), imported, skipped, failed, truncated, failures);
    }

    /**
     * 这本书是不是已经在书架上了。
     *
     * <p>判重用 {@link BookId}，也就是那个由文件路径推出来的稳定 ID ——
     * 和阅读进度、书签用的是同一个标识。于是"同一本书被导入两次"
     * 和"同一本书被打开过两次"在数据层是同一件事，不会出现两份记录。
     */
    private boolean isAlreadyOnShelf(Path file) {
        return store.books().find(BookId.of(file)).isPresent();
    }

    /**
     * 递归收集文件夹下的 TXT。
     *
     * <p>三个刻意的选择：
     * <ul>
     *   <li><b>不跟随符号链接</b>。Windows 的目录联接（junction）可以指回上层目录，
     *       跟随之后会走进死循环 —— 递归时的经典坑，默认不跟随最省事。</li>
     *   <li><b>{@code visitFileFailed} 返回 CONTINUE</b>。没有权限的目录、
     *       被占用的文件都只跳过，不中断整次导入。</li>
     *   <li><b>按文件数截断</b>。见 {@link #DEFAULT_MAX_FILES}。</li>
     * </ul>
     */
    private List<Path> scan(Path folder) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isTxt(file)) {
                    found.add(file);
                    if (found.size() >= maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static boolean isTxt(Path file) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return false;
        }
        try {
            // 隐藏文件和系统文件多半不是用户想读的小说（比如 desktop.ini）
            return !Files.isHidden(file);
        } catch (IOException e) {
            // 判断不了就当它可见 —— 宁可多导一本，也不要悄悄漏掉
            return true;
        }
    }
}
