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
 * 单书搜索耗时拆解：把「SQL 候选」与「后过滤」分开计时。
 *
 * 目的：查明 57 个候选和 1448 个候选的耗时几乎一样（388 vs 486 ms），
 * 说明存在一个与候选数无关的固定开销。
 */
public final class SearchTimingProbe {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        TxtBookParser parser = new TxtBookParser();
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) { s.filter(p -> p.toString().endsWith(".txt")).forEach(files::add); }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        Path dbFile = dir.resolve("timing-probe.db");
        Files.deleteIfExists(dbFile);
        Database db = Database.open(dbFile);
        BookStore books = new BookStore(db);
        SearchStore search = new SearchStore(db);
        StringBuilder rep = new StringBuilder();

        for (Path file : files) {
            Book book = parser.parseMetadata(file);
            List<Chapter> chapters = parser.parseChapters(file, book.id());
            ChapterTextBatch texts = ChapterTextBatch.load(file, chapters);
            List<SearchDocument> docs = new ArrayList<>(chapters.size());
            for (int i = 0; i < chapters.size(); i++) docs.add(new SearchDocument(i, texts.textOf(i)));
            books.save(book, chapters.size(),
                    new ReadingProgress(book.id(), 0, null, 0, System.currentTimeMillis()));
            search.index(book.id(), SearchStore.fingerprint(file), docs, null);

            rep.append(String.format("%n### %s（%d 章）%n", book.title(), chapters.size()));
            rep.append("| 词 | 候选 | 默认上限总耗时 | 其中SQL | 其中后过滤 | 只跑SQL×3 | 只跑后过滤×3 |").append('\n');
            rep.append("|---|---|---|---|---|---|---|\n");

            for (String word : wordsFor(book.title())) {
                String match = CjkTokenizer.tokenizeQuery(word).trim();

                // 1) 默认上限跑一次，拿到产品路径的总耗时
                SearchResult capped = search.search(book.id(), word, 0, texts::textOf);

                // 2) 只跑 SQL 候选（反复 3 次取最小值，排除 JIT 与磁盘抖动）
                long sqlOnly = Long.MAX_VALUE;
                int candCount = 0;
                for (int i = 0; i < 3; i++) {
                    long t0 = System.nanoTime();
                    candCount = countCandidates(db, book.id(), match);
                    sqlOnly = Math.min(sqlOnly, (System.nanoTime() - t0) / 1_000_000L);
                }

                // 3) 只跑后过滤（拿 SQL 的结果，自己校验前 300 个）
                long filterOnly = Long.MAX_VALUE;
                for (int i = 0; i < 3; i++) {
                    long t0 = System.nanoTime();
                    verify(texts, candidateList(db, book.id(), match), word, 300);
                    filterOnly = Math.min(filterOnly, (System.nanoTime() - t0) / 1_000_000L);
                }

                rep.append(String.format("| %s | %d | %d ms | %d ms | %d ms | %d ms | %d ms |%n",
                        word, candCount, capped.elapsedMs(), sqlOnly,
                        capped.elapsedMs() - sqlOnly, filterOnly, filterOnly));
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.print(rep);
        }
        System.out.println("written " + out);
    }

    private static int countCandidates(Database db, String bookId, String match) {
        return candidateList(db, bookId, match).size();
    }

    private static List<Integer> candidateList(Database db, String bookId, String match) {
        List<Integer> result = new ArrayList<>();
        String sql = "SELECT chapter_index FROM search_index WHERE search_index MATCH ? AND book_id = ? ORDER BY chapter_index";
        try (var conn = db.connection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, bookId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getInt(1));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private static int verify(ChapterTextBatch texts, List<Integer> cands, String needle, int limit) {
        int n = 0;
        for (int idx : cands) {
            if (n >= limit) break;
            String raw = texts.textOf(idx);
            if (raw == null || raw.isEmpty()) continue;
            n++;
            String lower = raw.toLowerCase(Locale.ROOT);
            int first = lower.indexOf(needle.toLowerCase(Locale.ROOT));
            if (first < 0) continue;
            int count = 0, from = 0;
            String h = needle.toLowerCase(Locale.ROOT);
            while ((from = lower.indexOf(h, from)) >= 0) { count++; from += h.length(); }
            String snippet = first >= 0 ? raw.substring(Math.max(0, first - 20), Math.min(raw.length(), first + needle.length() + 20)) : "";
        }
        return n;
    }

    private static String[] wordsFor(String title) {
        return switch (title) {
            case "元尊" -> new String[]{"周元", "源气", "气运", "天源界"};
            case "全职高手" -> new String[]{"叶修", "荣耀", "兴欣", "剑客"};
            case "斗破苍穹" -> new String[]{"斗之气", "药老", "萧炎", "异火", "云岚宗", "炼药师", "纳戒"};
            default -> new String[]{"林动", "元力", "石符", "青阳镇"};
        };
    }
}
