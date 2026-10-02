"""用真书语料量一件事：轻读这层检索词该不该去掉疑问词。

判据：FTS5 的 MATCH 要求**所有** token 都出现，缺一个就整条召不回。
所以要比较的是"整串切出的 bigram 里，有多少在原文中真的出现过"。

这是真机验收（2026-10-03）里没量过的一环 —— 当时只看了最终召回段数，
没看"检索词本身"这一步加了多少不必要的 token。
"""
import io
import os
import sys

CORPUS = [
    ("全职高手", "《全职高手》（精校版全本）作者蝴蝶蓝.txt"),
    ("斗破苍穹", "《斗破苍穹》（精校无删减版）作者：天蚕土豆.txt"),
    ("武动乾坤", "《武动乾坤》.txt"),
    ("元尊", "《元尊》.txt"),
]

# 轻读 AskKeyword 的去词表（保持同步）
QUESTION_WORDS = [
    "是什么", "哪本书", "在哪里", "哪儿", "哪里", "第几章",
    "为什么", "怎么", "怎么样", "是谁", "是谁的", "哪个", "哪些",
    "多少", "什么时候", "有没有", "能否", "可以", "告诉",
]


def ask_keyword(q: str) -> str:
    out = q.strip()
    for w in QUESTION_WORDS:
        out = out.replace(w, "")
    for p in ["？", "?", "。", "，", "、", "！"]:
        out = out.replace(p, " ")
    return " ".join(out.split())


def is_han(c: str) -> bool:
    return "\u4e00" <= c <= "\u9fff"


def bigrams(s: str):
    """模拟 CjkTokenizer 的二字滑窗：只对含汉字的相邻对产token。"""
    out = []
    for i in range(len(s) - 1):
        bg = s[i:i + 2]
        if is_han(bg[0]) or is_han(bg[1]):
            out.append(bg)
    return out


CASES = [
    ("全职高手", "叶修是怎么退役的"),
    ("全职高手", "叶修是谁"),
    ("斗破苍穹", "药老是什么来历"),
    ("斗破苍穹", "药老是哪本书的人物"),
    ("武动乾坤", "林动为什么能修炼"),
    ("武动乾坤", "云岚宗在哪里"),
    ("元尊", "元尊是什么来历"),
    ("全职高手", "苏沐橙怎么加入嘉世"),
    ("斗破苍穹", "萧炎在哪里拜师"),
    ("武动乾坤", "林动是谁"),
]


def main() -> int:
    # 语料目录从命令行传，不写死本机绝对路径（��项目里其他探针一样的规矩）。
    # ⚠️ 目录路径一律 **ASCII** —— 中文路径经命令行传给 JVM 会 InvalidPathException，
    # 这个坑在 CorpusProbe 上踩过。想看中文书名就在 ASCII 目录里放中文**文件名**。
    here = os.path.dirname(os.path.abspath(__file__))
    corpus_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "tmp-corpus")
    report_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "kw-probe.md")

    books = {}
    for name, fn in CORPUS:
        path = os.path.join(corpus_dir, fn)
        if not os.path.exists(path):
            print(f"跳过 {name}: 找不到 {fn}")
            continue
        # ⚠️ 语料是 GBK 而不是 UTF-8（真书语料的老规矩）。
        # 用 UTF-8 读会得到满屏替换字符，于是"叶修"这种词也查不到 ——
        # 一开始就是这么错的：命中率全是 0，看起来像"两种策略都极差"。
        with io.open(path, encoding="gb18030", errors="ignore") as fh:
            books[name] = fh.read()

    report = io.open(report_path, "w", encoding="utf-8")
    report.write("# 轻读检索词：去疑问词 vs 整句（真书实测）\n\n")
    report.write("判据：bigram 里有多少在原文真实出现过。"
                 "全中=召回到该章，缺一个=整条召不回。\n\n")
    report.write("| 书 | 问题 | 检索词（去疑问词） | 去词 token 命中 | 整句 token 命中 | 结论 |\n")
    report.write("|---|---|---|---|---|---|\n")

    better_whole = 0
    better_kw = 0
    same = 0
    for book, q in CASES:
        text = books.get(book)
        if text is None:
            continue
        kw = ask_keyword(q)
        for label, needle in (("kw", kw), ("whole", q)):
            bgs = bigrams(needle)
            hit = [b for b in bgs if b in text]
            miss = [b for b in bgs if b not in text]
            if label == "kw":
                kw_hit, kw_miss = hit, miss
            else:
                w_hit, w_miss = hit, miss
        if len(kw_miss) < len(w_miss):
            better_kw += 1
            verdict = "去词更好"
        elif len(kw_miss) > len(w_miss):
            better_whole += 1
            verdict = "**整句更好**"
        else:
            same += 1
            verdict = "一样"
        report.write(f"| {book} | {q} | {kw} | {len(kw_hit)}/{len(kw_hit) + len(kw_miss)} | "
                     f"{len(w_hit)}/{len(w_hit) + len(w_miss)} | {verdict} |")
        report.write(f"\n<!-- kw缺={kw_miss} 整句缺={w_miss} -->\n")

    report.write(f"\n合计：去词更好 {better_kw} 次，整句更好 {better_whole} 次，持平 {same} 次\n")
    report.close()
    print(f"better_kw={better_kw} better_whole={better_whole} same={same} "
          f"report={report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())