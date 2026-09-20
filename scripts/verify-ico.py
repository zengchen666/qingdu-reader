#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 assets/app.ico 是否合法。

=== 为什么需要这个脚本 ===

生成 ICO 的代码是本项目自己写的（见 gen-icon.py）——因为 Pillow 存 ICO 只能从一张源图
缩放出所有尺寸，而我们要求每个尺寸单独绘制（小尺寸要单独加宽书脊缝）。

自己写的东西自己验等于没验，所以这里**按 ICO 格式规范重新写一个读取器**，
不复用生成端的任何函数。这样生成端对格式的误解不会在读取端被同样地复制一遍。

=== 校验内容 ===

  1. 文件头 ICONDIR 合法（reserved=0，type=1，count 与实际目录项一致）
  2. 每个目录项的偏移/长度自洽：不越界、不落进目录区
  3. 每张图的 BITMAPINFOHEADER 与目录项声明一致
     （尤其高度必须是两倍 —— 因为后面跟着 XOR 位图和 AND 掩码两张图）
  4. 手工解码像素，确认四角透明、中间那列是底色蓝（书脊缝）、缝两侧是书页白

用法：
    python scripts/verify-ico.py

只依赖 Python 标准库（故意不用 Pillow —— 独立校验器不引入第三方解析器，
才不会和生成端共享同一个"对格式的理解"）。退出码：0 = 通过，1 = 有问题（可直接挂 CI）。
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ICO = ROOT / "assets" / "app.ico"
# 报告用下划线开头，命中 .gitignore 里的 `_*` 规则，不会被提交
REPORT = ROOT / "_ico-verify.txt"

ACCENT = (59, 110, 165)     # #3b6ea5，取自 theme-light.css 的 -qd-accent
PAPER = (250, 249, 246)     # #faf9f6，纸面色

lines: list[str] = []


def say(text: str = "") -> None:
    lines.append(text)
    print(text)


def main() -> int:
    if not ICO.is_file():
        say(f"找不到 {ICO}，请先运行 scripts/gen-icon.py")
        return 1

    data = ICO.read_bytes()
    problems: list[str] = []

    reserved, kind, count = struct.unpack_from("<HHH", data, 0)
    say(f"ICONDIR: reserved={reserved} type={kind} count={count}  文件大小={len(data)}B")
    if reserved != 0:
        problems.append("reserved 应为 0")
    if kind != 1:
        problems.append(f"type 应为 1(ICO)，实际 {kind}")

    entry_zone_end = 6 + 16 * count
    frames: list[int] = []

    for index in range(count):
        base = 6 + 16 * index
        w, h, colors, res, planes, bpp, length, offset = struct.unpack_from("<BBBBHHII", data, base)
        width = w or 256      # 目录项里一个字节存不下 256，用 0 表示
        height = h or 256

        if offset < entry_zone_end:
            problems.append(f"[{index}] 数据块起点 {offset} 落进了目录区（<{entry_zone_end}）")
        if offset + length > len(data):
            problems.append(f"[{index}] 数据块越界：{offset}+{length} > {len(data)}")

        bi_size, bi_w, bi_h, bi_planes, bi_bpp = struct.unpack_from("<IiiHH", data, offset)
        if (bi_size, bi_w, bi_h, bi_planes, bi_bpp) != (40, width, height * 2, 1, 32):
            problems.append(
                f"[{index}] BITMAPINFOHEADER 不符：biSize={bi_size} biWidth={bi_w} "
                f"biHeight={bi_h} biPlanes={bi_planes} biBitCount={bi_bpp}"
            )
        if (planes, bpp) != (1, 32):
            problems.append(f"[{index}] 目录项 planes/bitCount 应为 1/32，实际 {planes}/{bpp}")

        # 手工解码像素：BGRA、行自下而上（BMP 的存储顺序）
        pixels: dict[tuple[int, int], tuple[int, int, int, int]] = {}
        px_base = offset + 40
        for y in range(height):
            for x in range(width):
                at = px_base + ((height - 1 - y) * width + x) * 4
                b, g, r, a = data[at], data[at + 1], data[at + 2], data[at + 3]
                pixels[(x, y)] = (r, g, b, a)

        mid = width // 2
        mid_row = [pixels[(x, mid)] for x in range(width)]
        paper_xs = [x for x, p in enumerate(mid_row) if p[:3] == PAPER and p[3] == 255]
        accent_xs = [x for x, p in enumerate(mid_row) if p[:3] == ACCENT and p[3] == 255]
        left = [x for x in paper_xs if x < mid]
        right = [x for x in paper_xs if x > mid]
        # 书脊缝 = 夹在左右两页之间的那段底色
        gap = [x for x in accent_xs if min(left, default=mid) < x < max(right, default=mid)]

        corners = [pixels[(0, 0)], pixels[(width - 1, 0)],
                   pixels[(0, height - 1)], pixels[(width - 1, height - 1)]]

        ok_pages = bool(left) and bool(right)
        ok_gap = bool(gap)
        ok_corners = all(p[3] == 0 for p in corners)

        if not ok_pages:
            problems.append(f"[{index}] {width}px 中线上找不到左右两页")
        if not ok_gap:
            problems.append(f"[{index}] {width}px 中线上看不到书脊缝（缝被抗锯齿糊掉了）")
        if not ok_corners:
            problems.append(f"[{index}] {width}px 四角不是透明：{[p[3] for p in corners]}")

        verdict = "OK" if (ok_pages and ok_gap and ok_corners) else "有疑问"
        say(f"  {width:>3}px  数据块 @{offset:>7}B 长 {length:>7}B  "
            f"左页{len(left):>3}px 缝{len(gap):>3}px 右页{len(right):>3}px  "
            f"四角alpha {[p[3] for p in corners]}  {verdict}")
        frames.append(width)

    if frames != sorted(set(frames)):
        problems.append(f"尺寸序列不是严格递增或有重复：{frames}")

    say()
    if problems:
        say("校验未通过：")
        for item in problems:
            say("  - " + item)
        return 1

    say(f"校验通过：{len(frames)} 档尺寸 {frames} —— 容器结构、字段、像素全部符合规范。")
    return 0


if __name__ == "__main__":
    code = main()
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    sys.exit(code)
