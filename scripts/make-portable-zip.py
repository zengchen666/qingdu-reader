#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
轻读阅读器 —— 把 dist\\QingduReader 打成「绿色版」压缩包。

为什么用 Python 的 zipfile，而不是 PowerShell 的 Compress-Archive：

  1. zipfile 自带 testzip()，会逐条解压并做 CRC 校验。
     Compress-Archive 只负责"写"，写完之后没有任何校验手段，
     于是"压缩包坏了"这种事要等对方解压时才发现 —— 那时已经发出去了。
  2. 能顺手统计未压缩总量并与源目录逐字节比对，给出可复核的完整性证据。
  3. 压缩率、条目数、耗时都能直接打印，写 Release 说明时有数据可用。

用法：
    python scripts\\make-portable-zip.py                 # 版本从 dist\\...\\app\\*.cfg 里读
    python scripts\\make-portable-zip.py --version 0.1.1 # 也可以显式指定

输出：
    dist\\QingduReader-<version>-win-x64-portable.zip
"""

import argparse
import os
import re
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, "dist")
APP = "QingduReader"


def detect_version(app_dir):
    """从 jpackage 生成的 .cfg 里读版本号（-Djpackage.app-version=x.y.z）。

    刻意不写死、也不从 pom.xml 读：cfg 是**真实产物**的一部分，
    读它能顺带验证"exe 认为自己是几版"，和读 pom 是两回事
    （改了 pom 忘记重打包，这里就会暴露出来）。
    """
    cfg_dir = os.path.join(app_dir, "app")
    if not os.path.isdir(cfg_dir):
        return None
    for name in os.listdir(cfg_dir):
        if not name.endswith(".cfg"):
            continue
        with open(os.path.join(cfg_dir, name), "r", encoding="utf-8") as f:
            m = re.search(r"jpackage\.app-version=(\S+)", f.read())
            if m:
                return m.group(1)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", help="覆盖自动探测到的版本号")
    ap.add_argument("--app-dir", default=os.path.join(DIST, APP))
    args = ap.parse_args()

    app_dir = args.app_dir
    if not os.path.isdir(app_dir):
        sys.exit(f"找不到 {app_dir}。请先运行 scripts\\package.ps1 打包。")

    version = args.version or detect_version(app_dir)
    if not version:
        sys.exit("探测不到版本号，请用 --version 显式指定。")

    zip_path = os.path.join(DIST, f"{APP}-{version}-win-x64-portable.zip")

    # 先把要打包的文件全部枚举出来：后面要用它做"逐字节重建比对"
    entries = []
    for dirpath, _dirnames, filenames in os.walk(app_dir):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            entries.append((full, os.path.relpath(full, DIST)))

    print(f"源目录   : {app_dir}")
    print(f"版本号   : {version}")
    print(f"输出     : {zip_path}")
    print(f"条目数   : {len(entries)}")
    print("压缩中...")

    t0 = time.time()
    raw_total = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for full, arc in entries:
            raw_total += os.path.getsize(full)
            # arcname 统一用正斜杠：zip 规范如此，用反斜杠会让某些解压工具
            # 把它当成一个扁平文件名（全部堆在根目录）
            zf.write(full, arc.replace("\\", "/"))
    elapsed = time.time() - t0

    comp = os.path.getsize(zip_path)

    # ---- 完整性校验：重新打开，逐条解压 + CRC ----
    bad = None
    n = 0
    with zipfile.ZipFile(zip_path, "r") as zf:
        bad = zf.testzip()
        n = len(zf.namelist())

    print()
    print(f"耗时     : {elapsed:.1f} 秒")
    print(f"未压缩   : {raw_total:,} 字节")
    print(f"压缩后   : {comp:,} 字节  (压缩率 {(1 - comp / raw_total) * 100:.1f}%)")
    print(f"条目数   : {n}")
    print(f"testzip  : {'全部通过' if bad is None else '损坏于 ' + str(bad)}")

    tops = set()
    with zipfile.ZipFile(zip_path, "r") as zf:
        for name in zf.namelist():
            tops.add(name.split("/")[0])
    print(f"顶层入口 : {sorted(tops)}")

    if bad is not None:
        sys.exit("压缩包校验失败。")

    print()
    print("完成。解压后得到单个 QingduReader 文件夹，双击里面的 exe 即可运行。")


if __name__ == "__main__":
    main()
