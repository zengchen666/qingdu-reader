#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""轻读阅读器 —— 应用图标生成脚本。

一个 JavaFX + jpackage 的 Windows 应用需要**两份**图标，它们互相独立：

  1. 窗口 / 任务栏 / Alt-Tab 图标
     格式：多档尺寸的 PNG
     位置：qingdu-desktop/src/main/resources/icon/
     用法：QingduApplication 里 stage.getIcons() 加载（打进 jar，开发模式和打包版共用）
  2. exe 文件自身在资源管理器里的图标
     格式：单个 .ico（内部含多档尺寸）
     位置：assets/app.ico
     用法：scripts/package.ps1 里 jpackage --icon 传给打包器

本脚本一次生成这两份，另外输出一张预览图（docs/icon-preview.png）方便肉眼验收。

=== 为什么是"画"而不是"导入一张生成的图" ===

程序图标必须在小尺寸下仍然可辨识。缩放会吃掉细节：一个 1024px 上看着很精致的图，
缩到 16px 往往只剩一团糊。所以这里用几何图形直接在**每个目标尺寸各自绘制**，
每个尺寸都用 4 倍超采样再降采样（LANCZOS）拿到干净的抗锯齿边缘。

顺带解决了另一个问题：细节尺寸可以**按尺寸单独调**。最典型的就是书脊缝隙 ——
在 1024 下 76px 的缝缩到 16px 只剩 1.2px，会被抗锯齿糊掉、两页粘成一个白块。
所以小尺寸专门把缝加宽（见 SPINE_GAP）。这类"光学补偿"是图标设计的常规做法。

