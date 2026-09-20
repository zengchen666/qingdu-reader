package com.qingdu.core.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字符编码探测器 —— 解决"打开 TXT 全是乱码"这个中文阅读器的头号问题。
 *
 * <p><b>问题背景</b><br>
 * 同一段中文，用不同编码保存出来的字节完全不同：
 * <pre>
 *   "第一章"  UTF-8     → E7 AC AC E4 B8 80 E7 AB A0   （9 字节）
 *   "第一章"  GBK       → B5 DA D2 BB D5 C2             （6 字节）
 * </pre>
 * 用错编码去解码，拿到的就是"锟斤拷"或者"ç¬¬ä¸€ç« "。
 * 而网上下载的 TXT 小说，UTF-8 和 GBK 大约各占一半，用户自己根本不知道手上是哪种。
 *
 * <p><b>探测策略（按可靠性从高到低，逐级降级）</b>
 * <pre>
 *   第 1 级  BOM 检测          —— 文件头有 3~4 个特殊字节，100% 准确
 *   第 2 级  UTF-16 无 BOM 启发 —— 看 0x00 字节出现的"奇偶规律"
 *   第 3 级  严格 UTF-8 校验    —— 逐字节验证 UTF-8 语法，通过就是 UTF-8
 *   第 4 级  候选打分          —— GB18030 vs Big5，谁解码出的常用汉字多就用谁
 *   第 5 级  兜底              —— 实在判断不出来，用 GB18030（中文小说最常见）
 * </pre>
 *
 * <p><b>为什么"严格 UTF-8 校验"这么关键？</b><br>
 * 因为 UTF-8 有自己的语法规则：多字节序列的首字节范围、后续字节必须在
 * {@code 0x80-0xBF}。而 GBK 的后续字节范围是 {@code 0x40-0xFE}，
 * 落在 {@code 0x80-0xBF} 之外的比例很高。
 * 也就是说：<b>GBK 编码的中文几乎不可能通过 UTF-8 语法校验</b>（概率低到可以忽略）。
 * 这让"能不能通过校验"成为一个极强的判据 —— 而且它是<b>单向可靠</b>的：
 * 通过 → 基本可以确定是 UTF-8；不通过 → 再去区分 GBK / Big5。
 *
 * <p><b>已知取舍</b><br>
 * 无 BOM 且以中文为主的 UTF-16 文件（极其少见）无法靠前两级识别。
 * 我们在第 4 级之后加了一道"补救闸门"：只有当 GB18030 / Big5 解码结果
 * 明显是垃圾（出现大量替换字符）时，才回头尝试 UTF-16。
 * 这样做是为了避免一个更常见的误判 —— 纯英文为主的 GBK 文件被错认成 UTF-16。
 */
public final class CharsetDetector {

    /**
     * 采样上限（256KB）。
     *
     * <p><b>为什么不整个文件都读进来判断？</b><br>
     * 两个原因：
     * <ol>
     *   <li>一本小说可能上百 MB，为了判断编码多读一遍是浪费；</li>
     *   <li>更重要的是<b>一致性</b>：建立索引时要探测一次，之后每次读正文
     *       还会再探测一次。只有规定"两次都只看前 256KB"，
     *       才能保证结果完全一致 —— 否则可能出现"目录用了 GBK 建索引，
     *       正文却按 UTF-8 解码"的诡异 bug。</li>
     * </ol>
     * 256KB 相当于十几万字，中文编码统计特征早就稳定了，再采样也没有增益。
     */
    public static final int SAMPLE_LIMIT = 256 * 1024;

    public static final Charset GB18030 = Charset.forName("GB18030");

    /** Big5 属于 JDK 的"扩展字符集"，理论上可能不存在，所以做成可空。 */
    public static final Charset BIG5 = Charset.isSupported("Big5") ? Charset.forName("Big5") : null;

    /** 打分低于这个值说明候选编码解出来的基本都是垃圾。 */
    private static final double GARBAGE_SCORE = 0.0;

    /**
     * 常用简体汉字集合。
     *
     * <p>这份表是区分"GB18030 解对了"和"Big5 解错了"的关键：
     * 用错误编码解出来的汉字，往往会落到大量生僻字上（如"箝""犛""鱁"），
     * 这些字不在高频表里，得分立刻拉开差距。
     */
    private static final String COMMON_SIMPLIFIED =
            "的一是了我不人在他有这上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于"
                    + "心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它"
                    + "头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感";

