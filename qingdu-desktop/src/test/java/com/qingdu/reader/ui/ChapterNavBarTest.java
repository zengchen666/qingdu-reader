package com.qingdu.reader.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 章末翻章条的边界计算。
 *
 * <p><b>为什么只测这两个静态方法？</b><br>
 * 因为整条导航条里唯一会"算错"的就是它 —— 按钮长什么样、标题怎么截断，
 * 肉眼一看就知道；而下标越界是那种在测试里一行就能钉死、
 * 在真机上却要"翻到第一章按一下上一章"才能发现的错。
 *
 * <p>尤其是 {@code index - 1}：它在第一章会算出 -1，
 * 如果调用方直接拿它去 {@code chapters.get()}，就是一次越界崩溃。
 * 所以这两个方法刻意返回 -1 表示"没有"，把判断权交给调用方（按钮置灰）。
 *
 * <p>类注释里说明了本类的构造需要 JavaFX 控件，因此这里只碰静态方法 ——
 * 测试不需要图形环境，CI 上也能跑。
 */
class ChapterNavBarTest {

    @Test
    @DisplayName("中间任一章：上一章和下一章都指向相邻那一章")
    void middleChapterHasBothNeighbours() {
        assertEquals(11, ChapterNavBar.previousIndex(12, 1474));
        assertEquals(13, ChapterNavBar.nextIndex(12, 1474));
    }

    @Test
    @DisplayName("第一章没有上一章（返回 -1，而不是回绕到 0 或末章）")
    void firstChapterHasNoPrevious() {
        assertEquals(-1, ChapterNavBar.previousIndex(0, 1474));
        // 回绕到最后一章是另一种"很聪明"的设计，但用户按"上一章"
        // 绝不会预期自己跳到全书末尾 —— 宁可置灰
        assertEquals(1, ChapterNavBar.nextIndex(0, 1474));
    }

    @Test
    @DisplayName("最后一章没有下一章")
    void lastChapterHasNoNext() {
        assertEquals(1472, ChapterNavBar.previousIndex(1473, 1474));
        assertEquals(-1, ChapterNavBar.nextIndex(1473, 1474));
    }

    @Test
    @DisplayName("只有一章时，两个方向都没有")
    void singleChapterHasNoNeighbours() {
        assertEquals(-1, ChapterNavBar.previousIndex(0, 1));
        assertEquals(-1, ChapterNavBar.nextIndex(0, 1));
    }

    @Test
    @DisplayName("异常输入一律当作「没有」，不让越界下标漏到调用方")
    void oddInputsAreTreatedAsUnavailable() {
        // 空书：chapters 为空时 showChapter 不会被调用，但万一调用到了，
        // 这两个方法必须是安全的 —— 返回 -1 而不是抛异常
        assertEquals(-1, ChapterNavBar.previousIndex(0, 0));
        assertEquals(-1, ChapterNavBar.nextIndex(0, 0));
        // 负数下标（理论上到不了这里，但边界函数就该自己兜住）
        assertEquals(-1, ChapterNavBar.previousIndex(-1, 10));
        assertEquals(-1, ChapterNavBar.nextIndex(-1, 10));
        // 下标等于甚至超过总章数（换书时 chapters 已换、下标还没刷新的那一瞬间）
        assertEquals(-1, ChapterNavBar.previousIndex(10, 10));
        assertEquals(-1, ChapterNavBar.nextIndex(10, 10));
        assertEquals(-1, ChapterNavBar.previousIndex(99, 10));
        assertEquals(-1, ChapterNavBar.nextIndex(99, 10));
    }

    @Test
    @DisplayName("标题截断：短的原样返回，长的补省略号，空的安全退化成空串")
    void titlePreviewIsTruncatedSafely() {
        assertEquals("第十三章 云起", ChapterNavBar.shorten("第十三章 云起", 14));

        // 「第十三章 云起时会无风自动」共 13 个字符，截到 10 就是前 10 个字符
        // 加上一个省略号 —— 断言用完整字面量，免得又在心里数字数
        assertEquals("第十三章 云起时会无…",
                ChapterNavBar.shorten("第十三章 云起时会无风自动", 10));

        assertEquals("", ChapterNavBar.shorten(null, 14));
        assertEquals("", ChapterNavBar.shorten("   ", 14));

        // 截断依据是"字符数"而不是"字节数"：中文一个字就是一个字符，
        // 所以 14 就是 14 个汉字，不用担心把某个多字节字符劈成两半
        String fifteen = "一二三四五六七八九十甲乙丙丁戊";
        assertEquals(15, fifteen.length());
        assertEquals(15, ChapterNavBar.shorten(fifteen, 14).length());
    }
}
