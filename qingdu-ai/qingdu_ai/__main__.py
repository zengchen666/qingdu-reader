"""``python -m qingdu_ai`` 入口。

单独一个文件是为了让 ``python -m qingdu_ai`` 与 ``qingdu-ai`` 命令
走同一份 :func:`main`，避免两个入口行为不一致
（这种不一致通常表现为"命令行能跑但 pyproject 里那个不能"，很难查）。
"""

from .server import main

if __name__ == "__main__":
    main()
