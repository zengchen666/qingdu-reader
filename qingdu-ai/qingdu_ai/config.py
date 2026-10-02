"""运行配置 —— 全部来自环境变量，不读任何配置文件。

**为什么不用 `.env` 自动加载？**
轻读是 GUI 程序，用户双击 exe 启动；Python 服务通常是手动敲命令起的。
如果服务依赖一个藏在某个目录里的 `.env`，用户改了 key 之后根本不知道要重启什么。
所以这里只认**进程环境变量**，并在 `/api/health` 里如实回报当前处于哪种状态
（`ready` / `no-api-key` / `not-configured`），让界面能把"没配 key"和
"服务没起来"这两件事区分开。

**为什么没有 key 也要能启动？**
如果缺 key 就直接退出进程，轻读探测 8000 端口会得到"连接被拒绝"，
只能提示"服务未启动，请运行 python -m qingdu_ai" —— 而真实原因是"你忘了填 key"。
两个完全不同的原因给出同一句提示，是很难排查的体验。
"""

from __future__ import annotations

import os
from dataclasses import dataclass

#: 服务默认监听地址。与轻读侧探测的端口必须一致（见 ReaderView 的 AI 服务探测）。
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8000

#: LLM 调用超时（秒）。设成 90 秒的理由：一次问答要送 8 个片段进去，
#: 加上中文 token 化偏慢，30 秒在慢网络下会误报失败。
DEFAULT_TIMEOUT_SECONDS = 90.0


@dataclass(frozen=True)
class Settings:
    """一份不可变的配置快照。"""

    base_url: str
    api_key: str
    model: str
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS

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


def _env(name: str) -> str:
    """读环境变量，缺失或全是空白时返回空串。

    用 ``strip()`` 是为了挡住从 PowerShell 复制粘贴时带进来的尾随空格 ——
    ``$env:KEY = " sk-xxx"`` 这种尾随空格会让 401 变成一个极难查的问题。
    """
    raw = os.environ.get(name, "")
    return raw.strip()


def load_settings() -> Settings:
    """从环境变量装载配置。

    变量名带 ``QINGDU_`` 前缀是为了避免和别的 Python 项目抢环境变量
    （比如 ``MODEL`` 这种名字在别的工具里太常见了）。
    """
    timeout_raw = _env("QINGDU_LLM_TIMEOUT")
    try:
        timeout = float(timeout_raw) if timeout_raw else DEFAULT_TIMEOUT_SECONDS
    except ValueError:
        # 填了非法数字不该让服务起不来 —— 退回默认值即可，
        # 顶多这次调用慢一点，比整个服务不可用好。
        timeout = DEFAULT_TIMEOUT_SECONDS
    if timeout <= 0:
        timeout = DEFAULT_TIMEOUT_SECONDS

    return Settings(
        base_url=_env("QINGDU_LLM_BASE_URL"),
        api_key=_env("QINGDU_LLM_API_KEY"),
        model=_env("QINGDU_LLM_MODEL"),
        timeout_seconds=timeout,
    )
