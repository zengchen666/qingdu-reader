package com.qingdu.reader.library;

import com.qingdu.store.model.LibraryHit;
import com.qingdu.store.model.LibrarySearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把 {@link LibrarySearchResult} 变成"界面可以直接渲染的东西" —— 行模型与提示文案。
 *
 * <p><b>为什么要单独抽一个类？</b>因为 {@code SearchPanel} 是个 JavaFX 控件，
 * 而本项目 {@code qingdu-desktop} 的单元测试<b>刻意不碰界面</b>
 * （见 {@code ChapterNavBarTest} 那条约定：只测"不碰界面"的逻辑）。
 * 于是"跨书结果怎么分组""覆盖度怎么措辞"这两件最容易出错的事就都测不到。
 * 抽到这里之后它们是纯函数，跑一次 {@code mvn test} 毫秒级完成。
 *
 * <p>本类<b>没有 JavaFX 依赖</b>，也没有任何状态，方法都是静态的。
 */
public final class LibrarySearchPresenter {

    private LibrarySearchPresenter() {
    }

    /**
     * 结果列表的一行。
     *
     * <p>用 sealed 而不是"标题为 null 的 {@link LibraryHit}"来区分两种行：
     * 那种做法会让"分组头"和"真命中"共享一个类型，
     * 于是每个渲染处都要写一遍 {@code if (row.isHeader())}，
     * 而且忘了判的那处会安静地渲染出一个空行 —— 不报错，只是界面少一条。
     * 分成两个类型之后，Java 编译器会逼着每个 {@code switch} 写全。
     */
    public sealed interface Row {

        /**
         * 一本书的分组头。
         *
         * @param bookId    书 ID
         * @param bookTitle 书名
         * @param hitCount  这本书命中了几章
         * @param totalHint 全库共搜了几本书（给用户一个"这只是其中一部分"的参照）
         */
        record BookHeader(String bookId, String bookTitle, int hitCount, int totalHint)
                implements Row {
        }

        /** 一条章节命中。 */
        record ChapterRow(LibraryHit hit) implements Row {
        }
    }