    /** 常用繁体汉字集合，用于给 Big5 文件一个公平的得分机会。 */
    private static final String COMMON_TRADITIONAL =
            "的一是了我不人在他有這上們來到時大地為子中你說生國年著就那和要她出也得裡後自以會家可下而過天去能對小多然於"
                    + "心學麼之都好看起發當沒成只如事把還用第樣道想作種開美總從無情己面最女但現前些所同日手又行意動方期它"
                    + "頭經長兒回位分愛老因很給名法間斯知世什兩次使身者被高已親其進此話常與活正感";

    private static final Set<Character> COMMON_CHARS = new HashSet<>();

    static {
        for (int i = 0; i < COMMON_SIMPLIFIED.length(); i++) {
            COMMON_CHARS.add(COMMON_SIMPLIFIED.charAt(i));
        }
        for (int i = 0; i < COMMON_TRADITIONAL.length(); i++) {
            COMMON_CHARS.add(COMMON_TRADITIONAL.charAt(i));
        }
    }

    /** 判定依据的来源，用于界面展示和问题排查。 */
    public enum Source {
        /** 文件头有 BOM，最可靠 */
        BOM,
        /** 通过 0x00 字节的奇偶规律推断出的无 BOM UTF-16 */
        UTF16_HEURISTIC,
        /** 通过 UTF-8 语法校验 */
        STRICT_UTF8,
        /** 多个候选编码打分后胜出 */
        SCORING,
        /** 全部失败后的兜底 */
        FALLBACK
    }

    /**
     * 探测结果。
     *
     * @param charset 判定出的字符集
     * @param source  判定依据
     * @param reason  给人和日志看的解释（例如"UTF-8 校验通过""GB18030 得分 -3.2，Big5 得分 -18.7"）
     */
    public record Detection(Charset charset, Source source, String reason) {

        /** 直接拿到配套的编解码器，省得调用方再包一层。 */
        public TextCodec codec() {
            return TextCodec.of(charset);
        }

        /** 给界面用的中文名，如"GBK / GB18030"。 */
        public String displayName() {
            return codec().displayName();
        }
    }

    private CharsetDetector() {
        // 工具类不允许实例化
    }

    /**
     * 探测字节数组的编码。只会考察前 {@link #SAMPLE_LIMIT} 个字节。
     *
     * @param data 文件内容（可以只传头部样本，也可以传整个文件）
     */
    public static Detection detect(byte[] data) {
        if (data == null || data.length == 0) {
            return new Detection(StandardCharsets.UTF_8, Source.FALLBACK, "文件为空，按 UTF-8 处理");
        }
        int len = Math.min(data.length, SAMPLE_LIMIT);

        // ---- 第 1 级：BOM 检测 ----
        Detection byBom = detectByBom(data);
        if (byBom != null) {
            return byBom;
        }

        // ---- 第 2 级：无 BOM 的 UTF-16 启发式 ----
        Charset wide = guessUtf16WithoutBom(data, len);
        if (wide != null) {
            return new Detection(wide, Source.UTF16_HEURISTIC,
                    "字节呈 0x00 奇偶规律，判定为无 BOM 的 " + wide.name());
        }

        // ---- 第 3 级：严格 UTF-8 校验 ----
        if (isValidUtf8(data, len)) {
            return new Detection(StandardCharsets.UTF_8, Source.STRICT_UTF8,
                    "符合 UTF-8 语法规则（GBK 编码几乎无法通过该校验）");
        }

        // ---- 第 4 级：候选打分 ----
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(score(data, len, GB18030));
        if (BIG5 != null) {
            candidates.add(score(data, len, BIG5));
        }
        Candidate best = pickBest(candidates);

        // ---- 第 4.5 级：补救闸门 ----
        // 只有当主流中文编码解出来明显是垃圾时，才怀疑是"无 BOM 的 UTF-16"。
        // 这道闸门是为了防止"英文为主的 GBK 文件"被错判成 UTF-16。
        if (best.average < GARBAGE_SCORE) {
            List<Candidate> wideCandidates = new ArrayList<>();
            wideCandidates.add(score(data, len, StandardCharsets.UTF_16LE));
            wideCandidates.add(score(data, len, StandardCharsets.UTF_16BE));
            Candidate bestWide = pickBest(wideCandidates);
            if (bestWide.average > best.average) {
                return new Detection(bestWide.charset, Source.SCORING,
                        buildReason(bestWide) + "（主流中文编码解码失败后改为尝试宽字符编码）");
            }
        }

        // ---- 第 5 级：兜底 ----
        if (best.average < GARBAGE_SCORE) {
            return new Detection(GB18030, Source.FALLBACK,
                    "所有候选编码都无法正常解码，按中文小说最常见的 GB18030 处理；"
                            + buildReason(best));
        }
        return new Detection(best.charset, Source.SCORING, buildReason(best));
    }

