package com.qingdu.reader.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <h2>🔴 为什么不能把整句丢给 FTS（2026-10-03 真机踩到的大坑）</h2>
 * FTS5 的 MATCH 是 <b>AND 语义</b>，而 {@code CjkTokenizer} 把中文切成<b>bigram</b>，
 * 于是"N 个字的检索词"= 要求原文里同时出现 <b>N-1 个相邻二字对</b> ——
 * 实际上等于要求原文里有这么一整句话。
 *
 * <p>真机实测（四本真书，{@code out/diag5-result.txt}）：
 * <pre>
 *   问句                  检索词              FTS token                                        候选章
 *   叶修                叶修              叶修                                        1523  ✅
 *   叶修是谁             叶修              叶修                                        1523  ✅
 *   苏沐橙喜欢谁           苏沐橙喜欢谁         苏沐 沐橙 橙喜 喜欢 欢谁                            0  ❌
 *   这个主角最后去哪了        这个主角最后去哪了      这个 个主 主角 角最 最后 后去 去哪 哪了               0  ❌
 * </pre>
 * 「喜欢」「最后」这类字在正文里到处都是，但它们与前后字组成的
 * <b>相邻对</b>（如「橙喜」「角最」）几乎不存在 ——
 * 于是整个 AND 查询恒为空，<b>一个字都召不回</b>。
 *
 * <p><b>修法</b>：不再赌"整句刚好是个专有名词"，而是备好一条<b>候选词阶梯</b> ——
 * 先试整句（短问句时它就是最精确的查询），召不回再逐级降级到
 * 2~4 字的滑窗候选（{@link #terms}）。第一个能命中的就是答案所在。
 *
 * <p><b>修完的真机验证</b>（{@code out/diag6-result.txt}，9 问句 × 4 本真书）：
 * 修复前 4 个代表问句里 2 个 0 召回；修复后<b>所有"书里确实有那个人名"的问句
 * 全部召回成功</b>（25/25）。剩下 11 个 0 召回是<b>正确结果</b> ——
 * 比如在《武动乾坤》里问"叶修是谁"，这本书里本来就没有叶修。
 *
 * <h2>为什么候选词要过两道噪音过滤</h2>
 * 滑窗必然造出两类垃圾，而它们的危害<b>比 0 召回更大</b>：
 * 用户看到 0 召回会换个词再问，看到答非所问只会以为程序坏了。
 * <ol>
 *   <li><b>整词是常用词</b>（「这个」「最后」「喜欢」）→ {@link #GENERIC_TERMS} 挡；</li>
 *   <li><b>虚字开头的跨词组合</b>（「这个主」「个主」）→
 *       {@link #LEADING_FUNCTION_CHARS} 挡。这类黑名单挡不住，
 *       但它们<b>在原文里真的出现过</b>，FTS 会给命中。</li>
 * </ol>
 *
 * <h2>为什么候选词按"位置优先、同位置长度降序"排</h2>
 * 中文习惯把主语放句首：「<b>苏沐橙</b>喜欢谁」「<b>云岚宗</b>在哪里」。
 * 所以先按起始位置排，同一位置再取更长的窗口 ——
 * 「苏沐橙喜」（4 字）先试，「苏沐橙」（3 字）紧随其后。
 * 4 字窗口几乎必然落空，但只需一次 FTS 查询（毫秒级）就能排除它。
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

    /**
     * 一次问答最多试几个候选词。
     *
     * <p><b>为什么是 12 而不是全部试完</b>：每个候选词都要跑一次 FTS 查询
     * （真书基线 16~104 ms）并对命中章做原文后过滤（逐章随机读）。
     * 一个 20 字的长问句能生成 100+ 个滑窗，全试一遍界面要僵十几秒。
     * 12 是个折中：绝大多数问句在前 3 个候选内就有命中
     * （主语在句首），剩下的额度留给长问句。
     */
    public static final int MAX_TERMS = 12;

    /**
     * 候选词最短长度。
     *
     * <p><b>为什么是 2 而不是 1</b>：单字窗口几乎全是噪音
     * （"这""个""主""角"），命中率高得毫无意义；而且中文里
     * 两字人名/地名本来就最常见（叶修、药老、林动、萧炎），
     * 放宽到 1 只会让噪音淹没信号。
     */
    public static final int MIN_TERM_LEN = 2;

    /**
     * 候选词最长长度。
     *
     * <p>4 字够覆盖「斗破苍穹」这类书名，再长就基本是一句话了 ——
     * 而越长越召不回（见类注释的 AND 语义分析）。
     */
    public static final int MAX_TERM_LEN = 4;

    /**
     * 虚字 —— <b>不能出现在候选词首字</b>的汉字。
     *
     * <h2>为什么需要它（黑名单挡不住的那一类）</h2>
     * {@link #GENERIC_TERMS} 只能挡"整个词等于黑名单"的窗口，
     * 挡不住<b>跨词组合</b>。真书实测（{@code out/diag6-result.txt}）里
     * 「这个主角最后去哪了」问出的一串候选：
     * <pre>
     *   这个主角最后去哪了 → 这个主(命中) / 个主(命中) / 这个主角(命中) …
     * </pre>
     * 「这个主」「个主」在原文里<b>确实出现过</b>（FTS 给了 6 个候选章），
     * 所以黑名单放它们过去 → 召回 4 章无关内容 → 模型答非所问。
     * <b>比 0 召回更糟</b>：0 召回用户会换个词问，答非所问用户会以为程序坏了。
     *
     * <h2>为什么只看首字</h2>
     * 专有名词几乎不会以虚字开头（没有哪个人叫"这个主""很厉害"），
     * 而结尾是虚字的专有名词却真实存在——「叶修」不会，
     * 但「元尊」「萧炎」这类两字名里第二个字完全可能是常用字。
     * 只卡首字是"宁可漏挡、不误伤"的一侧。
     *
     * <h2>为什么用"首字"而不是"整词都不含虚字"</h2>
     * 后者会误伤「药老来历」这种正常候选（"来""历"都是常用字），
     * 也会误伤「炎用了」这种用户真写的词。虚字密集的<b>三字以上</b>窗口
     * 才是噪音，但那个判断留给 {@link #GENERIC_TERMS} 逐词处理更清楚。
     */
    private static final Set<Character> LEADING_FUNCTION_CHARS = Set.of(
            '这', '那', '个', '们', '的', '了', '着', '过', '是', '在', '和', '与',
            '就', '都', '也', '还', '又', '很', '更', '太', '最', '被', '把', '让',
            '给', '使', '对', '从', '到', '向', '为', '以', '于', '及', '或', '但',
            '却', '只', '才', '便', '再', '会', '能', '要', '说', '讲', '问', '答',
            '怎', '什', '哪', '如', '有', '无', '不', '没');

    /**
     * 泛用词黑名单 —— 命中它们的窗口直接跳过，不拿去检索。
     *
     * <p><b>为什么必须有</b>：滑窗必然会造出「这个」「最后」「喜欢」这种组合，
     * 它们在任何一本小说里都满地都是。不挡掉的话，
     * 第一个"有命中"的候选往往就是「这个」，召回一堆无关章节，
     * 模型据此回答"原文里没有提到" —— <b>比直接报错更难排查</b>。
     *
     * <p><b>挑选标准：几乎不可能出现在书名/人名/地名/门派名里。</b>
     * 反例（刻意<b>不</b>收录）：「后羿」「上官」「小貂」里的
     * 后、上、小 都不在清单里 —— 收下它们就会误伤真实人名。
     * 宁可漏挡几个噪音词，也不能误伤专有名词：
     * 误挡的代价只是少一个候选，误伤的代价是这个人名永远搜不到。
     */
    private static final Set<String> GENERIC_TERMS = buildGenericTerms();

    /**
     * 泛用词黑名单的构建方法。
     *
     * <p><b>为什么不在字段上直接写 {@code Set.of(...)}</b>：
     * {@code Set.of} 逐个重载只到 10 个元素，超过就走 varargs，
     * 而这份清单有 80 项 —— 写成一行超长调用时一旦多一个逗号或括号，
     * 报错信息是"非法的表达式开始"这种毫无线索的话
     *（2026-10-03 实测在这个方法上卡了三次）。
     * 放进方法体 + 逐行注释，编译器报错会直接指向具体那一行。
     */
    private static Set<String> buildGenericTerms() {
        List<String> terms = new ArrayList<>();
        // 指示代词与量词
        Collections.addAll(terms,
                "这个", "那个", "这些", "那些", "这样", "那样", "这里", "那里",
                "一个", "一些", "一点", "一下", "一样", "一般", "一切", "一起");
        // 疑问与判断
        Collections.addAll(terms,
                "什么", "怎么", "怎样", "如何", "为什么", "为何", "哪里", "哪儿",
                "哪个", "哪些", "是谁", "多少", "几个", "何时", "是否", "可否",
                "不是", "就是", "还是", "只是", "只有", "并不", "并非");
        // 时间副词
        Collections.addAll(terms,
                "最后", "后来", "开始", "当初", "原本", "现在", "目前", "如今",
                "以后", "之后", "之前", "以前", "从此", "同时", "然后", "接着");
        // 逻辑连接词
        Collections.addAll(terms,
                "于是", "所以", "因此", "因为", "如果", "虽然", "但是", "不过",
                "可是", "而且", "并且", "或者", "已经", "正在", "曾经", "一直");
        // 程度与数量副词
        Collections.addAll(terms,
                "非常", "十分", "特别", "比较", "有些", "所有", "每个", "整个");
        // 泛指名词与动词
        Collections.addAll(terms,
                "事情", "问题", "地方", "东西", "样子", "情况", "关系", "意思",
                "故事", "小说", "作者", "书名", "内容", "时候", "知道", "介绍",
                "说说", "讲讲", "问问", "帮我", "告诉", "回答", "喜欢",
                "发生", "出现", "看到", "认为", "觉得");
        return Set.copyOf(terms);
    }

    private AskKeyword() {
    }

    /**
     * 提取单个检索词。
     *
     * <p><b>⚠️ 现在还有谁在用这个方法</b>：{@link #terms} 会把它作为第一候选；
     * 单独调用它的地方只剩测试。它的局限必须清楚：
     * <b>返回值是"问题里最像检索词的那一整串"，不是"一定能召回的检索词"</b> ——
     * 拿它直接查询时，长句会因 AND 语义恒定召不回（见类注释）。
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

    /**
     * 提取<b>候选检索词阶梯</b> —— 调用方按顺序逐个试，召不回就降级到下一个。
     *
     * <p><b>为什么返回多个而不是一个</b>：FTS5 的 AND 语义决定了
     * "整句当检索词"在长句上必然落空（见类注释的真机数据）。
     * 与其在"整句"和"瞎猜的短词"之间二选一，不如把两者串成一条阶梯 ——
     * 短问句走第一级就命中，长问句靠后面的滑窗救回来。
     *
     * <p><b>为什么调用方要能提前停下</b>：每级都要花一次 FTS 查询
     * （16~104 ms）+ 原文后过滤（逐章随机读）。凑够章节就该停，
     * 否则一个 20 字问句会把界面拖住十几秒。
     *
     * @param question 用户原始问题
     * @return 候选词列表（已去重、已滤掉泛用词、已截断到 {@link #MAX_TERMS}）；
     *         提不出任何东西时返回空 List（<b>不返回 null</b>）
     */
    public static List<String> terms(String question) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String primary = of(question);
        if (!primary.isBlank()) {
            out.add(primary);
        }
        for (String candidate : windows(primary)) {
            if (out.size() >= MAX_TERMS) {
                break;
            }
            out.add(candidate);
        }
        return List.copyOf(out);
    }

    /**
     * 在去词后的文本上生成滑窗候选。
     *
     * <p><b>按段（而非整串）处理</b>：{@link #of} 会把标点换成空格，
     * 所以「叶修，苏沐橙」到这里已经是两段。跨段造词必然召不回
     * （原文里两个字中间隔着逗号），必须段内滑窗。
     */
    private static List<String> windows(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return List.of();
        }
        List<int[]> spans = new ArrayList<>();
        int cursor = 0;
        for (String run : cleaned.split("[\\s\\u3000]+", -1)) {
            int n = run.length();
            // 🔴 位置必须是【在 cleaned 里的绝对下标】，不能是段内偏移：
            // 后面要拿它去 cleaned 上 substring。用段内偏移切整串会切出
            // 跨段的假词（症状是候选词里出现原文没有的相邻对 → 恒定召不回）。
            int start = cleaned.indexOf(run, cursor);
            cursor = start + n;
            if (n < MIN_TERM_LEN) {
                continue;
            }
            for (int len = Math.min(MAX_TERM_LEN, n); len >= MIN_TERM_LEN; len--) {
                for (int i = 0; i + len <= n; i++) {
                    spans.add(new int[]{start + i, len});
                }
            }
        }
        // 位置优先 → 同位置长度降序。
        spans.sort((a, b) -> {
            if (a[0] != b[0]) {
                return Integer.compare(a[0], b[0]);
            }
            return Integer.compare(b[1], a[1]);
        });

        List<String> out = new ArrayList<>();
        for (int[] span : spans) {
            String candidate = cleaned.substring(span[0], span[0] + span[1]);
            if (isNoise(candidate)) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    /**
     * 判断一个候选词是不是噪音（虚字开头，或整个词在黑名单里）。
     *
     * <p>抽出来是为了让意图显式：<b>这两类噪音的成因不同</b> ——
     * 黑名单挡的是"完整的常用词"，虚字首字挡的是"跨词组合"，
     * 后者才是「这个主」这种真机实测出来的坑。
     */
    private static boolean isNoise(String candidate) {
        if (GENERIC_TERMS.contains(candidate)) {
            return true;
        }
        char first = candidate.charAt(0);
        return LEADING_FUNCTION_CHARS.contains(first);
    }
}
