package com.qingdu.store.model;

import java.util.List;
import java.util.Map;

/**
 * 一次跨书检索的完整结果。
 *
 * <p><b>三个覆盖度数字不是冗余，各回答一个不同的问题：</b>
 * <table border="1">
 *   <caption>覆盖度</caption>
 *   <tr><th>字段</th><th>回答什么</th></tr>
 *   <tr><td>{@code libraryBooks}</td><td>用户书库里有几本书？</td></tr>
 *   <tr><td>{@code indexedBooks}</td><td>其中几本建了索引、<b>真的被搜了</b>？</td></tr>
 *   <tr><td>{@code searchedBooks}</td><td>哪几本真的走到了后过滤这一步？</td></tr>
 * </table>
 *
 * <p><b>为什么必须报它们：</b>索引是<b>懒建</b>的 —— 用户不搜的书就没有索引。
 * 于是搜出 0 条结果时，有两种完全不同的原因：
 * "30 本书都搜过了，确实没有" 和 "只有 2 本建了索引，另外 28 本压根没搜"。
 * 不报覆盖度，界面只能说"没有找到"，用户会以为全书都没有 ——
 * 而真相是"我还没搜过那 28 本"。这是<b>沉默的错误答案</b>，
 * 比报错更糟，因为它让用户以为功能坏了。
 *
 * <p>三个数字的关系：
 * <ul>
 *   <li>{@code unindexedBooks = libraryBooks - indexedBooks}
 *       —— 未建索引的书数，界面要提示"这 28 本没被搜"；</li>
 *   <li>{@code searchedBooks <= indexedBooks}
 *       —— 结果被条数上限截断时，只有前面那几本书的候选真的被校验过，
 *       这时 {@code searchedBooks} 会小于 {@code indexedBooks}。</li>
 * </ul>
 *
 * <p><b>{@code hitsByBook} 也不是装饰</b>：命中可能有几百条，全部平铺出来用户根本看不过来。
 * 界面用它渲染"某书 · N 章命中"的分组头。
 *
 * @param hits           精确命中（已过后过滤），按"书名 → 章号"排序
 * @param hitsByBook     每本书的命中章数，键是 {@link LibraryHit#bookKey()}
 * @param candidateCount SQL 给出的候选<b>章</b>数（跨书合计，后过滤之前）
 * @param libraryBooks   书库里的书总数
 * @param indexedBooks   其中已建索引、真的参与了这次检索的书数
 * @param searchedBooks  实际走到后过滤的书数（被截断时会小于 {@code indexedBooks}）
 * @param truncated      是否因为上限而提前停下
 * @param elapsedMs      本次查询耗时（毫秒）
 */
public record LibrarySearchResult(List<LibraryHit> hits, Map<String, Integer> hitsByBook,
                                  int candidateCount, int libraryBooks, int indexedBooks,
                                  int searchedBooks, boolean truncated, long elapsedMs) {

    public LibrarySearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        hitsByBook = hitsByBook == null ? Map.of() : Map.copyOf(hitsByBook);
        if (candidateCount < 0 || libraryBooks < 0 || indexedBooks < 0 || searchedBooks < 0) {
            throw new IllegalArgumentException("计数不能为负数: candidates=" + candidateCount
                    + " library=" + libraryBooks + " indexed=" + indexedBooks
                    + " searched=" + searchedBooks);
        }
    }

    /**
     * 空结果。
     *
     * <p>⚠️ <b>不要无脑用这个方法返回"没搜到"。</b>
     * 它表示"连一个候选都拿不到"，此时覆盖度数字全是 0，
     * 界面无法区分"书库是空的"和"书库有 30 本但一本都没建索引"。
     * 那两种情况要分别用 {@link #noIndexAtAll(int, long)} 和"搜过了但没命中"来表达。
     *
     * @param libraryBooks 书库里的书总数（要报出来，否则用户看到 0 不知道该怎么办）
     */
    public static LibrarySearchResult empty(int libraryBooks, long elapsedMs) {
        return new LibrarySearchResult(List.of(), Map.of(), 0, libraryBooks, 0, 0, false, elapsedMs);
    }

    /** 整本书没建索引 —— 与"搜过了但没命中"是两回事，界面提示也不一样。 */
    public static LibrarySearchResult noIndexAtAll(int libraryBooks, long elapsedMs) {
        return empty(libraryBooks, elapsedMs);
    }

    public int hitCount() {
        return hits.size();
    }

    /** 还没建索引、因而被排除在这次检索之外的书数。 */
    public int unindexedBooks() {
        return Math.max(0, libraryBooks - indexedBooks);
    }

    /**
     * 建了索引、但这次没轮到它（被上限截断）的书数。
     *
     * <p>只有 {@code truncated} 为真时它才可能非零。
     */
    public int skippedBooks() {
        return Math.max(0, indexedBooks - searchedBooks);
    }

    /**
     * 被上限截掉、没做精确校验的候选章数。
     *
     * <p>和 {@link SearchResult#unchecked()} 同义：<b>是没看，不是"看了不合格"</b>。
     * 两者混为一谈会得出"假阳性很多"的错误结论 —— v0.2.0 的第一版验收脚本就踩过。
     */
    public int unchecked() {
        return truncated ? Math.max(0, candidateCount - hits.size()) : 0;
    }
}
