package com.qingdu.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Chapter} 的单元测试。
 *
 * <p>重点覆盖 {@code startsNewVolume}：目录侧栏靠它在"本卷第一章"上方插卷头。
 * 这段判断原本写在界面代码里没法验证，抽到模型上之后就能这么直接测了。
 */
@DisplayName("章节模型")
class ChapterTest {

    private static Chapter chapter(String volumeTitle) {
        return Chapter.indexOnly("book-1", 0, "第一章 出山", volumeTitle, 0, 100);
    }

    @Nested
    @DisplayName("分卷分组判断")
    class VolumeGrouping {

        @Test
        @DisplayName("没有卷名的章节不开启新卷")
        void noVolume() {
            assertFalse(chapter(null).startsNewVolume(null));
            // 空白串在构造器里已经归一化成 null，行为要和"没传"完全一致
            assertFalse(chapter("   ").startsNewVolume(null));
        }

        @Test
        @DisplayName("全书的第一个章节带卷名时开启新卷")
        void firstChapterOfBook() {
            assertTrue(chapter("第一卷 启程").startsNewVolume(null));
        }

        @Test
        @DisplayName("卷名变了就开启新卷")
        void volumeChanged() {
            assertTrue(chapter("第二卷 风起").startsNewVolume(chapter("第一卷 启程")));
        }

        @Test
        @DisplayName("同一卷的后续章节不重复开启")
        void sameVolume() {
            assertFalse(chapter("第一卷 启程").startsNewVolume(chapter("第一卷 启程")));
        }

        @Test
        @DisplayName("从有卷回到无卷，同样不开启新卷")
        void backToNoVolume() {
            assertFalse(chapter(null).startsNewVolume(chapter("第一卷 启程")));
        }
    }

    @Test
    @DisplayName("卷名归一化：空白串存成 null，带空格的卷名去首尾空白")
    void volumeTitleNormalized() {
        Chapter blank = new Chapter("book-1", 0, "第一章", "   ", 0, 1, List.of());
        assertNull(blank.volumeTitle(), "空白卷名应当归一化成 null");
        assertFalse(blank.hasVolume());

        Chapter padded = new Chapter("book-1", 0, "第一章", "  第一卷 启程 ", 0, 1, List.of());
        assertEquals("第一卷 启程", padded.volumeTitle());
        assertTrue(padded.hasVolume());
    }
}
