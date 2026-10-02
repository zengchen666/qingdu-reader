package com.qingdu.reader.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索词提取 —— 决定整条 AI 问答链路能否召回原文的那一步。
 *
 * <h2>先读这段，否则你会以为这些期望值写错了</h2>
 * 这里的期望值<b>不是"最干净的检索词"</b>。比如
 * {@code "叶修是怎么退役的"} 提出来是 {@code "叶修是退役的"}，
 * 不是 {@code "叶修"}。这是<b>有意为之</b>：
 * <ul>
 *   <li>逐个删疑问词会把句子切得支离破碎（{@code 哪本书} 删完剩
 *       {@code "药老是的人物"}），造出 {@code 是的} 这种原文没有的相邻对；</li>
 *   <li>而 FTS5 的 MATCH 是 <b>AND 语义</b> ——
 *       {@code SearchStore.search} 要求<b>每个</b> token 都出现，缺一个整条召不回。
 *       token 越少越安全。</li>
 * </ul>
 *
 * <p><b>真书实测（{@code out/kw-probe.md}，10 句 × 四本语料）：</b>
 * 去词后 token 从 6~8 降到 1~5；其中 1 例<b>只有去词才召得回</b>
 * （「药老是哪本书的人物」——整句里的 {@code 哪本} {@code 本书}
 * 在《斗破苍穹》全文不存在）；另有 1 例两边都召不回。
 * 结论：去词<b>不是必需但无害</b>，且 token 更少。
 *
 * <h2>为什么这类东西必须有测试</h2>
 * 提词提错时<b>程序不报错</b>，只是召不回章节，模型于是回答
 * 「原文里没有提到」。用户看到的是一句语气很确定的否认，
 * 比任何报错都更容易让人放弃这个功能。
 *
 * <p>语料来自真机验收（{@code docs/2026-10-03-v0.4-live-acceptance.md}）用过的问句 ——
 * 用同一批句子才能防止"改一版就退回去"。
 */
class AskKeywordTest {

    @Test
    @DisplayName("真机验收用过的问句：去疑问词后 token 明显变少")
    void realQuestionsFromAcceptance() {
        // 注意断言的是真实输出，不是"理想输出"。
        // 「叶修是怎么退役的」留下「是」字是刻意的，见类注释。
        assertEquals("叶修是退役的", AskKeyword.of("叶修是怎么退役的"));
        assertEquals("药老来历", AskKeyword.of("药老是什么来历"));
        assertEquals("药老是的人物", AskKeyword.of("药老是哪本书的人物"));
        assertEquals("云岚宗", AskKeyword.of("云岚宗在哪里"));
        assertEquals("林动能修炼", AskKeyword.of("林动为什么能修炼"));
        assertEquals("叶修", AskKeyword.of("叶修是谁"));
    }

    @Test
    @DisplayName("token 数必须真的下降，否则这层提取就没意义")
    void tokenCountDrops() {
        // 这是本方法存在的<b>唯一理由</b>：FTS 是 AND 语义，
        // token 多一个就多一个"原文可能没有"的相邻对。
        assertTrue(AskKeyword.of("叶修是怎么退役的").length()
                < "叶修是怎么退役的".length());
        assertTrue(AskKeyword.of("萧炎在哪里拜师").length()
                < "萧炎在哪里拜师".length());
    }

    @ParameterizedTest
    @DisplayName("中英文问号与句末标点都被清掉，且不留残余空白")
    @ValueSource(strings = {"叶修？", "叶修?", "叶修。", "叶修！", "  叶修  "})
    void punctuationAndSurroundingSpaceAreRemoved(String input) {
        assertEquals("叶修", AskKeyword.of(input));
    }

    @ParameterizedTest
    @DisplayName("提不出检索词时返回空串而不是 null")
    @ValueSource(strings = {"？", "?", "   ", "是什么", "在哪里"})
    void blankWhenNothingLeft(String input) {
        assertEquals("", AskKeyword.of(input));
    }

    @Test
    @DisplayName("null 输入返回空串")
    void nullIsSafe() {
        assertEquals("", AskKeyword.of(null));
    }

    @Test
    @DisplayName("「谁」不单独删：会吃掉「谁知道」这种关键词")
    void keepsShuiIntact() {
        // 早期版本把「谁」单列进词表，「谁知道这件事」会被砍成「知道这件事」。
        // 现在只删「是谁」，所以整句保留 —— 「谁」在中文里更常是名字的一部分。
        assertEquals("谁知道这件事", AskKeyword.of("谁知道这件事"));
        assertEquals("叶修", AskKeyword.of("叶修是谁"));
    }

    @Test
    @DisplayName("「什么」刻意不删：少一个 token 不一定更安全")
    void keepsShenmeIntact() {
        // 「萧炎用的什么功法」有 7 个字符，删掉「什么」后是「萧炎用的功法」——
        // 少一个 token，但多造出「的功」这种通用组合。
        // 真书实测里两边都能召回到相关章，所以保持原句、不做二次加工。
        assertEquals("萧炎用的什么功法", AskKeyword.of("萧炎用的什么功法"));
    }

    @Test
    @DisplayName("去掉疑问词后不会留下双空格")
    void noDoubleSpaceAfterStripping() {
        // 「叶修  是谁」去掉「是谁」后是「叶修  」，必须压成「叶修」
        assertEquals("叶修", AskKeyword.of("叶修  是谁"));
        // 中间去词也不该凭空造出空格：去词前本来就没有空格
        assertEquals("药老来历", AskKeyword.of("药老是什么来历"));
    }

    @Test
    @DisplayName("全角空格也要清掉：中文输入法打出的就是它")
    void fullWidthSpaceIsRemoved() {
        // ⚠️ Java 的 \s 和 String.strip() 都**不处理** U+3000，
        // 而中文输入法下按空格键打出来的正是全角空格。
        // 不清掉的话检索词里会藏一个"看不见的空白"，
        // 送到 FTS 那边就是"一个 token 都没命中"，静默失败。
        //
        // 期望值里那个**中间的空格是故意保留的**：
        // 「药老␣是什么来历」去掉「是什么」后剩「药老␣来历」，
        // 全角空格被规范成普通空格 —— 保留分隔比把两个词粘在一起好，
        // 因为 CjkTokenizer 会把 ASCII 空白当作 token 边界。
        assertEquals("药老 来历", AskKeyword.of("药老" + '\u3000' + "是什么来历"));
        // 首尾的全角空格必须去掉（strip 不管它，靠 replaceAll 兜住）
        assertEquals("叶修", AskKeyword.of("\u3000叶修\u3000"));
        assertFalse(AskKeyword.of("\u3000叶修\u3000").contains("\u3000"));
    }

    @Test
    @DisplayName("结果一定没有前后空白（调用方直接拿去做 FTS 查询）")
    void resultIsStripped() {
        for (String q : new String[]{"  药老是什么来历  ", "药老是什么来历\n", "叶修是谁\t"}) {
            String kw = AskKeyword.of(q);
            assertEquals(kw.strip(), kw, "「" + q + "」提取出了带空白的检索词");
        }
    }
}