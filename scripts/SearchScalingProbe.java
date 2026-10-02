import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.Chapter;
import com.qingdu.common.util.CjkTokenizer;
import com.qingdu.core.parser.txt.ChapterTextBatch;
import com.qingdu.core.parser.txt.TxtBookParser;
import com.qingdu.store.*;
import com.qingdu.store.model.ReadingProgress;
import com.qingdu.store.model.SearchDocument;
import com.qingdu.store.model.SearchResult;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 定位「SQL 候选查询 100~215 ms、与候选数无关」的成因。
 *
 * <p>假设：{@code detail='none'} 的 FTS5 没有可用倒排索引，MATCH 只能全表扫；
 * 代价正比于<b>索引库总行数</b>，与命中多少无关。
 *
 * <p>验证方法：让索引库里分别只有 1 / 2 / 4 本书，测同一个词的 SQL 耗时。
 * 若耗时随总行数线性增长 → 假设成立。
 */
public final class SearchScalingProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        TxtBookParser parser = new TxtBookParser();

        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) { s.filter(p -> p.toString().endsWith(".txt")).forEach(files::add); }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        StringBuilder rep = new StringBuilder();
        rep.append("# 单书 SQL 候选查询耗时 vs 索引库规模\n\n");
        rep.append("假设：detail='none' 的 FTS5 只能全表扫，耗时正比于索引库总行数，与命中数无关。\n\n");

        // 逐本累加建索引，每加一本测一次
        List<Book> built = new ArrayList<>();
        List<List<Chapter>> chapterLists = new ArrayList<>();
        int rows = 0;

        for (int upto = 1; upto <= files.size(); upto++) {
            Path file = files.get(upto - 1);
            Book book = parser.parseMetadata(file);
            List<Chapter> chapters = parser.parseChapters(file, book.id());
            ChapterTextBatch texts = ChapterTextBatch.load(file, chapters);
            List<SearchDocument> docs = new ArrayList<>(chapters.size());
            for (int i = 0; i < chapters.size(); i++) docs.add(new SearchDocument(i, texts.textOf(i)));

            Path dbFile = dir.resolve("scaling-probe.db");
            if (upto == 1) Files.deleteIfExists(dbFile);
            Database db = Database.open(dbFile);
            BookStore books = new BookStore(db);
            SearchStore search = new SearchStore(db);
            books.save(book, chapters.size(),
                    new ReadingProgress(book.id(), 0, null, 0, System.currentTimeMillis()));
            search.index(book.id(), SearchStore.fingerprint(file), docs, null);
            rows += chapters.size();

            long dbBytes = Files.size(dbFile);
            rep.append(String.format("%n## 索引库里有 %d 本（累计 %,d 章，%.1f MB）%n",
                    upto, rows, dbBytes / 1024.0 / 1024.0));
            rep.append("| 词 | 候选 | SQL 耗时 (ms) | 默认上限总耗时 (ms) |").append('\n');
            rep.append("|---|---|---|---|").append('\n');

            // 用固定几个词，避免各书命中数差异干扰
            for (String word : new String[]{"云岚宗", "药老", "元力", "石符", "青阳镇"}) {
                String match = CjkTokenizer.tokenizeQuery(word).trim();
                long best = Long.MAX_VALUE;
                int cand = 0;
                for (int i = 0; i < 5; i++) {
                    long t0 = System.nanoTime();
                    cand = sqlCount(db, book.id(), match);
                    best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
                }
                SearchResult capped = search.search(book.id(), word, 0, texts::textOf);
                rep.append(String.format("| %s | %d | %d | %d |%n", word, cand, best, capped.elapsedMs()));
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.print(rep);
        }
        System.out.println("written " + out);
    }

    private static int sqlCount(Database db, String bookId, String match) {
        String sql = "SELECT chapter_index FROM search_index WHERE search_index MATCH ? AND book_id = ? ORDER BY chapter_index";
        int n = 0;
        try (var conn = db.connection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, bookId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) n++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return n;
    }
}