依赖 Python 3 + Pillow（`pip install pillow`）。本脚本是**可选**的构建辅助工具：
生成好的 PNG 与 ICO 已经提交进仓库，只有想改图标时才需要重跑。
"""

from __future__ import annotations

import struct
from pathlib import Path

from PIL import Image, ImageDraw

# ---------------------------------------------------------------------
# 设计参数（全部基于 1024×1024 的设计画布，改这里就能改图标）
# ---------------------------------------------------------------------

CANVAS = 1024.0          # 设计画布边长，所有坐标都以它为基准

# 颜色取自 qingdu-desktop/src/main/resources/css/theme-light.css，
# 保证图标和界面是同一套色：底色 = -qd-accent，书页 = 纸面色
ACCENT = (59, 110, 165, 255)    # #3b6ea5  -qd-accent
PAPER = (250, 249, 246, 255)    # #faf9f6  纸面

CHIP_RADIUS = 224.0      # 圆角方形底的四角半径（1024 基准下约 22%）

# 书页几何。左右两页互为镜像，靠近书脊的一侧整体下沉，形成"展开的书"的 V 形谷底。
# 这个下沉是关键 —— 两页如果只是并排的矩形，看起来会像两扇门而不是一本书。
BOOK_OUTER_X = 140.0     # 书的左/右外缘
BOOK_TOP_OUT = 260.0     # 外缘顶边（高）
BOOK_TOP_IN = 328.0      # 靠书脊一侧顶边（低）—— 与上一行的差就是 V 形斜度
BOOK_BOT_OUT = 692.0
BOOK_BOT_IN = 760.0
SPINE_X = CANVAS / 2.0   # 书脊中心线

# 书脊缝隙宽度，按目标尺寸分别取值（1024 基准之外的单位）。
# 大尺寸用默认值保持比例协调；小尺寸加宽，否则缝隙会被抗锯齿抹平、书变成白块。
SPINE_GAP = 76.0
SPINE_GAP_BY_SIZE = {
    16: 140.0,
    24: 120.0,
    32: 104.0,
    48: 92.0,
    64: 84.0,
}

SUPERSAMPLE = 8          # 超采样倍数：先按目标尺寸的 8 倍绘制，再降采样

# 降采样用盒式滤波（面积平均）而不是 LANCZOS。
# 这里踩过一个坑：超采样降采样的数学含义就是"把 N×N 个源像素求平均"，
# 而 LANCZOS 的卷积核比缩放倍数宽得多（4 倍缩放时核宽约 16 个源像素），
# 会把缝隙两侧的纸色渗进缝里 —— 结果 16px 下书脊缝完全消失，两页粘成一个白块。
# 盒式滤波的核正好等于缩放倍数，内部区域能保持精确的原色。
RESAMPLE = Image.BOX

PNG_SIZES = (16, 24, 32, 48, 64, 128, 256, 512)
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)

ROOT = Path(__file__).resolve().parent.parent
RESOURCE_ICON_DIR = ROOT / "qingdu-desktop" / "src" / "main" / "resources" / "icon"
ASSETS_DIR = ROOT / "assets"
DOCS_DIR = ROOT / "docs"
REPORT_PATH = ROOT / "_icon-report.txt"


# ---------------------------------------------------------------------
# 绘制
# ---------------------------------------------------------------------

def render(size: int) -> Image.Image:
    """按目标像素尺寸绘制一张图标，返回 RGBA 图。"""
    work = size * SUPERSAMPLE
    scale = work / CANVAS

    def s(value: float) -> float:
        return value * scale

    image = Image.new("RGBA", (work, work), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # 圆角方形底。四角落在圆角之外的部分保持透明，
    # 这样任务栏和资源管理器看到的是真正的圆角，而不是"白底切了个圆角的样子"。
    draw.rounded_rectangle(
        [0, 0, work - 1, work - 1],
        radius=s(CHIP_RADIUS),
        fill=ACCENT,
    )

    gap = SPINE_GAP_BY_SIZE.get(size, SPINE_GAP)
    half = gap / 2.0

    # 左页 / 右页，各是一个四边形。内缘（靠书脊）比外缘低，
    # 两页合起来在中间形成浅 V —— 这是"摊开的书"最省笔墨的表达。
    left_page = [
        (SPINE_X - half, BOOK_TOP_IN),
        (BOOK_OUTER_X, BOOK_TOP_OUT),
        (BOOK_OUTER_X, BOOK_BOT_OUT),
        (SPINE_X - half, BOOK_BOT_IN),
    ]
    right_page = [
        (SPINE_X + half, BOOK_TOP_IN),
        (CANVAS - BOOK_OUTER_X, BOOK_TOP_OUT),
        (CANVAS - BOOK_OUTER_X, BOOK_BOT_OUT),
        (SPINE_X + half, BOOK_BOT_IN),
    ]
    for page in (left_page, right_page):
        draw.polygon([(s(x), s(y)) for x, y in page], fill=PAPER)

    # 超采样渲染后降采样 = 便宜又干净的抗锯齿。
    # 注意必须用面积平均（RESAMPLE），理由见文件头 RESAMPLE 处的注释。
    return image.resize((size, size), RESAMPLE)


# ---------------------------------------------------------------------
# ICO 封装
#
# 不用 Pillow 的 ICO 保存：它只能从一张源图缩放出所有尺寸，
# 而我们需要"每个尺寸各画一遍"（小尺寸要单独加宽书脊缝）。
# 所以自己拼 ICO 容器 —— 格式本身很简单：文件头 + 每个尺寸一条目录项 + 各自的数据块。
#
# 每张图用最保守的 BMP(DIB) 形式存，而不是 PNG 压缩形式。
# PNG 内嵌是 Vista 之后才有的扩展，个别老 shell 路径会渲染不出；
# DIB 是所有版本都认的原始格式，代价只是文件大一点（256px 那档约 256KB，完全可接受）。
# ---------------------------------------------------------------------

def _dib_payload(image: Image.Image) -> bytes:
    """把一张 RGBA 图编成 ICO 里的 BMP(DIB) 数据块。"""
    size = image.size[0]
    pixels = image.load()

    # 像素数据：BGRA + 自下而上（BMP 是行倒着存的）
    xor = bytearray()
    for y in range(size - 1, -1, -1):
        for x in range(size):
            r, g, b, a = pixels[x, y]
            xor += bytes((b, g, r, a))

    # AND 掩码：32 位带 alpha 的图标用不到它（系统看 alpha 通道），但格式要求必须有。
    # 每行按 4 字节对齐，这里是"全不透明"以外的位置本来由 alpha 决定，所以整块填 0。
    mask_stride = ((size + 31) // 32) * 4
    and_mask = bytes(mask_stride * size)

    # BITMAPINFOHEADER：高度要写 2 倍，因为后面跟着 XOR 和 AND 两张位图
    header = struct.pack(
        "<IiiHHIIiiII",
        40,           # biSize
        size,         # biWidth
        size * 2,     # biHeight（含掩码，所以是两倍）
        1,            # biPlanes
        32,           # biBitCount
        0,            # biCompression = BI_RGB
        0,            # biSizeImage
        0, 0,         # 分辨率（不关心）
        0, 0,         # 调色板
    )
    return header + bytes(xor) + and_mask


def write_ico(path: Path, images: list[Image.Image]) -> None:
    images = sorted(images, key=lambda im: im.size[0])
    payloads = [_dib_payload(im) for im in images]

    out = bytearray(struct.pack("<HHH", 0, 1, len(images)))   # reserved, type=1(ICO), count
    offset = 6 + 16 * len(images)
    for image, payload in zip(images, payloads):
        size = image.size[0]
        # 目录项里 0 表示 256（一个字节存不下 256）
        out += struct.pack(
            "<BBBBHHII",
            size if size < 256 else 0,
            size if size < 256 else 0,
            0, 0,          # 调色板数量、保留位
            1, 32,         # 色彩平面数、位深
            len(payload),
            offset,
        )
        offset += len(payload)
    for payload in payloads:
        out += payload

    path.write_bytes(bytes(out))


# ---------------------------------------------------------------------
# 预览图
# ---------------------------------------------------------------------

def write_preview(path: Path, images: dict[int, Image.Image]) -> None:
    """把各尺寸并排画在浅色和深色两种背景上。

    深色那一行不是凑数：Windows 任务栏可能在深色模式下，
    浅色底上好看的图标放到深色任务栏上可能就"飘"了，必须两种都看一眼。
    """
    pad = 28
    cell_gap = 22
    row_h = 300

    order = (256, 128, 64, 48, 32, 24, 16)
    width = pad * 2 + sum(images[n].size[0] for n in order) + cell_gap * (len(order) - 1)
    width = max(width, 520)
    height = row_h * 2 + pad

    sheet = Image.new("RGBA", (width, height), (240, 240, 240, 255))
    draw = ImageDraw.Draw(sheet)

    for row, bg in enumerate(((250, 249, 246, 255), (34, 36, 42, 255))):
        top = pad // 2 + row * row_h
        draw.rectangle([0, top, width, top + row_h - pad // 2], fill=bg)

        x = pad
        for n in order:
            icon = images[n]
            y = top + (row_h - pad // 2 - n) // 2
            sheet.alpha_composite(icon, (x, y))
            x += n + cell_gap

    sheet.convert("RGB").save(path, "PNG")


# ---------------------------------------------------------------------
# 自检
#
# 因为看不到图片，所以用数值确认"画对了"。做法是把中间一行横向扫描一遍，
# 把每个像素分类成 底(蓝) / 纸(白) / 透明 / 过渡，再压成游程字符串。
# 一眼就能看出图标是不是"底-纸-缝-纸-底"这个结构 —— 也就是书脊读不读得出来。
# ---------------------------------------------------------------------

def _classify(pixel: tuple[int, int, int, int]) -> str:
    r, g, b, a = pixel
    if a < 128:
        return "."
    rgb = (r, g, b)

    def near(target: tuple[int, int, int]) -> bool:
        # 三通道差值之和在容差内即认为同色。蓝和纸的差值总和接近 400，容差 40 足够区分；
        # 两者各占一半的过渡像素距离两边都在 90 以上，会被判成 "~"。
        return sum(abs(x - y) for x, y in zip(rgb, target)) <= 40

    if near(ACCENT[:3]):
        return "A"
    if near(PAPER[:3]):
        return "P"
    return "~"


def _run_length(text: str) -> str:
    parts = []
    index = 0
    while index < len(text):
        char = text[index]
        end = index
        while end < len(text) and text[end] == char:
            end += 1
        parts.append(f"{char}{end - index}")
        index = end
    return " ".join(parts)


def self_check(images: dict[int, Image.Image]) -> list[str]:
    lines = []
    for size in sorted(images):
        image = images[size]
        pixels = image.load()
        mid = size // 2

        # 中间一行：预期是 底-纸-缝-纸-底，缝就是书脊
        profile = "".join(_classify(pixels[x, mid]) for x in range(size))
        corners = [pixels[0, 0][3], pixels[size - 1, 0][3],
                   pixels[0, size - 1][3], pixels[size - 1, size - 1][3]]

        # 顺带量一下"书的竖向范围"：外缘那一列（x 取 1/6 处）纸色的起止行。
        # 这个值应该随尺寸等比变化，用来确认几何没被写歪。
        probe_x = size // 6
        column = [y for y in range(size) if _classify(pixels[probe_x, y]) == "P"]
        vertical = f"{min(column)}..{max(column)}" if column else "无"

        lines.append(
            f"  {size:>3}px  行剖面[{_run_length(profile)}]  "
            f"外缘纸色行 {vertical}  四角 alpha {corners}"
        )
    return lines


def main() -> None:
    RESOURCE_ICON_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    DOCS_DIR.mkdir(parents=True, exist_ok=True)

    # 每个尺寸各画一遍：小尺寸的书脊缝要单独加宽，不能从大图缩
    needed = sorted(set(PNG_SIZES) | set(ICO_SIZES))
    images = {size: render(size) for size in needed}

    # 1) 多尺寸 PNG —— 给 JavaFX stage.getIcons() 用
    for size in PNG_SIZES:
        images[size].save(RESOURCE_ICON_DIR / f"icon-{size}.png", "PNG")

    # 2) 单个多档 ICO —— 给 jpackage --icon 用
    write_ico(ASSETS_DIR / "app.ico", [images[n] for n in ICO_SIZES])

    # 3) 预览图 —— 给人看
    write_preview(DOCS_DIR / "icon-preview.png", images)

    ico_path = ASSETS_DIR / "app.ico"
    lines = [
        "应用图标生成报告",
        "",
        "已生成：",
        f"  PNG : {RESOURCE_ICON_DIR.relative_to(ROOT)}/icon-"
        f"{{{','.join(str(n) for n in PNG_SIZES)}}}.png",
        f"  ICO : {ico_path.relative_to(ROOT)}"
        f"  ({ICO_SIZES[0]}~{ICO_SIZES[-1]}px, {ico_path.stat().st_size / 1024:.0f} KB)",
        f"  预览: {(DOCS_DIR / 'icon-preview.png').relative_to(ROOT)}",
        "",
        "自检 —— 中间一行横向扫描（A=底色蓝  P=书页白  .=透明  ~=过渡），"
        "预期结构是 A P A P A，中间那个 A 就是书脊缝：",
    ]
    lines += self_check(images)

    report = "\n".join(lines) + "\n"
    # 直接以 UTF-8 写文件，不经过控制台：PowerShell 管道的编码会按系统 ANSI 解，
    # 中文会变成乱码（本机实测）。
    REPORT_PATH.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
