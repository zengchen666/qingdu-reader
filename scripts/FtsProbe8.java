import com.qingdu.common.util.CjkTokenizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 探针 8：在【插入前先做 bigram 切分】的前提下，对比 MATCH 串加不加引号。
 *
 * 探针 7 的中文查询全是 0 条 —— 那不是引号的问题，是插进去的是未切分的原文，
 * unicode61 把整串汉字当成一个 token（正是设计文档 1.1 节那个坑）。
 * 这里把分词器接上再测一遍，顺便把"引号到底要不要"这个问题彻底钉死。
 *
 * 用法：javac -encoding UTF-8 -cp "qingdu-common\target\classes;dist\QingduReader\app\*" -d out scripts\FtsProbe8.java
 *       java -cp "out;qingdu-common\target\classes;dist\QingduReader\app\*" FtsProbe8
 */
public class FtsProbe8 {

    public static void main(String[] args) throws Exception {
        StringBuilder report = new StringBuilder();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE VIRTUAL TABLE ft USING fts5("
                        + "book_id UNINDEXED, chapter_index UNINDEXED, body, detail='none')");
            }
            insert(conn, "b1", 0, "他走向云岚宗的大门");
            insert(conn, "b1", 1, "云岚山中有个岚宗派");
            insert(conn, "b1", 2, "林动手中的石符发出微光");
            insert(conn, "b2", 0, "另一本书里的云岚宗");

            report.append("---- 切分后：加不加引号结果是否一致 ----\n");
            probe(conn, report, "云岚 岚宗", "b1");
            probe(conn, report, "\"云岚\" \"岚宗\"", "b1");
            report.append("---- 两字词 ----\n");
            probe(conn, report, "石符", "b1");
            probe(conn, report, "林动", "b1");
            report.append("---- book_id 过滤是否生效（b2 也有云岚宗） ----\n");
            probe(conn, report, "云岚 岚宗", "b1");
            probe(conn, report, "云岚 岚宗", "b2");
            report.append("---- 候选 vs 精确（后过滤要剔掉第 1 章） ----\n");
            report.append("  候选（SQL）      = ").append(candidates(conn, "云岚 岚宗", "b1")).append('\n');
            report.append("  精确（原文校验）= ").append(exact(conn, "云岚宗", "b1")).append('\n');
        }
        Files.writeString(Path.of("probe8.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println("written probe8.txt");
    }

    private static void insert(Connection conn, String bookId, int idx, String raw) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ft(book_id, chapter_index, body) VALUES (?, ?, ?)")) {
            ps.setString(1, bookId);
            ps.setInt(2, idx);
            ps.setString(3, CjkTokenizer.tokenize(raw));
            ps.executeUpdate();
        }
    }

    private static void probe(Connection conn, StringBuilder report, String match, String bookId) {
        String sql = "SELECT chapter_index FROM ft WHERE ft MATCH ? AND book_id = ? ORDER BY chapter_index";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, bookId);
            StringBuilder hits = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hits.length() > 0) {
                        hits.append(", ");
                    }
                    hits.append(rs.getInt(1));
                }
            }
            report.append(String.format("  [%s] MATCH %-18s -> [%s]%n", bookId, match, hits));
        } catch (Exception e) {
            report.append(String.format("  [%s] MATCH %-18s -> 异常：%s%n", bookId, match, e.getMessage()));
        }
    }

    /** 后过滤前：SQL 给的候选章号。 */
    private static String candidates(Connection conn, String match, String bookId) throws Exception {
        return hitList(conn, "SELECT chapter_index FROM ft WHERE ft MATCH ? AND book_id = ? "
                + "ORDER BY chapter_index", match, bookId);
    }

    /** 后过滤后：拿原文再校验一次。这里直接用内存里的原文模拟"回读"。 */
    private static String exact(Connection ignored, String needle, String bookId) throws Exception {
        String[] raws = {"他走向云岚宗的大门", "云岚山中有个岚宗派", "林动手中的石符发出微光"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raws.length; i++) {
            if (raws[i].contains(needle)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(i);
            }
        }
        return "[" + sb + "]";
    }

    private static String hitList(Connection conn, String sql, String match, String bookId) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(rs.getInt(1));
                }
            }
        }
        return "[" + sb + "]";
    }
}
