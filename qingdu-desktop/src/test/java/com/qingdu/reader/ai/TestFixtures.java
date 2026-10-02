package com.qingdu.reader.ai;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** AI 侧单测用的领域对象构造辅助。 */
final class TestFixtures {

    private TestFixtures() {
    }

    static Book book(String id, String title) {
        return new Book(id, title, "作者", BookFormat.TXT,
                Path.of("C:/books/x.txt"), null, 100, Instant.EPOCH);
    }

    static Chapter chapter(String bookId, int index, String title) {
        return new Chapter(bookId, index, title, null, -1L, -1L, List.of());
    }
}
