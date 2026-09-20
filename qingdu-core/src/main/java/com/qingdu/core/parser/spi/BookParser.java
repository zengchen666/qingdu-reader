package com.qingdu.core.parser.spi;

import com.qingdu.common.domain.Book;
import com.qingdu.common.domain.BookFormat;
import com.qingdu.common.domain.Chapter;
import com.qingdu.core.parser.BookParseException;

import java.nio.file.Path;
import java.util.List;

/**
 * 图书解析器接口（SPI = Service Provider Interface）。
 *
 * <p><b>这个接口是整个项目"可扩展"的关键。</b>
 *
 * <p>它把"解析一本书"拆成三个独立步骤，每种格式各写一个实现类：
 * <pre>
 *   TxtBookParser   implements BookParser   → 处理 .txt
 *   EpubBookParser  implements BookParser   → 处理 .epub
 *   （以后）MobiBookParser                  → 处理 .mobi
 * </pre>
 *
 * <p>这样做的好处是：<b>新增一种格式，只需要新增一个实现类，
 * 阅读界面和渲染逻辑一行都不用改。</b> 这就是"对扩展开放、对修改关闭"
 * （开闭原则）的实际落地 —— 不是为了炫技，而是因为改渲染层
 * 意味着要重新测试所有已有格式，成本高得多。
 *
 * <p><b>为什么把"读目录"和"读正文"分开？</b><br>
 * 因为一本小说可能有一万章。打开书的时候我们只需要目录，
 * 正文应该等用户真正翻到那一章再去读。所以：
 * <ol>
 *   <li>{@link #parseMetadata} —— 先读到书名、作者这些展示信息</li>
 *   <li>{@link #parseChapters} —— 只扫描出章节的位置，不读正文内容</li>
 *   <li>{@link #loadChapter} —— 用户翻到某章时，才真正读取这一章的文字</li>
 * </ol>
 * 这三步把"打开一本书"的耗时从"读完整个文件"降到"只扫一遍找章节"。
 */
public interface BookParser {

    /**
     * 声明本解析器负责哪种格式。
     *
     * <p>调度器（后面会写）靠这个方法来选择该用哪个解析器，
     * 所以它相当于解析器的"身份证"。
     */
    BookFormat supportedFormat();

    /**
     * 第一步：读取图书元信息（书名、作者、封面）。
     *
     * @param file 图书文件
     * @return 不含章节索引的 {@link Book} 对象
     * @throws BookParseException 文件不存在、损坏或格式不符时抛出
     */
    Book parseMetadata(Path file) throws BookParseException;

    /**
     * 第二步：扫描全书，建立章节索引。
     *
     * <p><b>注意：这里只定位章节，不读取正文内容。</b>
     * 返回的每个 {@link Chapter} 的 {@code blocks} 都是空的，
     * 正文通过 {@link #loadChapter} 按需加载。
     *
     * @param file   图书文件
     * @param bookId 图书 ID，会写进每个 Chapter 里
     * @return 章节骨架列表，按顺序排列
     * @throws BookParseException 解析失败时抛出
     */
    List<Chapter> parseChapters(Path file, String bookId) throws BookParseException;

    /**
     * 第三步：读取指定章节的正文。
     *
     * @param file     图书文件
     * @param skeleton 由 {@link #parseChapters} 产出的章节骨架，
     *                 里面带着定位正文所需的偏移量或 href
     * @return 填充了 {@code blocks} 的完整章节
     * @throws BookParseException 读取失败时抛出
     */
    Chapter loadChapter(Path file, Chapter skeleton) throws BookParseException;
}
