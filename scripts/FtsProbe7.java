import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 探针 7：确定 v0.2.0 的 MATCH 串到底该怎么写。
 *
 * 待回答的两个问题（看文档答不上来，只能跑）：
 *   1. detail='none' 下，给每个 token 加双引号（"云岚" "岚宗"）还能不能用？
 *      加引号是为了防止 token 恰好是 and / or / not 时被当成运算符。
 *   2. 小写的 and 到底会不会被 FTS5 当成布尔运算符？
 *
 * 用法：javac -cp "dist\QingduReader\app\*" -d out scripts\FtsProbe7.java
 *       java -cp "out;dist\QingduReader\app\*" FtsProbe7
 */
public class FtsProbe7 {

    public static void main(String[] args) throws Exception {
        StringBuilder report = new StringBuilder();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE VIRTUAL TABLE ft USING fts5("
                        + "book_id UNINDEXED, chapter_index UNINDEXED, body, detail='none')");
            }
            insert(conn, "b1", 0, "他走向云岚宗的大门");
            insert(conn, "b1", 1, "云岚山中有个岚宗派");
            insert(conn, "b1", 2, "you and me");
            insert(conn, "b1", 3, "AND OR NOT");
            insert(conn, "b1", 4, "林动手中的石符发出微光");

            report.append("---- 问题 1：detail=none 下加不加引号 ----\n");
            probe(conn, report, "云岚 岚宗");
            probe(conn, report, "\"云岚\" \"岚宗\"");
            probe(conn, report, "\"云岚 岚宗\"");
            probe(conn, report, "云岚 AND 岚宗");

            report.append("\n---- 问题 2：小写 and 是不是运算符 ----\n");
            probe(conn, report, "and");
            probe(conn, report, "AND");
            probe(conn, report, "and me");
            probe(conn, report, "you and");
            probe(conn, report, "not");

            report.append("\n---- 问题 3：单个 token 加引号 ----\n");
            probe(conn, report, "石符");
            probe(conn, report, "\"石符\"");
            probe(conn, report, "\"林动\"");
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("probe7.txt"), report.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("written probe7.txt");
    }

    private static void insert(Connection conn, String bookId, int idx, String body) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ft(book_id, chapter_index, body) VALUES (?, ?, ?)")) {
            ps.setString(1, bookId);
            ps.setInt(2, idx);
            ps.setString(3, body);
            ps.executeUpdate();
        }
    }

    private static void probe(Connection conn, StringBuilder report, String match) {
        String sql = "SELECT chapter_index FROM ft WHERE ft MATCH ? AND book_id = ? ORDER BY chapter_index";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, match);
            ps.setString(2, "b1");
            StringBuilder hits = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hits.length() > 0) {
                        hits.append(", ");
                    }
                    hits.append(rs.getInt(1));
                }
            }
            report.append(String.format("  MATCH %-22s -> [%s]%n", match, hits));
        } catch (Exception e) {
            report.append(String.format("  MATCH %-22s -> 异常：%s%n", match, e.getMessage()));
        }
    }
}
