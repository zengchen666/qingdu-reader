package com.qingdu.reader.ai;

/**
 * 从自然语言问题里提取"检索词"。
 *
 * <h2>🔴 为什么要单独一个类，而不是 {@code AiPanel} 的静态方法</h2>
 * {@code AiPanel extends VBox}，测它的静态方法<b>仍然要加载 JavaFX 控件类</b>，
 * 在没有图形环境的机器上（CI、surefire 默认 headless）会炸。
 * 项目里已有 {@code ChapterNavBarTest} 遵守"只测静态纯函数"的约定，
 * 这个类是同一个约定下的又一处 —— 纯逻辑就该待在纯类里。
 *
 * <h2>为什么它决定整条链路成败</h2>
 * 轻读这层的检索词直接进 {@code SearchStore.search()}。词提得不对，
 * FTS 就召不回任何章节，后面的切片、重排、生成全都无从谈起 ——
 * 而且<b>症状是"原文里没有提到"而不是报错</b>，极难定位。
 *
 * <h2>为什么只去疑问词、不做分词</h2>
 * 真正的 n-gram 重排在 Python 侧做（{@code retrieval._query_terms}）。
 * 轻读这层只需要"大致相关"，两边都做就是两处真理。
 */
public final class AskKeyword {

    /**
     * 中文里高频出现在问句里的疑问词。
     *
     * <p><b>为什么是这个清单而不是一套语法</b>：中文问句没有空格，
     * 做不出轻量的分词；而这些词去掉之后，剩下的几乎总是专有名词
     * （人名、地名、门派、物品），正是 FTS 擅长找的东西。
     *
     * <p>🔴 <b>清单里刻意没有 {@code 什么}</b>：「什么」单独出现时通常不带信息，
     * 但「萧炎用的什么功法」去掉「什么」会留下「萧炎用的功法」，
     * FTS 侧只能命中「萧炎」—— 这是可接受的降级，
     * 而如果去掉「什么」把整句变成「萧炎用功法」反而更难命中。
     */
    private static final String[] QUESTION_WORDS = {
            "是什么", "哪本书", "在哪里", "哪儿", "哪里", "第几章",
            "为什么", "怎么", "怎么样", "是谁", "是谁的", "哪个", "哪些",
            "多少", "什么时候", "有没有", "能否", "可以", "告诉",
    };

    /** 只在句尾/句首出现的问号：中英文都收，中文书里两种都有人打。 */
    private static final String[] PUNCTUATION = {"？", "?", "。", "，", "、", "！"};

    private AskKeyword() {
    }

    /**
     * 提取检索词。
     *
     * @return 检索词；提不出东西时返回空串（<b>不返回 null</b>），
     *         调用方可以直接 {@code isBlank()} 判断
     */
    public static String of(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String out = question.strip();
        for (String w : QUESTION_WORDS) {
            out = out.replace(w, "");
        }
        for (String p : PUNCTUATION) {
            out = out.replace(p, " ");
        }
        // 多余空白压成一个：去掉疑问词后常留下两段连着的空白。
        // 🔴 字符类里必须显式列出全角空格 U+3000 —— Java 的 \s **不含**它
        //（只含ASCII 空白），而中文输入法下打出的空格就是全角的，
        // 漏掉它的现象是检索词里藏着一个"看起来什么都没有"的字符，
        // FTS 那边会静默召不回任何东西。
        out = out.replaceAll("[\\s\\u3000]+", " ").strip();
        return out;
    }
}