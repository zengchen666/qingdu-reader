package com.qingdu.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 图书 ID 生成器 —— 由文件路径推导出一个稳定的标识。
 *
 * <p><b>为什么不能用 UUID？</b><br>
 * 最开始的实现里，{@code parseMetadata} 每次调用都 {@code UUID.randomUUID()}。
 * 对"打开一本书、读完、关掉"这个流程完全没问题，但一旦要<b>记住阅读进度</b>就崩了：
 * 同一个文件第二次打开会拿到一个全新的 ID，上次存的进度按新 ID 根本查不到，
 * 于是进度条永远从第一章开始。
 *
 * <p>阅读进度、书签这些东西天生要求"同一个文件 → 同一个 ID"，所以 ID 必须从
 * 文件本身推导出来，而不是随机生成。
 *
 * <p><b>为什么是路径而不是文件内容？</b><br>
 * 用内容哈希（比如"前 64KB + 文件大小"）能扛住"文件被移动/改名"，
 * 但代价是每次打开一本书都要多读一遍文件；而路径哈希是零成本的。
 * 更关键的是：<b>同一个路径换了内容</b>（重新下载了一本同名小说）时，
 * 用户期望的是"还是这本书，进度重来"，路径方案正好是这个语义。
 * 所以这里选路径。
 *
 * <p><b>为什么不直接用路径字符串当 ID？</b><br>
 * 也可以，但路径里有盘符、反斜杠、中文，长度不定；放进数据库当主键和外键
 * 会显得很笨重，而且在日志里打印一串路径不如一个短 ID 清爽。
 * 摘要成定长十六进制后，索引也更紧凑。
 *
 * <p><b>Windows 路径的坑</b>：同一个文件可以有很多种写法
 * （{@code C:\a.txt} / {@code c:\A.TXT} / 8.3 短名 / 符号链接）。
 * 直接拿用户输入的原样字符串去哈希，会出现"同一个文件被当成两本书"。
 * 所以这里优先用 {@link Path#toRealPath()} 拿到系统规范路径 ——
 * 它会解析符号链接、统一大小写、展开 8.3 短名。
 */
public final class BookId {

    /** 取摘要的前 8 字节（16 个十六进制字符）。 */
    private static final int ID_BYTES = 8;

    private BookId() {
        // 工具类不允许实例化
    }

    /**
     * 根据文件路径生成稳定的图书 ID。
     *
     * @param file 文件路径，不能为空
     * @return 16 个十六进制字符
     */
    public static String of(Path file) {
        return ofPath(canonical(file));
    }

    /**
     * 把路径规范化成"能代表这个文件"的字符串。
     *
     * <p>先试 {@code toRealPath()}：它要求文件存在，会返回系统眼中的真实路径，
     * 顺带解决大小写、符号链接、短文件名的问题。
     * 文件不存在时（比如数据库中残留的记录指向一个已经被删掉的文件）会抛
     * {@link IOException}，这时退回 {@code toAbsolutePath().normalize()}——
     * 虽然不如前者精确，但至少是稳定的。
     */
    public static String canonical(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        try {
            return file.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            // 文件不存在 / 权限不足 / 路径非法，都退回到只做绝对化 + 归一化
            return file.toAbsolutePath().normalize().toString();
        }
    }

    /** 对任意字符串取摘要，主要给测试和特殊场景用。 */
    public static String ofPath(String canonicalPath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPath.getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[ID_BYTES];
            System.arraycopy(digest, 0, head, 0, ID_BYTES);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 的必需算法，理论上不可能走到这里
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
