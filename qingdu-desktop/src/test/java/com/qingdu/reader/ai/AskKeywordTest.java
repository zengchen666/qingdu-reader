package com.qingdu.reader.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

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
 * <h2>🔴 但"少 token"并不够：真机上长问句依然恒定 0 召回</h2>
 * 上面那条"token 越少越安全"是 2026-10-03 之前的结论，只在
 * <b>去词后还剩专有名词</b>时成立。真机把四本书建完索引后实测发现：
 * <pre>
 *   问句               去词结果            FTS token                              候选章
 *   叶修             叶修              叶修                                1523  ✅
 *   苏沐橙喜欢谁        苏沐橙喜欢谁         苏沐 沐橙 橙喜 喜欢 欢谁                    0  ❌
 *   这个主角最后去哪了     这个主角最后去哪了      这个 个主 主角 角最 最后 后去 去哪 哪了       0  ❌
 * </pre>
 * 原因：<b>bigram 切分让"N 个字"变成了"N-1 个必须同时出现的相邻对"</b>，
 * 等价于要求原文里有这么一整句话。「橙喜」「角最」这种跨词相邻对在正文里
 * 几乎不存在，于是整个 AND 查询恒为空。
 *
 * <p>所以 {@link AskKeyword#terms} 改成<b>候选阶梯</b>：整句先试，
 * 召不回再逐级降到 2~4 字滑窗。下面的 {@code TermsTest} 钉住这套行为，
 * 而本类其余测试继续钉住 {@code of()} 本身的清洗规则（它现在是阶梯的第一级）。
 *
 * <h2>为什么这类东西必须有测试</h2>
 * 提词提错时<b>程序不报错</b>，只是召不回章节，模型于是回答
 * 「原文里没有提到」。用户看到的是一句语气很确定的否认，
 * 比任何报错都更容易让人放弃这个功能 —— v0.4 真机上正是如此。
 *
 * <p>语料来自真机验收（{@code docs/2026-10-03-v0.4-live-acceptance.md}）用过的问句，
 * 以及事后排查时的四本真书召回数据（{@code out/diag5-result.txt}）——
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

    // ==================== 候选词阶梯 ====================

    @Nested
    @DisplayName("候选词阶梯（terms）—— 修掉「长句恒定 0 召回」的那个坑")
    class TermsTest {

        /**
         * 🔴 这组数据来自<b>真机四本书的实测</b>（{@code out/diag5-result.txt}），
         * 不是构造的样本。修复前的行为是：{@code of} 抽出整句 →
         * {@code CjkTokenizer} 切成 N-1 个 bigram → FTS5 的 AND 语义要求
         * 它们全部同时出现 → 候选章 0。
         */
        @Test
        @DisplayName("真机踩过的问句：阶梯里必须出现能召回的短词")
        void realQuestionsProduceUsableTerms() {
            // 「苏沐橙喜欢谁」：修复前只有 ["苏沐橙喜欢谁"] 一个候选，恒定召不回。
            // 现在必须在阶梯里出现「苏沐橙」这个三字人名。
            assertTrue(AskKeyword.terms("苏沐橙喜欢谁").contains("苏沐橙"),
                    "实际=" + AskKeyword.terms("苏沐橙喜欢谁"));

            // 「这个主角最后去哪了」：没有专有名词，只能靠二字窗口救。
            var terms = AskKeyword.terms("这个主角最后去哪了");
            assertTrue(terms.contains("主角"),
                    "泛用词挡掉之后应剩「主角」，实际=" + terms);
        }

        @Test
        @DisplayName("阶梯第一个候选仍然是整句：短问句走第一级就命中")
        void primaryTermComesFirst() {
            // 不能一上来就丢滑窗 —— 「叶修」这种问句整句就是最精确的查询，
            // 拆成「叶修」虽然也对，但多一次无谓的降级尝试。
            assertEquals("叶修", AskKeyword.terms("叶修").get(0));
            assertEquals("叶修", AskKeyword.terms("叶修是谁").get(0));
        }

        @Test
        @DisplayName("泛用词被挡掉：否则第一个命中的候选是「这个」，召回一堆无关章")
        void genericTermsAreFiltered() {
            var terms = AskKeyword.terms("这个主角最后去哪了");
            assertFalse(terms.contains("这个"), "「这个」在任何小说里都满地是：" + terms);
            assertFalse(terms.contains("最后"), "「最后」同理：" + terms);
            assertFalse(terms.contains("喜欢"), "「喜欢」同理：" + terms);
        }

        @Test
        @DisplayName("🔴 虚字开头的跨词组合必须挡掉：它在原文里真的出现过")
        void leadingFunctionCharIsFiltered() {
            // 真机实测（out/diag6-result.txt）：「这个主角最后去哪了」问出
            // 「这个主」「个主」这类窗口，FTS 对它们**有命中**（各 6 个候选章），
            // 黑名单挡不住（词不等于"这个"），于是召回 4 章无关内容。
            // 这比 0 召回更糟：0 召回用户会换词，答非所问用户会以为程序坏了。
            var terms = AskKeyword.terms("这个主角最后去哪了");
            assertFalse(terms.contains("这个主"), "虚字开头的跨词组合：" + terms);
            assertFalse(terms.contains("个主"), "虚字开头的跨词组合：" + terms);
            // 但真正有信息的「主角」必须留着
            assertTrue(terms.contains("主角"), "实际=" + terms);
        }

        @Test
        @DisplayName("🚨 虚字首字规则不许误伤真实人名")
        void leadingCharRuleDoesNotEatProperNouns() {
            for (String name : new String[]{
                    "叶修", "林动", "萧炎", "药老", "云岚宗", "苏沐橙", "唐三",
                    "斗破苍穹", "武动乾坤", "元尊", "后羿", "上官婉儿", "小貂"}) {
                assertTrue(AskKeyword.terms(name).contains(name),
                        "人名/地名「" + name + "」被误伤了");
            }
        }

        @Test
        @DisplayName("🚨 黑名单不许误伤真实人名与地名")
        void blacklistDoesNotEatProperNouns() {
            // 这条是黑名单的<b>主要风险</b>：收得太宽会把人名挡掉，
            // 症状是"书里明明有这个人，AI 却说没提到"——
            // 比多留几个噪音词严重得多，因为噪音词只是浪费一次查询。
            for (String name : new String[]{
                    "后羿", "上官婉儿", "小貂", "云岚宗", "药老", "林动", "萧炎",
                    "叶修", "苏沐橙", "唐三", "斗破苍穹", "武动乾坤", "元尊"}) {
                var terms = AskKeyword.terms(name);
                assertTrue(terms.contains(name),
                        "人名/地名「" + name + "」必须原样出现在候选里，实际=" + terms);
            }
        }

        @Test
        @DisplayName("候选数有上限：不然长问句会把界面卡住十几秒")
        void termsAreCapped() {
            // 每个候选都要一次 FTS 查询（16~104 ms）+ 逐章随机读，
            // 所以必须截断。
            var terms = AskKeyword.terms("这个主角最后去了哪里他在全书里面究竟做了什么值得思考的事情呢");
            assertTrue(terms.size() <= AskKeyword.MAX_TERMS,
                    "候选数 " + terms.size() + " 超过上限 " + AskKeyword.MAX_TERMS);
        }

        @Test
        @DisplayName("同位置长度降序：先试更长的窗口，再用短窗口兜底")
        void longerWindowAtSamePositionComesFirst() {
            // 「苏沐橙喜欢谁」去掉疑问词后是「苏沐橙喜欢」，
            // 位置 0 处的窗口应按 4-3-2 字排：苏沐橙喜 → 苏沐橙 → 苏沐
            var terms = AskKeyword.terms("苏沐橙喜欢谁");
            int i4 = terms.indexOf("苏沐橙喜");
            int i3 = terms.indexOf("苏沐橙");
            int i2 = terms.indexOf("苏沐");
            assertTrue(i4 >= 0 && i3 >= 0 && i2 >= 0, "实际=" + terms);
            assertTrue(i4 < i3 && i3 < i2,
                    "位置 0 处应按长度降序，实际顺序=" + terms);
        }

        @Test
        @DisplayName("🚨 跨标点的窗口不能造出来（原文里两个字中间隔着逗号）")
        void windowsDoNotCrossPunctuation() {
            // 「叶修，苏沐橙」去标点后是「叶修 苏沐橙」两段。
            // 如果按整串滑窗，会造出「修苏」这种原文里不存在的相邻对，
            // 而它<b>看起来</b>是个正常的二字候选 —— 静默失效，最难查。
            var terms = AskKeyword.terms("叶修，苏沐橙");
            assertFalse(terms.contains("修苏"), "造出了跨段假词：" + terms);
            assertTrue(terms.contains("叶修"), "实际=" + terms);
            assertTrue(terms.contains("苏沐橙"), "实际=" + terms);
        }

        @Test
        @DisplayName("全角空格同样不能被跨过去")
        void windowsDoNotCrossFullWidthSpace() {
            // ⚠️ 中文输入法打出的就是全角空格，而 split 必须显式列出它
            var terms = AskKeyword.terms("叶修" + '\u3000' + "苏沐橙");
            assertFalse(terms.contains("修苏"), "跨全角空格造出了假词：" + terms);
        }

        @Test
        @DisplayName("提不出候选时返回空 List 而非 null")
        void emptyWhenNothingLeft() {
            assertTrue(AskKeyword.terms("？").isEmpty());
            assertTrue(AskKeyword.terms("   ").isEmpty());
            assertTrue(AskKeyword.terms(null).isEmpty());
            assertTrue(AskKeyword.terms("是什么").isEmpty());
        }

        @Test
        @DisplayName("单字不入候选：噪音远多于信号")
        void singleCharIsNotACandidate() {
            // 中文两字人名最常见（叶修、药老），放宽到 1 字只剩噪音
            for (String t : AskKeyword.terms("叶修是谁")) {
                assertTrue(t.length() >= AskKeyword.MIN_TERM_LEN,
                        "出现了单字候选：" + t);
            }
        }

        @Test
        @DisplayName("候选词没有重复：重复查询是白花钱")
        void noDuplicates() {
            var terms = AskKeyword.terms("叶修叶修叶修是谁");
            assertEquals(terms.size(), Set.copyOf(terms).size(),
                    "候选里有重复：" + terms);
        }

        @Test
        @DisplayName("纯 ASCII 问题仍能工作（章节号、人名拼音）")
        void asciiStillWorks() {
            var terms = AskKeyword.terms("JavaFX 100");
            assertFalse(terms.isEmpty(), "ASCII 问题不该产出空阶梯");
        }
    }
}