    /**
     * 探测文件的编码。只读取文件头部 {@link #SAMPLE_LIMIT} 字节。
     *
     * <p>这个方法在"建索引"和"读正文"两处都会被调用，保证两次结论一致。
     */
    public static Detection detect(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] sample = in.readNBytes(SAMPLE_LIMIT);
            return detect(sample);
        }
    }

    // ==================== 第 1 级：BOM ====================

    private static Detection detectByBom(byte[] data) {
        int bom = TextCodec.bomLength(data);
        if (bom == 0) {
            return null;
        }
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        if (bom == 3) {
            return new Detection(StandardCharsets.UTF_8, Source.BOM, "检测到 UTF-8 BOM（EF BB BF）");
        }
        if (bom == 4) {
            Charset cs = (b0 == 0xFF) ? Charset.forName("UTF-32LE") : Charset.forName("UTF-32BE");
            return new Detection(cs, Source.BOM, "检测到 " + cs.name() + " BOM");
        }
        // bom == 2
        Charset cs = (b0 == 0xFF) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
        return new Detection(cs, Source.BOM, "检测到 " + cs.name() + " BOM");
    }

    // ==================== 第 2 级：无 BOM 的 UTF-16 ====================

    /**
     * 无 BOM 的 UTF-16 启发式判断。
     *
     * <p>原理：ASCII 字符在 UTF-16 里会占两个字节，其中一个恒为 {@code 0x00}。
     * 小端时这个 {@code 0x00} 在<b>奇数位</b>，大端时在<b>偶数位</b>。
     * 所以只要统计"零字节全部集中在某一侧"，就能反推字节序。
     *
     * <p>阈值定得比较保守（要求 70% 以上），宁可漏判也不误判 ——
     * 漏判会走后面的打分流程，误判则会让正常的 GBK 文件全部变乱码。
     */
    private static Charset guessUtf16WithoutBom(byte[] data, int len) {
        if (len < 32) {
            return null; // 样本太短，统计不可靠
        }
        int units = len / 2;
        int zeroOnEven = 0;
        int zeroOnOdd = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            if (data[i] == 0) {
                zeroOnEven++;
            }
            if (data[i + 1] == 0) {
                zeroOnOdd++;
            }
        }
        double evenRatio = (double) zeroOnEven / units;
        double oddRatio = (double) zeroOnOdd / units;
        if (oddRatio >= 0.7 && evenRatio <= 0.1) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenRatio >= 0.7 && oddRatio <= 0.1) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    // ==================== 第 3 级：UTF-8 语法校验 ====================

    /**
     * 逐字节验证是否符合 UTF-8 语法。
     *
     * <p><b>为什么手写而不是用 {@code CharsetDecoder}？</b><br>
     * 用 {@code CharsetDecoder} 也能做（把 {@code onMalformedInput} 设为 {@code REPORT}），
     * 但有个边界麻烦：采样刚好在某个多字节字符中间被切断时，
     * 解码器会报"非法序列"，而实际上文件本身是好的。
     * 手写只需要在结尾遇到"不完整的序列"时直接返回 {@code true} 即可，逻辑更清晰。
     *
     * <p>校验规则（RFC 3629）：
     * <pre>
     *   1 字节  0xxxxxxx                          0x00 - 0x7F
     *   2 字节  110xxxxx 10xxxxxx                 0x80 - 0x7FF        （且必须 ≥ 0x80，拒绝过长编码）
     *   3 字节  1110xxxx 10xxxxxx 10xxxxxx        0x800 - 0xFFFF      （排除代理区 D800-DFFF）
     *   4 字节  11110xxx 10xxxxxx ×3              0x10000 - 0x10FFFF
     * </pre>
     */
    private static boolean isValidUtf8(byte[] data, int len) {
        int i = 0;
        while (i < len) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }

            int need;
            int code;
            if ((b & 0xE0) == 0xC0) {
                need = 1;
                code = b & 0x1F;
            } else if ((b & 0xF0) == 0xE0) {
                need = 2;
                code = b & 0x0F;
            } else if ((b & 0xF8) == 0xF0) {
                need = 3;
                code = b & 0x07;
            } else {
                return false; // 0x80-0xBF（孤立的续字节）或 0xF8-0xFF（非法首字节）
            }

            // 采样被截断：序列不完整不算失败
            if (i + need >= len) {
                return true;
            }

            for (int k = 1; k <= need; k++) {
                int c = data[i + k] & 0xFF;
                if ((c & 0xC0) != 0x80) {
                    return false; // 后续字节必须是 10xxxxxx
                }
                code = (code << 6) | (c & 0x3F);
            }

            // 拒绝"过长编码"（overlong encoding）：这是一种历史上被用来绕过安全检查的写法
            if ((need == 1 && code < 0x80) || (need == 2 && code < 0x800)
                    || (need == 3 && code < 0x10000)) {
                return false;
            }
            if (code > 0x10FFFF) {
                return false;
            }
            if (code >= 0xD800 && code <= 0xDFFF) {
                return false; // UTF-16 代理区在 UTF-8 里是非法的
            }
            i += need + 1;
        }
        return true;
    }

    // ==================== 第 4 级：候选打分 ====================

    /**
     * 一个候选编码的得分。
     *
     * @param charset 候选字符集
     * @param total   总分
     * @param average 平均分（每个字符的得分）
     * @param garbage 替换字符 U+FFFD 的个数
     */
    private record Candidate(Charset charset, double total, double average, int garbage) {
    }

    /**
     * 用指定编码解码样本，然后给结果的"像不像正常中文小说"打分。
     *
     * <p><b>为什么用"平均分"而不是"总分"？</b><br>
     * 同一份字节用不同编码解码，得到的字符数可能差一倍（UTF-16 是两个字节一个字符，
     * GB18030 大多是两个字节一个汉字）。用总分比较就会偏向"字符多"的编码，
     * 造成系统性偏差。平均分是"每个字符值不值得信任"，才可比。
     */
    private static Candidate score(byte[] data, int len, Charset charset) {
        String text = new String(data, 0, len, charset);
        double total = 0;
        int garbage = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD') {
                garbage++;
                total -= 120; // 替换字符 = "这段字节在该编码下根本不存在"，是最强的负面信号
            } else if (c == '\n' || c == '\r' || c == '\t') {
                total += 6;
            } else if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                total -= 60; // 控制字符不该出现在小说正文里
            } else if (c == '\uFEFF') {
                // BOM 字符，忽略不计
            } else if (c >= 0xE000 && c <= 0xF8FF) {
                total -= 40; // 私有区，说明解错了
            } else if (c >= 0x3000 && c <= 0x303F) {
                total += 14; // 中文标点：，。！？「」等
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                total += 12;
                if (COMMON_CHARS.contains(c)) {
                    total += 25; // 命中常用字，强烈支持"解对了"
                }
            } else if (c >= 0xFF00 && c <= 0xFFEF) {
                total += 10; // 全角字符
            } else if (c >= 0x3040 && c <= 0x30FF) {
                total -= 25; // 日文假名：中文小说里不该出现
            } else if (c >= 0xAC00 && c <= 0xD7AF) {
                total -= 25; // 谚文
            } else if (c >= 0x3400 && c <= 0x4DBF) {
                total -= 6; // 中日韩扩展 A 区，生僻字
            } else if (c > 0xFFFF) {
                total -= 20; // 扩展 B 区及以后：小说正文不会用到的超生僻字
            } else if (c >= 0x20 && c <= 0x7E) {
                total += 8; // 可打印 ASCII
            }
        }
        double average = text.isEmpty() ? Double.NEGATIVE_INFINITY : total / text.length();
        return new Candidate(charset, total, average, garbage);
    }

    private static Candidate pickBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            // 同分时优先选垃圾字符更少的
            if (best == null || c.average > best.average
                    || (c.average == best.average && c.garbage < best.garbage)) {
                best = c;
            }
        }
        return best;
    }

    private static String buildReason(Candidate best) {
        return String.format("%s 得分最高（平均每字符 %.2f 分，替换字符 %d 个）",
                best.charset.name(), best.average, best.garbage);
    }
}
