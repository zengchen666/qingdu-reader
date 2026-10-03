package com.qingdu.reader.ai;

import com.qingdu.reader.ai.AiModels.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 侧的核心逻辑测试 —— 全部不碰界面，所以不需要图形环境。
 *
 * <p>与 JavaFX 无关的部分包括：JSON 读写、跨进程契约解析、片段切分、错误映射。
 * 这几处是"错了会静默出问题"的地方（解析失败变成空数据、
 * 字段名拼错被 Python 丢弃、段落号错位导致跳转跑偏），必须有测试钉住。
 */
class AiSideTest {

    // ==================== JSON ====================

    @Nested
    @DisplayName("JSON 读写")
    class JsonTest {

        @Test
        void 往返_基本类型() {
            String json = Json.write(Map.of("a", 1, "b", "x", "c", true));
            Map<String, Object> back = Json.parseObject(json);
            assertEquals(1.0, back.get("a"));
            assertEquals("x", back.get("b"));
            assertEquals(Boolean.TRUE, back.get("c"));
        }

        @Test
        void 转义_换行与引号与反斜杠() {
            // 真书正文里这三样都出现过（对话、引号换行）
            String text = "他说\"来\n了\"\\走";
            String json = Json.write(Map.of("t", text));
            assertEquals(text, Json.str(Json.parseObject(json), "t", ""));
        }

        @Test
        void 转义_控制字符() {
            // ⚠️ 不能写 "a\u0001b"：Java 会在读源码阶段就把 \u0001 当 Unicode 转义
            // 预处理，编译直接失败。用 char 拼接绕开。
            String ctrl = String.valueOf((char) 1);
            String json = Json.write(Map.of("t", "a" + ctrl + "b"));
            assertEquals("a" + ctrl + "b", Json.str(Json.parseObject(json), "t", ""));
        }

        @Test
        void 解析_中文保持原样不被转义() {
            // Python 侧能读，但保持可读性对抓包排查友好
            String json = Json.write(Map.of("title", "武动乾坤"));
            assertTrue(json.contains("武动乾坤"));
        }

        @Test
        void 解析_嵌套结构() {
            Map<String, Object> root = Json.parseObject(
                    "{\"coverage\":{\"hits\":3},\"citations\":[{\"index\":1},{\"index\":2}]}");
            assertEquals(3.0, Json.integer(Json.childObject(root, "coverage"), "hits", -1));
            assertEquals(2, Json.childArray(root, "citations").size());
        }

        @Test
        void 解析_空对象与空数组() {
            assertTrue(Json.parseObject("{}").isEmpty());
            assertTrue(Json.childArray(Json.parseObject("{\"a\":[]}"), "a").isEmpty());
        }

        @Test
        void 解析_服务端的中文转义序列() {
            // 服务端若用「反斜杠 + u + 4 位十六进制」输出，也要能读。
            // ⚠️ 不能把这种转义直接写在 Java 字面量或注释里 ——
            // javac 会在读源码阶段就把它当 Unicode 转义预处理，编译直接失败。
            // 只能用字符串拼接让 Java 保留那个反斜杠。
            String json = "{\"t\":\"\\u" + "4E66\"}";
            assertEquals("书", Json.str(Json.parseObject(json), "t", ""));
        }

