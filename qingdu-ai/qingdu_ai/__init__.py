"""轻读 AI 问答服务。

职责边界（这一点是整个设计的核心，务必守住）：

* **轻读（Java）** 是数据的主人 —— 读 TXT、切章节、跑 FTS5 检索、切段落片段，
  然后把命中的原文片段通过 HTTP 传过来。
* **qingdu-ai（Python）** 是无状态的服务 —— 不碰文件系统、不碰 SQLite，
  只做「片段重排 → 组织提示词 → 调模型 → 校验引用编号」。

为什么不这么分？因为原文在用户本机的 TXT 里，定位靠 `qingdu-core` 算出的
章节字节偏移；让 Python 重实现一遍解析引擎，等于把一份已验证的代码抄第二遍。
"""

# 🔴 版本号有两份：这里的 __version__ 与 pyproject.toml 的 version。
# 本机实测过 importlib.metadata 单一源头的写法，不可用：
#   ① 改了 pyproject 不会自动刷新已安装的元数据（仍报旧版本）；
#   ② 绿色版用户直接跑源码、没 pip install，importlib.metadata 会抛
#      PackageNotFoundError —— 服务根本起不来。
# 所以只能写死两份，升版时两处一起改。
# （Java 侧同理，共四处：5 个 POM、package.ps1 的 $Version、ReaderView.VERSION、本文件）
__version__ = "0.4.2"

__all__ = ["__version__"]
