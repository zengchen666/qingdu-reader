package com.qingdu.reader.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写 —— 只覆盖本项目跨进程要用的那部分。
 *
 * <p><b>为什么不用 Jackson / Gson？</b>
 * <ol>
 *   <li><b>体积</b>：Jackson core + databind 约 3.4 MB。绿色版现在 107.8 MB，
 *       为了传几个字段把它加进来，收益与代价不成比例。</li>
 *   <li><b>jpackage 失败面</b>：多一个 jar 就多一处可能需要额外
 *       {@code --add-opens} 或 native 解压的地方。本项目已经踩过
 *       sqlite-jdbc 的原生库解压问题，不想再引入一类。</li>
 *   <li><b>结构固定</b>：跨进程契约只有 4 个模型（{@link Chunk} / Ask 请求 /
 *       Ask 响应 / 引用），字段十几个，且<b>由本项目自己定义</b>。
 *       通用 JSON 库的主要价值（映射任意 POJO、容错解析未知结构）用不上。</li>
 * </ol>
 *
 * <p><b>代价（必须诚实说）</b>：手写解析器有出 bug 的可能。
 * 缓解办法是把它写成<b>纯函数 + 可测</b>：{@link #parseObject} 不碰网络与状态，
 * 单测直接喂字符串断言结果。真正危险的"解析失败导致崩溃"用
 * {@code try/catch → 返回 null → 上层给"服务返回异常"的提示}兜住，
 * 宁可提示用户重试，不要抛栈给用户看。
 *
 * <p><b>不做什么</b>：不支持流式解析、不支持自定义序列化、不支持数字精度控制
 * （本项目只有整数和小数文本）。缺什么将来再说，别提前造。
 */
public final class Json {

    private Json() {
    }

    // ==================== 写 ====================

    /**
     * 序列化 Map / List / String / Number / Boolean / null。
     *
     * <p>键按插入顺序输出（用 {@link LinkedHashMap}），
     * 这样抓包排查时字段顺序稳定，便于人肉 diff。
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (var item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            // 兜底：未知类型按字符串写，宁可多引号也不要产出非法 JSON
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                // 换页/退格/制表：JSON 规范要求转义，实际不转义多数解析器也能吃，
                // 但转了更稳，且不会破坏格式
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ==================== 读 ====================

    /**
     * 解析一个 JSON 对象。
     *
     * @return 键 → 值（值可能是 String / Double / Boolean / List / Map / null）
     * @throws IllegalArgumentException 语法错误
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = new Parser(text).parseValue();
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("期望 JSON 对象，实际是 " + typeName(v));
        }
        return (Map<String, Object>) v;
    }

    /** 取字符串字段；缺失或类型不对时返回 {@code fallback}。 */
    public static String str(Map<String, Object> obj, String key, String fallback) {
        Object v = obj.get(key);
        return v instanceof String s ? s : fallback;
    }

    /** 取整数字段。JSON 里的数字一律先解析成 Double，这里做转换与范围检查。 */
    public static int integer(Map<String, Object> obj, String key, int fallback) {
        Object v = obj.get(key);
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite() || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                return fallback;
            }
            return (int) (double) d;
        }
        return fallback;
    }

    /** 取对象字段；类型不对时返回空 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> childObject(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** 取数组字段；类型不对时返回空 List。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> childArray(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    // ==================== 内部解析器 ====================

    /** 递归下降解析器。不用正则：JSON 的嵌套结构用正则表达会立刻失控。 */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        Object parseValue() {
            skipWhitespace();
            if (i >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNum();
            };
        }

        private Map<String, Object> parseObj() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("期望字段名，位置 " + i);
                }
                String key = parseStr();
                skipWhitespace();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("期望 ':'，位置 " + i);
                }
                i++;
                map.put(key, parseValue());
                skipWhitespace();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("对象未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return map;
                } else {
                    throw new IllegalArgumentException("期望 ',' 或 '}'，位置 " + i);
                }
            }
        }

        private List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("数组未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return list;
                } else {
                    throw new IllegalArgumentException("期望 ',' 或 ']'，位置 " + i);
                }
            }
        }

        private String parseStr() {
            StringBuilder sb = new StringBuilder();
            i++; // 开头的 "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("\\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("未知转义 \\" + esc);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNum() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                throw new IllegalArgumentException("不是合法值，位置 " + i);
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("数字解析失败: " + s.substring(start, i));
            }
        }

        private Object parseLiteral(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("期望 " + word + "，位置 " + i);
            }
            i += word.length();
            return value;
        }

        private void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }

    private static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "对象";
        }
        if (v instanceof List) {
            return "数组";
        }
        if (v instanceof String) {
            return "字符串";
        }
        return v.getClass().getSimpleName();
    }
}