        @Test
        void 解析_非法输入抛异常() {
            // 宁可抛出来让上层转成"服务返回异常"，也不要静默返回空 Map
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{"));
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\"}"));
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1,2]"));
        }

        @Test
        void 取字段_类型不对时用兜底值() {
            Map<String, Object> o = Json.parseObject("{\"n\":\"不是数字\",\"s\":123}");
            assertEquals(-1, Json.integer(o, "n", -1));
            assertEquals("fallback", Json.str(o, "s", "fallback"));
            assertEquals("fallback", Json.str(o, "缺失", "fallback"));
        }
    }

    // ==================== 契约 ====================

    @Nested
    @DisplayName("跨进程契约")
    class ContractTest {

        @Test
        void 请求字段必须是_camelCase() {
            // Python 侧 Pydantic 用 alias_generator=to_camel；
            // 发 snake_case 会被**静默丢弃**（请求 200 但数据全丢）。
            // 这条测试是那个坑的 Java 侧护栏。
            String json = Json.write(new Chunk("b1", "武动乾坤", 929,
                    "第九百三十章", 2, "药老现身。").toJson());
            assertTrue(json.contains("\"bookId\""), json);
            assertTrue(json.contains("\"chapterIndex\""), json);
            assertTrue(json.contains("\"paragraphIndex\""), json);
            assertFalse(json.contains("book_id"), json);
        }

        @Test
        void 响应_正常作答() {
            String body = """
                    {"answer":"药老解了封印[1]。","citations":[
                      {"index":1,"bookId":"b1","bookTitle":"武动乾坤","chapterIndex":929,
                       "chapterTitle":"第九百三十章","paragraphIndex":2,
                       "ref":"《武动乾坤》第 930 章第 3 段","excerpt":"药老现身"}],
                     "coverage":{"searchedBooks":2,"indexedBooks":2,"hits":3},
                     "elapsedMs":1234,"reason":"ok"}""";
            var r = AiModels.parseAskResponse(body);
            assertTrue(r.answered());
            assertEquals(1, r.citations().size());
            assertEquals(929, r.citations().get(0).chapterIndex());
            assertEquals(2, r.citations().get(0).paragraphIndex());
            assertEquals(1234, r.elapsedMs());
            assertEquals(2, r.searchedBooks());
            assertEquals("ok", r.reason());
        }

        @Test
        void 响应_answer_为_null_表示答不出() {
            String body = """
                    {"answer":null,"citations":[],"coverage":{"searchedBooks":2,"hits":0},
                     "elapsedMs":8,"reason":"no-chunks"}""";
            var r = AiModels.parseAskResponse(body);
            assertNull(r.answer());
            assertFalse(r.answered());
            assertTrue(r.noChunks());
        }

        @Test
        void 响应_引用全部无效也是答不出() {
            var r = AiModels.parseAskResponse(
                    "{\"answer\":null,\"citations\":[],\"reason\":\"invalid-citations\"}");
            assertTrue(r.invalidCitations());
            assertFalse(r.answered());
        }

        @Test
        void 响应_缺_answer_字段也当答不出() {
            // 与"服务端明确说 null"区分：结构异常要能被发现
            var r = AiModels.parseAskResponse("{\"citations\":[]}");
            assertNull(r.answer());
        }

        @Test
        void 引用_显示章号是_1_起() {
            var r = AiModels.parseAskResponse("""
                    {"answer":"x[1]","citations":[{"index":1,"bookId":"b","chapterIndex":0,
                     "paragraphIndex":0,"ref":"","excerpt":""}],"reason":"ok"}""");
            assertEquals(1, r.citations().get(0).displayChapter());
        }

        @Test
        void 健康_三态解析() {
            // 🔴 version 字段这里用的是占位串，不是真实版本：
            // 解析器只读 llm 字段，写死成"0.4.0"会让人误以为在断言版本一致，
            // 于是每次升版都想来改这里 —— 而改它对测试没有任何意义。
            assertTrue(AiModels.parseHealth(
                    "{\"ok\":true,\"llm\":\"ready\",\"version\":\"x\"}").ready());
            var noKey = AiModels.parseHealth(
                    "{\"ok\":true,\"llm\":\"no-api-key\",\"version\":\"x\"}");
            assertFalse(noKey.ready());
            assertTrue(noKey.noApiKey());
            assertTrue(AiModels.parseHealth(
                    "{\"ok\":true,\"llm\":\"not-configured\"}").noApiKey());
        }
    }

    // ==================== 错误映射 ====================

    @Nested
    @DisplayName("HTTP 状态 → 失败分类")
    class FailureMappingTest {

        private final AiServiceClient client = new AiServiceClient();

        @Test
        void 服务没配_key_是_NOT_CONFIGURED() {
            var out = client.interpret(503, "{\"detail\":\"未配置模型 API\"}");
            assertEquals(AiServiceClient.Failure.NOT_CONFIGURED, out.failure());
        }

        @Test
        void 模型失败是_LLM_ERROR() {
            assertEquals(AiServiceClient.Failure.LLM_ERROR, client.interpret(502, "{}").failure());
        }

        @Test
        void 其他状态码是_BAD_RESPONSE() {
            assertEquals(AiServiceClient.Failure.BAD_RESPONSE, client.interpret(500, "{}").failure());
        }

        @Test
        void 响应解析不了也是_BAD_RESPONSE() {
            // 🔴 不能让它变成 500：用户看到"服务异常"会重试，
            // 而实际是协议不匹配（轻读与服务端版本不同），重试没用
            var out = client.interpret(200, "这不是 JSON");
            assertEquals(AiServiceClient.Failure.BAD_RESPONSE, out.failure());
        }

        @Test
        void 正常响应走_ok() {
            var out = client.interpret(200,
                    "{\"answer\":\"a[1]\",\"citations\":[],\"reason\":\"ok\"}");
            assertTrue(out.ok());
        }
    }

    // ==================== 片段切分 ====================

    @Nested
    @DisplayName("片段切分")
    class SplitterTest {

        @Test
        void 只取段落_标题与插图不算() {
            var book = TestFixtures.book("b1", "武动乾坤");
            var chapter = TestFixtures.chapter("b1", 929, "第九百三十章");
            var blocks = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Heading(1, "小标题"),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("第一段。"),
                    new com.qingdu.common.domain.ChapterBlock.Image("x.png"),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("第二段。"));
            var chunks = ChunkSplitter.splitChapter(book, chapter, blocks);
            assertEquals(2, chunks.size());
            assertEquals(0, chunks.get(0).paragraphIndex());
            assertEquals(1, chunks.get(1).paragraphIndex());
        }

        @Test
        void 空段不占编号() {
            // 用户读"第 3 段"不该莫名指向一个空行
            var book = TestFixtures.book("b1", "书");
            var chapter = TestFixtures.chapter("b1", 0, "第一章");
            var blocks = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("甲"),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("   "),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("乙"));
            var chunks = ChunkSplitter.splitChapter(book, chapter, blocks);
            assertEquals(2, chunks.size());
            assertEquals("乙", chunks.get(1).text());
            assertEquals(1, chunks.get(1).paragraphIndex());
        }

        @Test
        void 超长段被截断() {
            var book = TestFixtures.book("b1", "书");
            var chapter = TestFixtures.chapter("b1", 0, "第一章");
            var blocks = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("字".repeat(5000)));
            var chunks = ChunkSplitter.splitChapter(book, chapter, blocks);
            assertEquals(ChunkSplitter.MAX_CHARS + 1, chunks.get(0).text().length());
            assertTrue(chunks.get(0).text().endsWith("…"));
        }

        @Test
        void 空输入返回空列表而非_null() {
            var book = TestFixtures.book("b1", "书");
            assertTrue(ChunkSplitter.splitChapter(book, null, List.of()).isEmpty());
            assertTrue(ChunkSplitter.splitChapter(null, null, null).isEmpty());
            var empty = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            assertTrue(ChunkSplitter.collect(book, empty, 8).isEmpty());
        }

        @Test
        void 保持原始顺序_不自己排序() {
            // 排序是 Python 侧的责任；轻读再排一次就是两处真理。
            // 🔴 这里必须用 TreeMap：Map.of 是无序的，会让章节顺序随机变化，
            // 引用编号在两次运行间对不上。collect 的签名收 SortedMap 就是为此。
            var book = TestFixtures.book("b1", "书");
            var b0 = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("甲"));
            var b1 = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("乙"));
            var ordered = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            ordered.put(0, b0);
            ordered.put(1, b1);
            var out = ChunkSplitter.collect(book, ordered, 8);
            assertEquals(List.of(0, 1), out.stream().map(Chunk::chapterIndex).toList());
        }

        @Test
        void 收集_受上限保护() {
            var book = TestFixtures.book("b1", "书");
            var blocks = List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("甲"),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("乙"),
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("丙"));
            var one = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            one.put(0, blocks);
            var out = ChunkSplitter.collect(book, one, 2);
            assertEquals(2, out.size());
        }

        @Test
        void 章节标题由调用方提供() {
            // 🔴 引用列表显示的是「[1] 第十二章 xxx」，缺了章名就只剩编号，
            // 用户核对时还得自己数第几章 —— 所以标题必须真的带出去。
            // ChapterBlock 里没有章名（章名在 Chapter.title() 上），
            // 所以 collect 必须接受一个「章节序号 → 标题」的函数。
            var book = TestFixtures.book("b1", "书");
            var chapters = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            chapters.put(5, List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("正文")));
            var out = ChunkSplitter.collect(book, chapters, 8, i -> "第六章 归来");
            assertEquals("第六章 归来", out.get(0).chapterTitle());
            assertEquals(5, out.get(0).chapterIndex());
        }

        @Test
        void 章节标题为_null_或函数为_null_都不炸() {
            var book = TestFixtures.book("b1", "书");
            var chapters = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            chapters.put(0, List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("正文")));
            assertEquals("", ChunkSplitter.collect(book, chapters, 8, null).get(0).chapterTitle());
            assertEquals("", ChunkSplitter.collect(book, chapters, 8, i -> null).get(0).chapterTitle());
        }

        @Test
        void 章节序号与标题对得上_按序号取值而不是插入顺序() {
            // 传进来的 TreeMap 键是 0、2、9，函数按序号给标题。
            // 如果实现里错用了"第几个"的隐式计数，第 2 章就会挂上第 9 章的标题 ——
            // 而这在界面上的表现只是"引用的章名不对"，不会报错。
            var book = TestFixtures.book("b1", "书");
            var chapters = new java.util.TreeMap<Integer, List<com.qingdu.common.domain.ChapterBlock>>();
            chapters.put(0, List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("甲")));
            chapters.put(9, List.<com.qingdu.common.domain.ChapterBlock>of(
                    new com.qingdu.common.domain.ChapterBlock.Paragraph("乙")));
            var out = ChunkSplitter.collect(book, chapters, 8, i -> "第" + i + "章");
            assertEquals("第0章", out.get(0).chapterTitle());
            assertEquals("第9章", out.get(1).chapterTitle());
        }
    }

    // ==================== 启动提示 ====================

    @Nested
    @DisplayName("服务启动提示")
    class StartCommandTest {

        @Test
        void 启动命令必须用_venv_里的解释器而不是裸_python() {
            // 🔴 这条测试是为了钉住一个真实踩过的坑（2026-10-03）。
            // 依赖装在 qingdu-ai\.venv 里，而裸 `python` 指向系统解释器，
            // 于是照着提示敲会得到：
            //     ModuleNotFoundError: No module named 'fastapi'
            // 报错看着像"代码坏了"，实际是提示本身写错了。
            // 写成全路径之后，本机实测一次就起来。
            String cmd = AiServiceClient.START_COMMAND;
            assertTrue(cmd.contains(".venv"),
                    "启动命令必须指向 .venv 里的解释器，实际是：" + cmd);
            assertTrue(cmd.contains("python.exe"),
                    "启动命令必须用 venv 的 python.exe，实际是：" + cmd);
            assertFalse(cmd.trim().startsWith("python"),
                    "不能用裸 python（那是系统解释器），实际是：" + cmd);
            assertTrue(cmd.contains("-m qingdu_ai"),
                    "启动命令必须走 python -m qingdu_ai 入口，实际是：" + cmd);
        }

        @Test
        void 装依赖的提示包含建_venv_与_install_两步() {
            // 只给启动命令不够：venv 还没建的人照着敲仍然失败，
            // 所以要能一键看到"先装依赖"的那两条命令。
            String hint = AiServiceClient.INSTALL_HINT;
            assertTrue(hint.contains("python -m venv .venv"), "缺建 venv 这一步：" + hint);
            assertTrue(hint.contains("pip install -e"), "缺装依赖这一步：" + hint);
        }

        @Test
        void 服务未启动的提示必须说清服务不在程序里() {
            // 🔴 用户实机踩过（2026-10-03）：从绿色版启动、AI 面板提示
            // "请在 qingdu-ai 目录执行"，但解压后的目录里根本没有 qingdu-ai。
            // 于是那条命令指向一个不存在的地方，用户只会怀疑程序坏了。
            //
            // 这里钉住三件事：① 明确说"它是另一个程序"，
            // ② 把装依赖与启动两条命令都写全（顺序不能反），
            // ③ 说清为什么不自带 .venv（否则用户会去找"总开关"）。
            String hint = AiServiceClient.SERVICE_DOWN_HINT;

            assertTrue(hint.contains("另一个程序"),
                    "必须说明服务不在轻读里，实际是：" + hint);

            // 装依赖在前、启动在后：反了的话用户第一次照做就会失败
            int venvAt = hint.indexOf("python -m venv .venv");
            int installAt = hint.indexOf("pip install -e");
            int startAt = hint.indexOf(AiServiceClient.START_COMMAND);
            assertTrue(venvAt >= 0, "缺建 venv 这一步：" + hint);
            assertTrue(installAt > venvAt, "装依赖必须排在建 venv 之后：" + hint);
            assertTrue(startAt > installAt, "启动必须排在装依赖之后：" + hint);

            assertTrue(hint.contains(".venv"),
                    "必须解释为什么绿色版不带 .venv：" + hint);
        }

        @Test
        void 两处服务未启动的入口必须用同一份文案() {
            // 「探测失败」（还没问就探不到）和「调用失败」（问着问着断了）
            // 如果各写一段，用户看到的提示随入口不同而不同，
            // 会怀疑是两个不同的问题。
            //
            // 钉法：读 AiPanel 源码，确认两处分支都只引用常量、不自带文案。
            // 字符串匹配而不是反射 —— 反射拿不到"源码里写了什么"，
            // 而"是否内联了第二份文案"恰恰是这里要防的事。
            java.nio.file.Path src = java.nio.file.Path.of("src", "main", "java",
                    "com", "qingdu", "reader", "ui", "AiPanel.java");
            java.nio.file.Path abs = java.nio.file.Files.exists(src)
                    ? src
                    : java.nio.file.Path.of("qingdu-desktop", "src", "main", "java",
                            "com", "qingdu", "reader", "ui", "AiPanel.java");
            String text = assertDoesNotThrow(
                    () -> java.nio.file.Files.readString(abs, java.nio.charset.StandardCharsets.UTF_8),
                    "读不到 AiPanel.java，无法校验提示文案：" + abs);

            int refs = text.split("AiServiceClient\\.SERVICE_DOWN_HINT", -1).length - 1;
            assertEquals(2, refs,
                    "SERVICE_DOWN_HINT 应恰好被引用两次（探测失败 / 调用失败），实际 " + refs);

            int notConfiguredRefs =
                    text.split("AiServiceClient\\.NOT_CONFIGURED_HINT", -1).length - 1;
            assertEquals(2, notConfiguredRefs,
                    "NOT_CONFIGURED_HINT 应恰好被引用两次（探测失败 / 调用失败），实际 "
                            + notConfiguredRefs);

            // 反向检查：不能还留着旧的内联文案
            assertFalse(text.contains("请在 qingdu-ai 目录执行"),
                    "AiPanel 里不该再有内联的启动提示文案，两处都该用常量");
            assertFalse(text.contains("等环境变量后重启服务"),
                    "AiPanel 里不该再有旧的内联配置提示，两处都该用常量");
        }

        @Test
        void 未配模型的提示必须给出可照抄的完整三行() {
            // 🔴 用户实机踩过（2026-10-04）：「一直配不上 key」。
            // 截图里他在 cmd 中敲了 PowerShell 的 $env: 语法，三行全报错
            // （"文件名、目录名或卷标语法不正确"），而界面之前只说
            // "请设置 QINGDU_LLM_BASE_URL / QINGDU_LLM_API_KEY / QINGDU_LLM_MODEL"
            // —— 三个变量名，没值、没语法、没说写到哪，等于没说。
            //
            // 所以这里钉住：提示里必须有**完整的 KEY=VALUE 行**，
            // 让用户能直接复制粘贴。
            String hint = AiServiceClient.NOT_CONFIGURED_HINT;

            assertTrue(hint.contains("QINGDU_LLM_BASE_URL="),
                    "必须给出 .env 里可直接照抄的完整行，实际是：" + hint);
            assertTrue(hint.contains("QINGDU_LLM_API_KEY="),
                    "必须给出 .env 里可直接照抄的完整行，实际是：" + hint);
            assertTrue(hint.contains("QINGDU_LLM_MODEL="),
                    "必须给出 .env 里可直接照抄的完整行，实际是：" + hint);

            assertTrue(hint.contains(".env"),
                    "必须说清写到哪里（.env 文件），实际是：" + hint);
            assertTrue(hint.contains("重启"),
                    "必须提醒改完要重启服务，否则用户改完不见效只会更困惑：" + hint);

            // 不能给出那个踩坑的写法本身
            assertFalse(hint.contains("$env:"),
                    "不要给 PowerShell 语法 —— 用户可能正在 cmd 里，" + hint);
        }
    }
}
