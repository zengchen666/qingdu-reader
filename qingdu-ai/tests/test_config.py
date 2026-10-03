"""配置装载测试 —— ``.env`` 解析、优先级、容错。

这一层的 bug **全都是静默的**：键名读错一个字符、BOM 没处理干净、
优先级反了 —— 服务照样启动，只是 key 读不到，然后用户看到
"未配置模型 API"，去怀疑自己的 key。所以这里的每条规则都要钉住。
"""

from __future__ import annotations

import os

from qingdu_ai.config import (
    DEFAULT_HOST,
    DEFAULT_PORT,
    DEFAULT_TIMEOUT_SECONDS,
    env_file_path,
    load_settings,
    parse_env_text,
    read_env_file,
)


def write_env(tmp_path, text: str, *, encoding: str = "utf-8"):
    """写一个临时 .env，返回路径。"""
    path = tmp_path / ".env"
    path.write_text(text, encoding=encoding)
    return path


# ==================== 文本解析 ====================


class TestParseEnvText:
    def test_键值对(self):
        assert parse_env_text("A=1") == {"A": "1"}

    def test_忽略空行与整行注释(self):
        text = "\n# 这是注释\nA=1\n\n   \n#B=2\n"
        assert parse_env_text(text) == {"A": "1"}

    def test_支持_export_前缀(self):
        # 有人习惯把 .env 写成 shell 脚本的样子
        assert parse_env_text("export A=1") == {"A": "1"}

    def test_剥掉值两侧的成对引号(self):
        assert parse_env_text('A="http://x/v1"') == {"A": "http://x/v1"}
        assert parse_env_text("A='http://x/v1'") == {"A": "http://x/v1"}

    def test_引号不成对时不剥(self):
        # 单个引号是值的一部分，不该乱猜
        assert parse_env_text('A="abc') == {"A": '"abc'}

    def test_等号周围的空白被吃掉(self):
        # 🔴 从 PowerShell 复制粘贴时最容易带进来的就是尾随空格，
        # 而 `sk-xxx ` 会让 401 变成一个极难查的问题
        assert parse_env_text("  A  =  sk-xxx  ") == {"A": "sk-xxx"}

    def test_没有等号的行被跳过而不是报错(self):
        # 一个手写笔误不该让服务起不来
        assert parse_env_text("随便写点什么\nA=1") == {"A": "1"}

    def test_空键被跳过(self):
        assert parse_env_text("=1") == {}

    def test_行内注释不剥除_已知限制(self):
        # 文档里写明了这个限制。理由：# 是合法 URL 片段字符，
        # 猜错的代价是"值莫名多了几个字符"，比不支持更难查。
        assert parse_env_text("A=1 # 注释") == {"A": "1 # 注释"}

    def test_值里可以有等号(self):
        assert parse_env_text("A=a=b=c") == {"A": "a=b=c"}

    def test_重复键后者生效(self):
        assert parse_env_text("A=1\nA=2") == {"A": "2"}

    def test_只有注释的行不算键(self):
        assert parse_env_text("# A=1") == {}


# ==================== 文件读取 ====================


class TestReadEnvFile:
    def test_文件不存在返回_None(self, tmp_path):
        assert read_env_file(tmp_path / "没有这个文件") is None

    def test_空文件返回空字典(self, tmp_path):
        # 空字典（而不是 None）是有意义的区别：说明"服务确实读了，
        # 只是文件里什么都没写"，启动提示要据此给出不同的话。
        assert read_env_file(write_env(tmp_path, "")) == {}

    def test_容忍_UTF8_BOM(self, tmp_path):
        # 🔴 这条是本文件里最重要的一条。
        # Windows 记事本存 UTF-8 会加 BOM（EF BB BF），用 utf-8 读会得到
        # "\ufeffQINGDU_LLM_BASE_URL" 这个键 —— 名字差一个不可见字符，
        # 于是**整份配置静默失效**，而肉眼检查文件内容完全正常。
        path = write_env(tmp_path, "QINGDU_LLM_MODEL=deepseek-chat")
        raw = path.read_bytes()
        path.write_bytes(b"\xef\xbb\xbf" + raw)

        parsed = read_env_file(path)
        assert parsed == {"QINGDU_LLM_MODEL": "deepseek-chat"}

    def test_编码坏了作废而不是抛异常(self, tmp_path):
        # 二进制垃圾（比如误存成 UTF-16）不该让服务起不来：
        # 用户看到的会是一堆 Python 栈，根本想不到是自己那个文件的问题。
        path = tmp_path / ".env"
        path.write_bytes(b"QINGDU_LLM_MODEL=\xff\xfe\x00abc")
        assert read_env_file(path) is None

    def test_默认路径是_qingdu_ai_上一级(self):
        # 用 __file__ 定位而不是当前工作目录 —— 从哪个目录敲命令都能找到
        assert env_file_path().name == ".env"
        assert env_file_path().parent.name == "qingdu-ai"


# ==================== 优先级 ====================


