package com.qingdu.core.parser;

import com.qingdu.common.domain.BookFormat;
import com.qingdu.core.parser.epub.EpubBookParser;
import com.qingdu.core.parser.spi.BookParser;
import com.qingdu.core.parser.txt.TxtBookParser;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析器注册表 —— 按格式挑出该用哪个 {@link BookParser}。
 *
 * <p><b>为什么要有这一层，而不是界面里直接 new？</b><br>
 * v0.5 之前只有 TXT，界面上写 {@code new TxtBookParser()} 是够用的。
 * 加了 EPUB 之后，"打开一个文件该走哪条解析路径"变成了分支逻辑，
 * 而这个判断放在界面里会让"新增一种格式"变成"改界面代码" ——
 * 那正好是 {@link BookParser} 这个 SPI 想避免的事。
 *
 * <p>现在新增格式的完整步骤是：<b>写一个实现类 + 在这里加一行</b>，
 * 界面、渲染、搜索全都不用动。
 *
 * <p><b>为什么不走 {@code ServiceLoader}？</b>
 * 真正的 SPI 发现机制要求每个实现提供 {@code META-INF/services} 声明，
 * 好处是可以从外部 jar 加载插件。但本项目四个模块都在同一个构建里，
 * 没有"运行时再加解析器"的需求，而 {@code ServiceLoader} 会把
 * "到底有几个解析器、各自什么顺序"变成隐式的（取决于 classpath 扫描结果）。
 * 显式列出来，清单就是这一行 —— 查找成本远低于它的收益。
 */
public final class BookParsers {

    /**
     * 全部可用的解析器。
     *
     * <p>顺序无关，因为按格式精确匹配；保持 TXT 在前只是读起来顺。
     */
    private static final List<BookParser> ALL = List.of(
            new TxtBookParser(),
            new EpubBookParser());

    private BookParsers() {
        // 工具类不允许实例化
    }

    /** 全部解析器。给"关于"对话框之类的地方列出支持哪些格式用。 */
    public static List<BookParser> all() {
        return ALL;
    }

    /**
     * 挑出能处理这个文件的解析器。
     *
     * @return 找到返回实现；格式不支持或路径为空返回 {@code null}
     *
     * <p>返回 null 而不是抛异常，是因为"不支持"是<b>正常的业务分支</b>：
     * 用户完全可能选到一个 .pdf。调用方据此弹出一句解释即可，
     * 不该被当成意外错误。
     */
    public static BookParser forFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        BookFormat format = BookFormat.fromFileName(file.getFileName().toString());
        if (format == BookFormat.UNKNOWN) {
            return null;
        }
        for (BookParser parser : ALL) {
            if (parser.supportedFormat() == format) {
                return parser;
            }
        }
        return null;
    }

    /** 这个文件的格式是否被支持。 */
    public static boolean isSupported(Path file) {
        return forFile(file) != null;
    }

    /**
     * 所有被支持格式的扩展名（带点），给文件选择器和批量导入用。
     *
     * <p>从解析器反推而不是写死一份清单 —— 否则加一个格式忘了改这里，
     * 就会出现"解析器支持但选择器选不到"，而这类不一致不会报错。
     */
    public static Set<String> supportedExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        for (BookParser parser : ALL) {
            String extension = parser.supportedFormat().extension();
            if (!extension.isEmpty()) {
                extensions.add(extension);
            }
        }
        return extensions;
    }
}
