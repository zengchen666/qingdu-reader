package com.qingdu.reader.library;

import com.qingdu.common.domain.Book;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.QingduStore;
import com.qingdu.store.StoreException;
import com.qingdu.store.model.RecentBook;
import com.qingdu.store.model.SearchDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全库检索的索引准备 —— 把"书库里哪些书还没建索引"这件事变成一个可取消、可报进度的任务。
 *
 * <p><b>为什么要有这么一类？</b>
 * 全库检索的前提是<b>每本书都建了索引</b>，而索引是懒建的（见
 * {@code SearchStore}）—— 用户不搜的书就没有索引。于是第一次用全库检索的人
 * 面对的是"书库里有 40 本，其中 38 本根本没搜过"，如果只回一句"没有找到"，
 * 那就是<b>沉默的错误答案</b>：用户会以为全书都没有这个词。
 *
 * <p>所以全库检索必须先问一句"要不要我把这 40 本都建上索引"，而这个动作
 * 可能是几十秒到几分钟。它必须：
 * <ul>
 *   <li><b>可取消</b> —— 40 本书建到第 3 本时用户反悔了，不能没处喊停；</li>
 *   <li><b>报进度</b> —— "正在建 7 / 38"比一个转圈有用得多；</li>
 *   <li><b>一本失败不影响下一本</b> —— 其中一本书被删了、加密了、格式不对，
 *       不该让另外 37 本也白等；</li>
 *   <li><b>逐本释放内存</b> —— 每本建完就出作用域，整本书的字节立刻可回收。
 *       不这么做的话 40 本会<b>同时</b>留在内存里（10 MB × 40 = 400 MB），
 *       而这正是"整本读进内存"在跨书场景下最致命的地方。
 *       实现上靠的是"用局部变量、别存成字段"——见 {@link #indexOne}。</li>
 * </ul>
 * 这四条都不是界面代码，但都不是"显然会对"的，所以抽出来单独测。
 *
 * <p><b>本类不是线程安全的</b>，一次只跑一个任务。
 */
public final class LibraryIndexTask {

    /** 一本建完之后报一次进度。 */
    public interface Progress {
        /** @param done 已完成本数 @param total 总本数 @param current 当前在处理哪本（可为空） */
        void onBook(int done, int total, Book current);
    }

    /**
     * 一次批量建索引的结果。
     *
     * @param succeeded  成功建索引的本数
     * @param skipped    跳过的本数（已建过 / 文件不在了）
     * @param failed     失败的本数
     * @param cancelled  是否被用户取消
     * @param failures   失败明细（书名 → 原因），用于给用户看清楚"哪本没建成、为什么"
     */
    public record Report(int succeeded, int skipped, int failed, boolean cancelled,
                         Map<String, String> failures) {
        public Report {
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }

        public boolean isCompleteSuccess() {
            return failed == 0 && !cancelled;
        }
    }

    private final QingduStore store;
    private final TxtBookParser parser;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public LibraryIndexTask(QingduStore store) {
        this(store, new TxtBookParser());
    }

    public LibraryIndexTask(QingduStore store, TxtBookParser parser) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为空");
        }
        this.store = store;
        this.parser = (parser == null) ? new TxtBookParser() : parser;
    }

    /** 喊停。正在建的那一本会做完手上的动作再退出，不会写到一半的索引。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 书库里有多少本书<b>还没建索引</b>。
     *
     * <p>注意是"建索引"而不是"打开过"：用户导入了一本书但从没在它里面搜过，
     * 它对全库检索就是不可见的。这个数字决定了要不要提示用户"先建索引"。
     */
    public int pendingCount() {
        List<RecentBook> all = store.books().list();
        int pending = 0;
        for (RecentBook item : all) {
            Book book = item.book();
            Path file = book.filePath();
            if (file == null || !Files.isRegularFile(file)) {
                continue; // 文件没了的书不该算进"待建"，否则数字永远降不下来
            }
            if (!store.search().isIndexed(book.id(), com.qingdu.store.SearchStore.fingerprint(file))) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * 书库里"有文件、可检索、但还没建索引"的书。
     *
     * <p>已建过索引的<b>跳过</b>，而不是重建：重建要重读整本书（几秒），
     * 而它已经能被搜了，没有任何理由重做一遍。
     */
    public List<Book> pendingBooks() {
        List<Book> result = new ArrayList<>();
        for (RecentBook item : store.books().list()) {
            Book book = item.book();
            Path file = book.filePath();
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            if (!store.search().isIndexed(book.id(), com.qingdu.store.SearchStore.fingerprint(file))) {
                result.add(book);
            }
        }
        return result;
    }

    /**
     * 给待建索引的书依次建索引。
     *
     * <p>🔴 <b>每本建完立刻释放 {@link ChapterTextBatch}</b>。
     * 索引只需要把章节文本交给 {@code SearchStore.index}，
     * 交完就没有用了 —— 而留着的话 40 本就是 400 MB 常驻内存。
     * 这里用局部变量而不是字段，字段会一直到最后一次赋值才被回收。
     *
     * @param onlyBooks 只给这些书建索引（{@code null} = 书库里所有待建的）
     * @param progress  每本完成后回调
     */
    public Report run(List<Book> onlyBooks, Progress progress) {
        List<Book> targets = (onlyBooks == null) ? pendingBooks() : onlyBooks;
        int total = targets.size();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        Map<String, String> failures = new LinkedHashMap<>();

        for (int i = 0; i < targets.size(); i++) {
            if (cancelled.get()) {
                return new Report(succeeded, skipped, failed, true, failures);
            }
            Book book = targets.get(i);
            if (progress != null) {
                progress.onBook(i, total, book);
                // 🔴 <b>必须在这里再查一次</b>，不能只在循环开头查。
                // progress 回调正是界面上"取消"按钮被点的那条路径，
                // 而回调的语义是"我<b>正要开始</b>处理这一本"——
                // 所以用户在这个回调里喊停，意思就是"这一本别做了"。
                // 只在循环开头查的话，那一本书照样会被建完索引，
                // 表现为"点了取消，还在跑好几秒"。
                if (cancelled.get()) {
                    return new Report(succeeded, skipped, failed, true, failures);
                }
            }
            try {
                if (indexOne(book)) {
                    succeeded++;
                } else {
                    // 文件没了或没章节：算"跳过"而不是"失败" ——
                    // 用户没做错任何事，不该看到一个红色的失败
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                failures.put(book.title(), String.valueOf(e.getMessage()));
                System.err.println("[轻读] 建立索引失败（" + book.title() + "）：" + e);
            }
        }
        if (progress != null) {
            progress.onBook(total, total, null);
        }
        return new Report(succeeded, skipped, failed, false, failures);
    }

    /**
     * 给一本书建索引，返回是否真的建了。
     *
     * <p>包可见是为了单测能直接验"一本书的索引建得对不对"，
     * 而不用先造一个 40 本的书库。
     *
     * <p>🔴 <b>整本书只在这一本书的调用栈里活着。</b>
     * {@link ChapterTextBatch} 是局部变量，返回后立刻可回收；
     * 如果把它写成字段，批量跑 40 本就是 400 MB 常驻 ——
     * 跨书场景下"整本读进内存"必须<b>一次只用一本</b>。
     */
    boolean indexOne(Book book) throws Exception {
        Path file = book.filePath();
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String fingerprint = com.qingdu.store.SearchStore.fingerprint(file);
        if (store.search().isIndexed(book.id(), fingerprint)) {
            return false;
        }
        // 分章与建索引都要完整读一遍文件；ChapterTextBatch 一次读完
        List<com.qingdu.common.domain.Chapter> chapters = parser.parseChapters(file, book.id());
        if (chapters.isEmpty()) {
            return false;
        }
        ChapterTextBatch texts = ChapterTextBatch.load(file, chapters);
        int total = texts.size();
        List<SearchDocument> docs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            docs.add(new SearchDocument(i, texts.textOf(i)));
        }
        store.search().index(book.id(), fingerprint, docs, null);
        return true;
    }

    /**
     * 造一个全库检索用的原文源。
     *
     * <p>传的是"按 bookId 找书"的函数：全库检索的候选可能来自任意一本书，
     * 它拿不到当前打开的是哪本。
     *
     * <p>🔴 <b>查书走的是实时查询而不是启动时的快照。</b>
     * 快照（{@code list()} 一次装进 Map）看着更省事，但那样这个源就
     * "<b>造出来那一刻的书库</b>"了：用户导入一本新书之后，
     * 老源对新书一无所知，搜出来就是一片空白，而且不报错。
     * 代价是每次开一本新书多一条主键查询 —— 一本书在一轮查询里只开一次，
     * 完全可以接受。
     *
     * <p>🔴 返回<b>具体类型</b>而不是 {@link BookChapterTextSource} 接口：
     * 返回接口的话调用方用不了 try-with-resources，而关不掉就等于
     * 文件句柄一直留着 —— 跨书检索要打开几十本书，这是必漏的。
     *
     * <p>💡 <b>它可以被长期持有并复用</b>（而这正是跨书检索该有的用法）：
     * 里面的章节表缓存价值很大，见 {@link LibraryChapterTextSource}
     * 关于"这个代价只付一次"的注释。每建一个新源，那些表就得重读一遍全文件。
     */
    public LibraryChapterTextSource newTextSource() {
        return new LibraryChapterTextSource(parser,
                bookId -> store.books().find(bookId).orElse(null));
    }

    /** 便捷重载：给书库里所有待建索引的书建。 */
    public Report run(Progress progress) {
        return run(null, progress);
    }
}
