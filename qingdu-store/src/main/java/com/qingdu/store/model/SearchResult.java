package com.qingdu.store.model;

import java.util.List;

/**
 * 一次查询的完整结果 —— 除了命中列表，还带上"候选数"这个诊断量。
 *
 * <p><b>为什么要单列一个 candidateCount？</b>
 * 中文 bigram 检索有个绕不开的特性：FTS 只能保证"两个 token 都出现"，
 * 不能保证它们<b>相邻</b>。所以 SQL 给的是<b>候选</b>，
 * 拿原文再校验一次之后才是<b>精确</b>结果（见 {@code SearchStore} 的注释）。
 *
 * <p>这两个数字放在一起，才能在真书语料上回答"假阳性到底有多少"。
 * 不过要小心：只有当 {@code truncated} 为假（结果没被截断）时，
 * 两者之差才等于假阳性数。想统计"全书到底有多少章命中"，
 * 就得传一个足够大的上限把整本书都过一遍（验收脚本就是这么做的）。
 *
 * @param hits          精确命中（已过后过滤），按章节序号升序
 * @param candidateCount SQL 给出的候选章数（后过滤之前）
 * @param truncated     是否因为条数上限而提前停下（见下方说明）
 * @param elapsedMs     本次查询耗时（毫秒），只用于诊断和验收
 */
public record SearchResult(List<SearchHit> hits, int candidateCount, boolean truncated,
                           long elapsedMs) {

    public SearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (candidateCount < 0) {
            throw new IllegalArgumentException("候选数不能为负数: " + candidateCount);
        }
    }

    /** 本次返回的命中章数。 */
    public int hitCount() {
        return hits.size();
    }

    /**
     * 被条数上限截掉之后，还剩多少个候选章没做精确校验。
     *
     * <p><b>为什么这个数字必须是"剩余候选"而不是"假阳性"？</b>
     * 一个常见词（"萧炎"）在整本书里能命中 1600 多章，
     * 查一次就把 1600 个摘要全造出来没有意义 —— 用户看不到第 301 条。
     * 所以 {@code search} 一旦集满上限就<b>立刻停下，不再往后校验</b>：
     * 剩下的候选是"没看"，不是"看了但不合格"。
     *
     * <p>把两者混为一谈会得出完全错误的结论。第一版验收脚本就踩了这个坑：
     * 它拿 {@code candidateCount - hitCount()} 当"假阳性数"，于是
     * 「萧炎」被报成有 1322 个假阳性 —— 实际上那是没做的部分。
     */
    public int unchecked() {
        return truncated ? candidateCount - hits.size() : 0;
    }
}
