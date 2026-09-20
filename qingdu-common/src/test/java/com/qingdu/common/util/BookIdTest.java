package com.qingdu.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BookId} 的测试。 */
class BookIdTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同一个文件每次算出来的 ID 都一样 —— 这是存进度的前提")
    void stableForSameFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.txt"), "内容");

        assertEquals(BookId.of(file), BookId.of(file));
        // 换一种写法指向同一个文件（带冗余的 . 和 ..），结果也应该一样
        Path roundabout = tempDir.resolve(".").resolve("sub").resolve("..").resolve("a.txt");
        assertEquals(BookId.of(file), BookId.of(roundabout));
    }

    @Test
    @DisplayName("不同文件的 ID 不同")
    void differentForDifferentFiles() throws IOException {
        Path a = Files.writeString(tempDir.resolve("a.txt"), "内容");
        Path b = Files.writeString(tempDir.resolve("b.txt"), "内容");

        assertNotEquals(BookId.of(a), BookId.of(b));
    }

    @Test
    @DisplayName("改名之后 ID 会变 —— 这是路径方案的已知代价")
    void changesWhenPathChanges() throws IOException {
        Path file = Files.writeString(tempDir.resolve("old.txt"), "同样的内容");

        String before = BookId.of(file);
        Path moved = Files.move(file, tempDir.resolve("new.txt"));
        String after = BookId.of(moved);

        // 把这一点写成测试，是为了让"路径变了进度会丢"这个行为
        // 成为一条有据可查的约定，而不是某天被人当成 bug 才发现
        assertNotEquals(before, after);
    }

    @Test
    @DisplayName("内容是 16 个十六进制字符，定长、可安全当主键")
    void idIsFixedLengthHex() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a.txt"), "x");

        String id = BookId.of(file);

        assertEquals(16, id.length());
        assertTrue(id.matches("[0-9a-f]{16}"), "实际值：" + id);
    }

    @Test
    @DisplayName("文件不存在时也能算出一个稳定的 ID")
    void worksForMissingFile() {
        Path missing = tempDir.resolve("not-there.txt");

        // 数据库里可能残留着一条记录指向已经被删掉的文件，
        // 这时仍然要能算出 ID 才能把它找出来删掉
        String first = BookId.of(missing);
        assertEquals(first, BookId.of(missing));
        assertEquals(16, first.length());
    }

    @Test
    @DisplayName("窗口大小写不同的路径指向同一个文件时，ID 相同")
    void caseInsensitiveOnWindows() throws IOException {
        Path file = Files.writeString(tempDir.resolve("MixedCase.txt"), "x");

        // toRealPath() 会返回系统眼中的真实大小写，
        // 所以用户从不同地方点进来的同一个文件不会被当成两本书
        String upper = BookId.of(tempDir.resolve("MIXEDCASE.TXT"));
        String lower = BookId.of(tempDir.resolve("mixedcase.txt"));

        // 只有文件真的存在、且系统确实是大小写不敏感时才相等 ——
        // 在大小写敏感的文件系统（Linux）上这两个路径本就是两个文件
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertEquals(BookId.of(file), upper);
            assertEquals(upper, lower);
        }
    }

    @Test
    @DisplayName("传 null 直接报错，而不是悄悄生成一个随机 ID")
    void nullPathRejected() {
        assertThrows(IllegalArgumentException.class, () -> BookId.of(null));
    }

    @Test
    @DisplayName("canonical 对非法路径不抛异常，退回到绝对路径")
    void canonicalFallsBack() {
        Path weird = Path.of("不存在的目录", "也不存在的文件.txt");

        // 文件不存在时 toRealPath() 会抛 IOException，
        // 这里验证它被接住并退化成绝对路径，而不是让调用方崩掉
        String canonical = BookId.canonical(weird);

        assertTrue(canonical.endsWith("也不存在的文件.txt"));
    }
}