    /**
     * 把命中列表摊成"分组头 + 章节行"的交替序列。
     *
     * <p>🔴 <b>分组只在书名真的变了时才插头</b>，不能每行都插。
     * 同一个 {@code bookId} 的命中在结果里是连续的（{@code SearchStore} 已按
     * 书名 + 章号排好序），所以判据是"和上一行的 bookId 不同"。
     * 那样写而不是"每本书都补一个头"，是因为后一种写法在排序被打乱时
     * 会吐出两个同名分组头 —— 而顺序恰恰是排序函数负责的事，
     * 界面不该再假设一次。
     *
     * @param hits 已按展示顺序排好的命中
     * @param totalBooks 本次检索涉及的书数；{@code <= 0} 时不显示"共 N 本"
     */
    public static List<Row> toRows(List<LibraryHit> hits, int totalBooks) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        // 先按书收集齐，再统一输出"头 + 该书的所有命中"。
        // 刻意不写成"边走边回头往前面插头"：那样要算插入位置，
        // 而位置算错时不会报错，只会安静地少一个头或多一个头。
        // 两趟遍历的代价是零（命中最多几百条），换来的是不用算位置。
        List<Row> rows = new ArrayList<>(hits.size() + 8);
        String currentBook = null;
        String currentTitle = "";
        List<LibraryHit> group = new ArrayList<>();
        for (LibraryHit hit : hits) {
            if (currentBook != null && !hit.bookKey().equals(currentBook)) {
                emitGroup(rows, currentBook, currentTitle, group.size(), totalBooks, group);
                group = new ArrayList<>();
            }
            currentBook = hit.bookKey();
            currentTitle = hit.bookTitle();
            group.add(hit);
        }
        emitGroup(rows, currentBook, currentTitle, group.size(), totalBooks, group);
        return rows;
    }

    private static void emitGroup(List<Row> rows, String bookId, String title, int count,
                                  int totalBooks, List<LibraryHit> group) {
        rows.add(new Row.BookHeader(bookId, title, count, totalBooks));
        for (LibraryHit hit : group) {
            rows.add(new Row.ChapterRow(hit));
        }
    }

    /**
     * 生成结果区上面那行提示。
     *
     * <p><b>本类存在的最主要理由就是这一个方法。</b>
     * 跨书检索的零结果有两种完全不同的成因：
     * "40 本都搜了，确实没有" 和 "只有 2 本建了索引，另外 38 本压根没搜"。
     * 后者如果只报"没有找到"，用户会以为全书都没有这个词 ——
     * 那是一个<b>沉默的错误答案</b>，比崩溃更糟，因为它看起来完全正常。
     *
     * <p>所以措辞必须把三件事说清楚：搜了几本书、结果里命中几本、还有几本没搜。
     *
     * @param query  用户输入的词（回显用）
     * @param result 本次结果
     * @return 一行提示文案
     */
    public static String summary(String query, LibrarySearchResult result) {
        if (result == null) {
            return "";
        }
        int unindexed = result.unindexedBooks();
        if (result.indexedBooks() == 0) {
            if (result.libraryBooks() == 0) {
                return "书库还是空的，先导入几本书再来搜。";
            }
            return "书库里有 " + result.libraryBooks() + " 本书，一本都还没有建立索引，"
                    + "所以搜不到任何内容。建立索引后才能搜。";
        }

        StringBuilder text = new StringBuilder();
        if (result.hitCount() == 0) {
            text.append("已搜索 ").append(result.indexedBooks()).append(" 本书，没有找到「")
                    .append(query).append("」");
        } else {
            text.append("找到 ").append(result.hitCount()).append(" 章，来自 ")
                    .append(result.hitsByBook().size()).append(" 本书");
        }
        if (result.truncated()) {
            // 截断必须说出来：用户看到的条数不是全部，
            // 不说清楚就会以为"就这些了"，进而得出"另一本书里也没有"的错误结论
            text.append("（已截断，另有 ").append(result.unchecked()).append(" 章未校验");
            if (result.skippedBooks() > 0) {
                text.append("、").append(result.skippedBooks()).append(" 本书没轮到");
            }
            text.append("）");
        }
        if (unindexed > 0) {
            text.append("    ·    还有 ").append(unindexed).append(" 本没建索引，没被搜索");
        }
        text.append("    ·    耗时 ").append(result.elapsedMs()).append(" ms");
        return text.toString();
    }

    /**
     * 界面该怎么呈现"这次搜得全不全" —— 决定要不要显示"建立索引"按钮。
     *
     * <p>刻意只区分三种而不是两种：
     * "全都没建索引"（<b>必须</b>提示，用户什么结果都得不到且不知道为什么）、
     * "部分没建"（<b>应该</b>提示，结果有用但不全）、
     * "全部有索引"（不用打扰）。
     */
    public enum Coverage {
        /** 一本都没建索引：不提示就等于功能看起来是坏的。 */
        NONE,
        /** 部分书没建索引：结果可用但不完整。 */
        PARTIAL,
        /** 所有书都建了索引。 */
        FULL
    }

    public static Coverage coverageOf(LibrarySearchResult result) {
        if (result == null) {
            return Coverage.FULL;
        }
        // 🔴 先看"书库里有没有书"，再看"有没有建索引"。
        // 顺序反了的话，空书库会落进 NONE，界面就会对一个空书架喊
        // "有 N 本书没建索引，搜不到任何内容" —— N=0，语气却像几十本，
        // 用户只会觉得界面坏了。空书库该说的是"先导入书"（见 summary）。
        if (result.libraryBooks() == 0) {
            return Coverage.FULL;
        }
        if (result.indexedBooks() == 0) {
            return Coverage.NONE;
        }
        return result.unindexedBooks() > 0 ? Coverage.PARTIAL : Coverage.FULL;
    }

    /** 结果里有没有可点的东西。 */
    public static boolean hasHits(LibrarySearchResult result) {
        return result != null && result.hitCount() > 0;
    }

    /** 行是不是"点一下能跳过去"的章节行（分组头点了没意义）。 */
    public static boolean isJumpable(Row row) {
        return row instanceof Row.ChapterRow;
    }

    /** 行里的命中；不是章节行时返回 null。 */
    public static LibraryHit hitOf(Row row) {
        return (row instanceof Row.ChapterRow chapter) ? chapter.hit() : null;
    }

    /** 分组头的书名；不是分组头时返回 null。 */
    public static String headerTitleOf(Row row) {
        return (row instanceof Row.BookHeader header) ? header.bookTitle() : null;
    }

    /** 行是不是属于 {@code bookId} 那本书。分组头也算 —— 头本来就属于它。 */
    public static boolean belongsTo(Row row, String bookId) {
        if (row == null || bookId == null) {
            return false;
        }
        if (row instanceof Row.BookHeader header) {
            return Objects.equals(header.bookId(), bookId);
        }
        return row instanceof Row.ChapterRow chapter
                && Objects.equals(chapter.hit().bookKey(), bookId);
    }
}