class TestPrecedence:
    def test_环境变量盖过文件(self, tmp_path, monkeypatch):
        path = write_env(tmp_path, "QINGDU_LLM_MODEL=来自文件")
        monkeypatch.setenv("QINGDU_LLM_MODEL", "来自环境")

        s = load_settings(env_file=path)
        assert s.model == "来自环境"
        assert "QINGDU_LLM_MODEL" not in s.from_file

    def test_环境变量为空白时回落到文件(self, tmp_path, monkeypatch):
        # 🔴 刻意与 python-dotenv 的"存在即优先"不同。
        # `set QINGDU_LLM_API_KEY=` 这种空值几乎总是误设；若把它当成
        # "显式禁用"，用户会遇到"我明明写了 .env 却不生效"。
        # 想禁用的人删掉 .env 即可 —— 两种失败模式的代价不对等。
        path = write_env(tmp_path, "QINGDU_LLM_MODEL=来自文件")
        monkeypatch.setenv("QINGDU_LLM_MODEL", "   ")

        s = load_settings(env_file=path)
        assert s.model == "来自文件"
        assert "QINGDU_LLM_MODEL" in s.from_file

    def test_from_file_如实报出文件提供的变量(self, tmp_path):
        path = write_env(
            tmp_path,
            "QINGDU_LLM_BASE_URL=https://api.deepseek.com/v1\n"
            "QINGDU_LLM_API_KEY=sk-xxx\n",
        )
        s = load_settings(env_file=path)
        assert set(s.from_file) == {"QINGDU_LLM_BASE_URL", "QINGDU_LLM_API_KEY"}

    def test_同时存在时能说清_key_不是文件给的(self, tmp_path, monkeypatch):
        # 用户改了 .env 却不见效，最常见的原因就是环境变量把它盖住了。
        # 启动提示要能指出这一点，否则只能靠猜。
        path = write_env(tmp_path, "QINGDU_LLM_API_KEY=sk-文件里的")
        monkeypatch.setenv("QINGDU_LLM_API_KEY", "sk-环境里的")

        s = load_settings(env_file=path)
        assert s.api_key == "sk-环境里的"
        assert "QINGDU_LLM_API_KEY" not in s.from_file
        # 但文件本身是读到了的，路径要照报
        assert s.env_file.endswith(".env")


# ==================== 装载结果 ====================


class TestLoadSettings:
    def test_什么都没有时是未配置态(self, tmp_path):
        s = load_settings(env_file=tmp_path / "不存在")
        assert s.llm_state == "not-configured"
        assert s.env_file == ""
        assert s.from_file == ()
        assert s.host == DEFAULT_HOST
        assert s.port == DEFAULT_PORT

    def test_完整配置是_ready(self, tmp_path):
        path = write_env(
            tmp_path,
            "QINGDU_LLM_BASE_URL=https://api.deepseek.com/v1\n"
            "QINGDU_LLM_API_KEY=sk-xxx\n"
            "QINGDU_LLM_MODEL=deepseek-chat\n",
        )
        s = load_settings(env_file=path)
        assert s.llm_state == "ready"
        assert s.configured

    def test_只缺_key时单独报出来(self, tmp_path):
        # ready / no-api-key / not-configured 三态必须分得开：
        # 界面据此给不同提示（"去填 key" vs "去填地址"）
        path = write_env(
            tmp_path,
            "QINGDU_LLM_BASE_URL=https://api.deepseek.com/v1\n"
            "QINGDU_LLM_MODEL=deepseek-chat\n",
        )
        assert load_settings(env_file=path).llm_state == "no-api-key"

    def test_空文件也照报路径(self, tmp_path):
        # 用户需要知道"服务确实读了，是文件空的"，
        # 否则会怀疑路径不对、去改别的地方
        path = write_env(tmp_path, "")
        s = load_settings(env_file=path)
        assert s.env_file == str(path)
        assert s.llm_state == "not-configured"

    def test_非法超时退回默认而不是崩(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_LLM_TIMEOUT=abc")
        assert load_settings(env_file=path).timeout_seconds == DEFAULT_TIMEOUT_SECONDS

    def test_非正超时退回默认(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_LLM_TIMEOUT=0")
        assert load_settings(env_file=path).timeout_seconds == DEFAULT_TIMEOUT_SECONDS

    def test_合法超时生效(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_LLM_TIMEOUT=30")
        assert load_settings(env_file=path).timeout_seconds == 30.0

    def test_非法端口退回默认(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_PORT=abc")
        assert load_settings(env_file=path).port == DEFAULT_PORT

    def test_越界端口退回默认(self, tmp_path):
        for bad in ("0", "99999", "-1"):
            path = write_env(tmp_path, f"QINGDU_PORT={bad}")
            assert load_settings(env_file=path).port == DEFAULT_PORT, bad

    def test_合法端口生效(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_PORT=8123")
        assert load_settings(env_file=path).port == 8123

    def test_host_可改(self, tmp_path):
        path = write_env(tmp_path, "QINGDU_HOST=0.0.0.0")
        assert load_settings(env_file=path).host == "0.0.0.0"

    def test_不往_os_environ_里写(self, tmp_path, monkeypatch):
        # 刻意不注入 os.environ：那样会有隐式全局状态，
        # 测试互相污染，且"值到底哪来的"再也说不清。
        path = write_env(tmp_path, "QINGDU_LLM_MODEL=deepseek-chat")
        monkeypatch.delenv("QINGDU_LLM_MODEL", raising=False)

        load_settings(env_file=path)
        assert "QINGDU_LLM_MODEL" not in os.environ
