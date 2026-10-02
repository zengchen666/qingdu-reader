package com.qingdu.reader.library;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.util.TextCleaner;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.core.text.CharsetDetector;
import com.qingdu.core.text.TextCodec;
import com.qingdu.store.BookChapterTextSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 跨书检索的原文来源 —— <b>按偏移随机读，而不是把整本书读进内存</b>。
 *
 * <p><b>这是 v0.3 最关键的一个类。</b>
 * v0.2.0 的单书检索用 {@code ChapterTextBatch}，它把<b>整本书</b>读进内存
 * （1636 章约 10 MB）—— 那是<b>正确</b>的优化：建索引和后过滤共用同一份字节，
 * 整本读一次 83 ms，比逐章开文件快 280 倍。
 *
 * <p>但同一个做法搬到跨书检索上就是<b>灾难</b>：
 * 候选章分布在几十本书里，"把所有书都读进内存"意味着
 * 50 本 × 10 MB = <b>500 MB</b>，一次搜索就能把堆撑爆。
 * 而绝大多数候选<b>根本不会被校验到</b>（条数上限一集满就停）。
 * 为了一堆用不到的数据占 500 MB，是纯亏。
 *
 * <p><b>所以这里用回 v0.0 就用上的原语：字节偏移 + seek。</b>
 * 分章时每章都记了 {@code startOffset/endOffset}（见 {@link TxtBookParser}），
 * 于是任意一章都能"seek 过去、读那几十 KB、丢掉"，不需要整本在场。
 *
 * <h2>三处缓存，缺一不可</h2>
 * <ol>
 *   <li><b>章节表（{@code chaptersByBook}）</b>：要 seek 就得知道偏移，
 *       而偏移来自分章。分章要读整个文件（{@code parseChapters} 里是
 *       {@code Files.readAllBytes}）—— 但它读完只留一份章节表（几十 KB），
 *       <b>字节本身立刻可回收</b>。这是"读一次、用完就扔"和"一直占着"的区别。
 *       缓存它是因为跨书检索里同一本书的候选是<b>连续</b>的（SQL 已按 book_id 排序），
 *       不会每章都重新分一次章。</li>
 *   <li><b>文件句柄（{@code openFiles}）</b>：不缓存的话每个候选章都要
 *       {@code new RandomAccessFile} + {@code seek} + {@code close}，
 *       上万次 open/close
 *       —— 那是 {@code ChapterTextBatch} 类注释里记的"慢的不是 SQLite，是每章一次的文件打开"，
 *       同一个坑不能再踩一次。用 LRU 限制同时打开的数量，
 *       否则 200 本书就是 200 个句柄（Windows 上会撞句柄上限）。</li>
 *   <li><b>章节文本（{@code chapterCache}）</b>：一个查询里同一章只会被校验一次，
 *       所以它<b>不</b>是性能必需品；留着是因为
 *       "摘要生成"和"精确校验"都要同一段文本，顺手复用省一次解码。
 *       容量刻意很小（见 {@link #CHAPTER_CACHE_SIZE}）——
 *       它的作用是"让相邻几次访问不重复解码"，<b>不是</b>当整本书的缓存用。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * 本类<b>不是</b>线程安全的：内部有可变的 LRU 状态和共享的 {@link RandomAccessFile}。
 * 跨书检索在后台线程里跑（和 v0.2.0 一样），所以只要不并发调用就是安全的。
 * 之所以不花力气加锁：<code>close()</code> 会把所有句柄关掉，
 * 而句柄被另一个线程用着时关它会抛 {@code IOException} ——
 * 与其做一套"关闭中的调用要等"的协议，不如让调用方保证单线程，
 * 这也是 {@link SearchStore#searchAll} 现在的约定。
 */
public final class LibraryChapterTextSource implements BookChapterTextSource, AutoCloseable {

    /**
     * 同时保持打开的文件句柄上限。
     *
     * <p>比这个数小：SQL 是按 {@code book_id} 排序的，
     * 所以实际同时需要的句柄数是"相邻几本书"，通常不超过 4。
     * 给 8 是留足余量，同时把最坏情况（200 本书各命中一章）钉在 8 个句柄。
     */
    private static final int MAX_OPEN_FILES = 8;

    /**
     * 章节文本缓存的条数。
     *
     * <p>一章 6 KB，20 条约 120 KB。刻意给得很小 ——
     * 这个缓存要是在这里悄悄长到"整本书"，那它就退化成了
     * {@code ChapterTextBatch}，而那正是这个类要避开的东西。
     */
    private static final int CHAPTER_CACHE_SIZE = 20;

    /** 每本书的章节表缓存上限；一张表 1636 章约 50 KB，16 本约 800 KB。 */
    private static final int CHAPTER_LIST_CACHE_SIZE = 16;

    private final TxtBookParser parser;

    /**
     * 按 bookId 找出书 —— 只需要拿到源文件路径。
     *
     * <p>🔴 <b>类型是 {@code Book} 而不是 {@code RecentBook}</b>：
     * 本类从头到尾只用到 {@code book.filePath()}，
     * 而 {@code BookStore.find} 返回的就是 {@code Optional<Book>}。
     * 让调用方为一个用不上的字段去做转换（{@code find(...).map(RunContext::toRecentBook).orElse(null)}）
     * 只会把"查得到查不到"和"怎么包装"两件事搅在一起 ——
     * 上一版就是因为这个多写了一层快照 Map，还顺带引入了"老源看不见新书"的 bug。
     */
    private final Function<String, Book> bookLookup;

    /** 章节表缓存。{@code removeEldestEntry} 超过容量就淘汰最旧的一本。 */
    private final Map<String, List<Chapter>> chaptersByBook = lru(CHAPTER_LIST_CACHE_SIZE, null);

    /**
     * 已打开的文件句柄。
     *
     * <p>🔴 淘汰时<b>必须</b>把句柄真关掉（第二个参数就是干这个的）——
     * 200 本书各留一个句柄的话，Windows 上早晚会撞上限，
     * 而且症状是"用着用着突然 {@code Too many open files}"，极难定位。
     */
    private final Map<String, OpenBook> openFiles =
            lru(MAX_OPEN_FILES, OpenBook::closeQuietly);

    /** 章节文本缓存，键是 {@code bookId + "#" + chapterIndex}。 */
    private final Map<String, String> chapterCache = lru(CHAPTER_CACHE_SIZE, null);

    private boolean closed;

    /**
     * @param parser     解析器（用来分章拿偏移）
     * @param bookLookup 按 bookId 找出书（要拿到源文件路径）。
     *                   <b>应当是实时查询而不是快照</b>，否则导入新书后老源搜不到它
     */
    public LibraryChapterTextSource(TxtBookParser parser, Function<String, Book> bookLookup) {
        this.parser = parser == null ? new TxtBookParser() : parser;
        this.bookLookup = bookLookup;
    }

    @Override
    public String textOf(String bookId, int chapterIndex) {
        if (closed || bookId == null || bookId.isBlank() || chapterIndex < 0) {
            return null;
        }
        String key = bookId + "#" + chapterIndex;
        String cached = chapterCache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        try {
            OpenBook open = openBook(bookId);
            if (open == null) {
                return null;
            }
            String text = open.readChapter(chapterIndex);
            // 空串也要缓存：它代表"这一章确实没读到"，
            // 不缓存就会对同一章反复重试，拖慢整次查询
            chapterCache.put(key, text == null ? "" : text);
            return (text == null || text.isEmpty()) ? null : text;
        } catch (IOException e) {
            // 一本书读不出来（比如文件被移走、磁盘拔了）不该让整次跨书查询失败。
            // 记一行日志、这一章当作不命中，然后继续查下一本。
            System.err.println("[轻读] 读取章节失败（" + bookId + " 第 " + chapterIndex + " 章）：" + e);
            return null;
        }
    }

    @Override
    public void close() {
        closed = true;
        for (OpenBook open : openFiles.values()) {
            open.closeQuietly();
        }
        openFiles.clear();
        chapterCache.clear();
        // 章节表刻意不清：它只有几十 KB，而且清它没有任何好处
        //（下次还要重新分章才能读，等于白扔一次全文件读）。
        // 它随本对象被 GC 带走，不属于"必须主动释放"的资源。
    }

    // ==================== 内部 ====================

    /**
     * 拿到这本书的打开状态；句柄不在缓存里就开一个。
     *
     * <p>🔴 <b>这里有个必须警惕的坑：{@code openFiles} 是 LRU，
     * 一次 {@code put} 可能会淘汰掉"正要用的那一个"。</b>
     * 所以代码里 {@code put} 之后<b>必须</b>重新从 map 里取，
     * 而不是用 put 之前拿到的那个对象 —— 那样会对着一个已关闭的句柄读，
     * 现象是"搜到第 N 本书之后结果全空"，非常难查。
     */
    private OpenBook openBook(String bookId) throws IOException {
        OpenBook existing = openFiles.get(bookId);
        if (existing != null) {
            return existing;
        }
        Path file = resolveFile(bookId);
        if (file == null) {
            return null;
        }
        List<Chapter> chapters = chaptersOf(bookId, file);
        if (chapters.isEmpty()) {
            return null;
        }
        // 编码必须和建索引时一致：两边都走 CharsetDetector.detect(Path)（看文件头 256KB），
        // 不一致就会出现"搜得到但校验不过"
        TextCodec codec = CharsetDetector.detect(file).codec();
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        long fileSize = raf.length();

        openFiles.put(bookId, new OpenBook(raf, chapters, codec, fileSize));
        // 重新取：put 可能把别人淘汰掉，但绝不会把自己淘汰（刚 put 的最新）
        return openFiles.get(bookId);
    }

    /** 按 bookId 找源文件；找不到或文件已经不在磁盘上返回 {@code null}。 */
    private Path resolveFile(String bookId) {
        if (bookLookup == null) {
            return null;
        }
        Book book = bookLookup.apply(bookId);
        if (book == null) {
            return null;
        }
        Path path = book.filePath();
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        return path;
    }

    /**
     * 这本书的章节表（含字节偏移），带缓存。
     *
     * <p>代价要说清楚：{@code parseChapters} 内部是 {@code Files.readAllBytes}，
     * <b>确实会把整本书读进内存</b>。但它读完只返回一份章节表，
     * 那几 MB 字节在方法返回后立刻可回收 ——
     * 与 {@code ChapterTextBatch} 把它<b>长期持有</b>是两回事。
     * 跨书检索里这个代价只付一次（章节表缓存住了），
     * 而且只在真的来查这本书时才付，不是"把所有书都过一遍"。
     */
    private List<Chapter> chaptersOf(String bookId, Path file) {
        List<Chapter> cached = chaptersByBook.get(bookId);
        if (cached != null) {
            return cached;
        }
        List<Chapter> parsed;
        try {
            parsed = parser.parseChapters(file, bookId);
        } catch (Exception e) {
            // 解析不了就当这本书没内容；不抛出去打断整次查询
            System.err.println("[轻读] 分章失败（" + bookId + "）：" + e);
            parsed = List.of();
        }
        chaptersByBook.put(bookId, parsed);
        return parsed;
    }

    /**
     * 一个 LRU {@link LinkedHashMap}；{@code accessOrder = true} 才是"最近用过的留下"。
     *
     * @param onEvict 条目被淘汰时的清理动作（可以是 {@code null}）。
     *                <b>文件句柄缓存必须传清理动作</b>：光淘汰不关的话，
     *                跑完一次跨书检索会漏掉几十个句柄，Windows 上迟早撞上限。
     */
    private static <K, V> Map<K, V> lru(int capacity, java.util.function.Consumer<V> onEvict) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean over = size() > capacity;
                if (over && onEvict != null) {
                    onEvict.accept(eldest.getValue());
                }
                return over;
            }
        };
    }

    /** 已打开的一本书：句柄 + 章节表 + 编解码器。 */
    private static final class OpenBook {

        private final RandomAccessFile raf;
        private final List<Chapter> chapters;
        private final TextCodec codec;
        private final long fileSize;

        OpenBook(RandomAccessFile raf, List<Chapter> chapters, TextCodec codec, long fileSize) {
            this.raf = raf;
            this.chapters = chapters;
            this.codec = codec;
            this.fileSize = fileSize;
        }

        /**
         * 按偏移读一章。
         *
         * <p>清洗（{@link TextCleaner#clean}）必须做，而且必须和
         * {@code ChapterTextBatch.textOf} 做同样的事 ——
         * 建索引时写进 FTS 的是清洗后的 token 串，
         * 这里拿来做精确校验的也必须是清洗后的原文。
         * 两边不一致就会出现"搜到了却校验不过"或者"假阳性漏网"。
         */
        String readChapter(int chapterIndex) throws IOException {
            if (chapterIndex < 0 || chapterIndex >= chapters.size()) {
                return null;
            }
            Chapter chapter = chapters.get(chapterIndex);
            // 文件在索引之后被改过时偏移可能越界，夹一下——宁可少读也不要崩
            long start = Math.max(0L, Math.min(chapter.startOffset(), fileSize));
            long end = Math.max(start, Math.min(chapter.endOffset(), fileSize));
            if (end == start) {
                return "";
            }
            int length = (int) Math.min(end - start, Integer.MAX_VALUE);
            byte[] buffer = new byte[length];
            raf.seek(start);
            raf.readFully(buffer);
            return TextCleaner.clean(codec.decodeAll(buffer));
        }

        void closeQuietly() {
            try {
                raf.close();
            } catch (IOException ignored) {
                // 关一个已经坏掉的句柄没什么可恢复的，别打断调用方
            }
        }
    }
}
