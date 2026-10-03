"""运行配置 —— 进程环境变量与 ``.env`` 文件，环境变量优先。

配置从两处取，优先级**进程环境变量 > ``qingdu-ai/.env``**：

1. 进程环境变量（``set`` / ``$env:`` / 系统环境变量）—— 临时覆盖用；
2. ``qingdu-ai/.env`` 文件 —— **日常用这个**，写一次就不用再管。

**为什么改成支持 ``.env``（2026-10-04 推翻原先"只认环境变量"的决定）？**

原先的理由是"藏在目录里的配置文件，用户改了之后不知道要重启什么"。
这个担心是对的，但**代价被低估了**：环境变量只在**当前那个终端窗口**里有效，
关掉重开就得重敲三行。实际结果是用户反复配不上，甚至以为是自己 key 有问题
（实机反馈："一直配不上key"）。相比之下"改了 .env 要重启服务"是个
**一次性的、可以写进提示**的小事 —— 所以那个担心的正确解法不是
"不提供配置文件"，而是**启动时把配置来源打印出来**（见 ``server.py`` 的启动提示）。

**为什么"没配 key 也要能启动"？**
如果缺 key 就直接退出进程，轻读探测 8000 端口会得到"连接被拒绝"，
只能提示"服务未启动，请运行 python -m qingdu_ai" —— 而真实原因是"你忘了填 key"。
两个完全不同的原因给出同一句提示，是很难排查的体验。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

#: 服务默认监听地址。与轻读侧探测的端口必须一致（见 ReaderView 的 AI 服务探测）。
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8000

#: LLM 调用超时（秒）。设成 90 秒的理由：一次问答要送 8 个片段进去，
#: 加上中文 token 化偏慢，30 秒在慢网络下会误报失败。
DEFAULT_TIMEOUT_SECONDS = 90.0

#: 配置文件名。与 ``.gitignore`` 里那条 ``.env`` 对应 —— **绝不要提交**。
ENV_FILE_NAME = ".env"


@dataclass(frozen=True)
class Settings:
    """一份不可变的配置快照。"""

    base_url: str
    api_key: str
    model: str
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT

    #: 实际读到的 ``.env`` 路径；空串表示没有配置文件。
    #: 仅用于启动时如实告知来源 —— 用户改了文件却没重启时，
    #: 这行提示能让他立刻明白"改动还没生效"。
    env_file: str = ""
    #: 由 ``.env``（而不是进程环境变量）提供的变量名。
    #: 用于区分"key 来自文件"和"key 来自环境变量" —— 两者同时存在时，
    #: 用户改文件却不生效，只有说清"这次用的是环境变量"才不会白折腾。
    from_file: tuple[str, ...] = ()

    @property
    def has_api_key(self) -> bool:
        """只判断"有没有"，不判断格式 —— 格式对不对由模型服务回答。"""
        return bool(self.api_key.strip())

    @property
    def configured(self) -> bool:
        """能不能发起一次调用。缺 base_url 或 model 都不算配置完整。"""
        return self.has_api_key and bool(self.base_url.strip()) and bool(self.model.strip())

    @property
    def llm_state(self) -> str:
        """回报给 `/api/health` 的三态。

        - ``not-configured``：base_url / model 有一项没填
        - ``no-api-key``：就差 API key
        - ``ready``：可以调用
        """
        if not self.configured:
            if not self.base_url.strip() or not self.model.strip():
                return "not-configured"
            return "no-api-key"
        return "ready"


def env_file_path() -> Path:
    """默认配置文件位置：``qingdu-ai/.env``。

    用 ``__file__`` 定位，**不是当前工作目录** —— 从哪个目录敲命令都能找到它。
    绿色版里对应 ``QingduReader\\qingdu-ai\\.env``，同一套逻辑照样成立。
    """
    return Path(__file__).resolve().parent.parent / ENV_FILE_NAME


def parse_env_text(text: str) -> dict[str, str]:
    """解析 ``.env`` 文本。

    容忍：空行、``#`` 注释、``export `` 前缀、值两侧的成对引号、
    键与值周围的空白。**不支持行内注释** —— ``KEY=a # b`` 里 ``# b`` 会被
    当成值的一部分。因为 `#` 是合法 URL 片段字符（锚点），
    猜错的代价是"值莫名多了几个字符"这种极难查的问题，不如不支持。
    """
    out: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, sep, value = line.partition("=")
        if not sep:
            # 没有 = 的行直接跳过（比如手写的说明文字），
            # 不报错 —— 一个笔误不该让整个服务起不来。
            continue
        key = key.strip()
        if not key:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key] = value
    return out


def read_env_file(path: Path | None = None) -> dict[str, str] | None:
    """读 ``.env``。

    :return: 读到则返回键值字典（**可以是空字典**）；文件不存在或读不动作废返回 ``None``。

    两条纪律：

    1. **用 ``utf-8-sig`` 读** —— Windows 记事本存 UTF-8 会加 BOM，
       用 ``utf-8`` 读会让第一个键变成 ``"\\ufeffQINGDU_LLM_BASE_URL"``，
       于是**整份配置静默失效**（BOM 不可见，属最难查的一类）。
    2. **读不了就当没有，绝不抛异常** —— 配置文件编码坏了不该让服务起不来，
       否则用户看到的是一堆 Python 栈，根本想不到是自己那个文件的问题。
    """
    target = path if path is not None else env_file_path()
    try:
        text = target.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError):
        return None
    return parse_env_text(text)


def _lookup(name: str, file_vars: Mapping[str, str]) -> tuple[str, bool]:
    """按优先级取一个变量的值。

    :return: ``(值, 是否来自 .env)``

    环境变量存在但**全是空白**时视为未设置，继续往下看 ``.env``。
    理由：``set QINGDU_LLM_API_KEY=`` 这种空值几乎总是误设，
    若把它当成"显式禁用"，用户会遇到"我明明写了 .env 却不生效" ——
    想禁用的人删掉 ``.env`` 就行，而误设空值的人只会一头雾水。
    两种失败模式的代价不对等，所以偏向后者。
    """
    raw = os.environ.get(name)
    if raw is not None and raw.strip():
        return raw.strip(), False
    from_file = file_vars.get(name)
    if from_file is not None and from_file.strip():
        return from_file.strip(), True
    return "", False


def _positive_float(raw: str, fallback: float) -> float:
    """解析正浮点；非法值退回默认而不是让服务起不来。

    填了 ``QINGDU_LLM_TIMEOUT=abc`` 顶多这次调用超时策略不对，
    比整个服务不可用好得多。
    """
    try:
        value = float(raw) if raw else fallback
    except ValueError:
        return fallback
    return value if value > 0 else fallback


def _port(raw: str) -> int:
    """解析端口；非法值退回默认。"""
    try:
        value = int(raw) if raw else DEFAULT_PORT
    except ValueError:
        return DEFAULT_PORT
    return value if 1 <= value <= 65535 else DEFAULT_PORT


def load_settings(env_file: Path | None = None) -> Settings:
    """装载配置。

    :param env_file: 显式指定配置文件路径（测试用）。默认 ``qingdu-ai/.env``。

    变量名带 ``QINGDU_`` 前缀是为了避免和别的 Python 项目抢环境变量
    （比如 ``MODEL`` 这种名字在别的工具里太常见了）。

    **刻意不往 ``os.environ`` 里写**（很多实现会这么做）：那样就有了隐式全局
    状态，测试之间会互相污染，而且"这个值到底是文件来的还是环境来的"再也说不清。
    """
    file_vars = read_env_file(env_file)
    lookup_in = file_vars if file_vars is not None else {}

    names = (
        "QINGDU_LLM_BASE_URL",
        "QINGDU_LLM_API_KEY",
        "QINGDU_LLM_MODEL",
        "QINGDU_LLM_TIMEOUT",
        "QINGDU_HOST",
        "QINGDU_PORT",
    )
    values: dict[str, str] = {}
    from_file: list[str] = []
    for name in names:
        value, came_from_file = _lookup(name, lookup_in)
        values[name] = value
        if came_from_file:
            from_file.append(name)

    return Settings(
        base_url=values["QINGDU_LLM_BASE_URL"],
        api_key=values["QINGDU_LLM_API_KEY"],
        model=values["QINGDU_LLM_MODEL"],
        timeout_seconds=_positive_float(
            values["QINGDU_LLM_TIMEOUT"], DEFAULT_TIMEOUT_SECONDS
        ),
        host=values["QINGDU_HOST"] or DEFAULT_HOST,
        port=_port(values["QINGDU_PORT"]),
        # 文件存在但为空 -> 路径照报（用户需要知道"服务确实读了，是文件空的"）
        env_file=str(env_file if env_file is not None else env_file_path())
        if file_vars is not None
        else "",
        from_file=tuple(from_file),
    )
