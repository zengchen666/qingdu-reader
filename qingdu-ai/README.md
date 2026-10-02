# 轻读 AI 服务（qingdu-ai）

轻读阅读器的 AI 问答后端。给它一个问题，它基于**轻读传来的原文片段**作答，
并给出可点击核对的引用出处（哪本书、第几章、第几段）。

服务**只读**：不改写原文、不续写、不存取轻读的书库数据库。

---

## 它和轻读是什么关系

```
轻读（Java/JavaFX）  ──HTTP/JSON──>  qingdu-ai（Python/FastAPI）
· 读 TXT、切章节                   · 片段去重 / 章节打散 / 截断
· FTS5 检索（已验证零假阳性）        · 组织提示词（含防幻觉约束）
· 按段落切片段                       · 调大模型
                                   · 校验模型返回的引用编号
```

**分工的理由**：原文在用户本机的 TXT 里，定位靠章节字节偏移。
让 Python 自己去读文件，就等于把已验证的解析引擎复制一份 —— 所以原文一律由轻读喂过来。

---

## 快速开始

```powershell
cd qingdu-reader\qingdu-ai

# 1. 装环境（只需一次）
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

# 2. 配模型（PowerShell）
$env:QINGDU_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:QINGDU_LLM_API_KEY  = "sk-你的key"
$env:QINGDU_LLM_MODEL    = "deepseek-chat"

# 3. 起服务
.\.venv\Scripts\python.exe -m qingdu_ai
```

看到 `http://127.0.0.1:8000` 就成了。轻读会自动探测这个端口。

### 配置项

| 环境变量 | 必填 | 说明 |
|---|---|---|
| `QINGDU_LLM_BASE_URL` | 是 | OpenAI 兼容接口地址，如 `https://api.deepseek.com/v1` |
| `QINGDU_LLM_API_KEY` | 是 | API key |
| `QINGDU_LLM_MODEL` | 是 | 模型名，如 `deepseek-chat` |
| `QINGDU_LLM_TIMEOUT` | 否 | 超时秒数，默认 90 |
| `QINGDU_HOST` / `QINGDU_PORT` | 否 | 监听地址，默认 `127.0.0.1:8000` |

**三家都能接**（只要是 OpenAI 兼容接口）：DeepSeek、通义、本地 Ollama。
本机 Ollama：`BASE_URL=http://localhost:11434/v1`，`API_KEY` 随便填非空值。

### 没配 key 会怎样

**服务照常启动**，`/api/health` 返回 `llm: "no-api-key"`，问答返回 503。
这是刻意的：如果缺 key 就退出进程，轻读只会探测到"连接被拒绝"，
没法区分"你忘了开服务"和"你忘了填 key" —— 两个完全不同的原因给同一句提示。

---

## 端点

### `GET /api/health`

```json
{ "ok": true, "llm": "ready", "version": "0.4.0" }
```

`llm` 三态：`ready` / `no-api-key` / `not-configured`。
注意 `ok` **恒为 true** —— 服务活着是它的事，没配 key 是配置问题。
置 `ok=false` 会让轻读把 AI 入口整个禁用，而用户其实只需要填个 key。

### `POST /api/ask`

```json
{
  "question": "药老在斗破苍穹里第一次出现是第几章",
  "chunks": [{
    "bookId": "b1", "bookTitle": "斗破苍穹",
    "chapterIndex": 12, "chapterTitle": "第十三章 陨落的天才",
    "paragraphIndex": 0, "text": "……"
  }],
  "bookIds": ["b1"],
  "topK": 8
}
```

响应：

```json
{
  "answer": "药老在本卷第十三章首次现身[1]。",
  "citations": [{
    "index": 1, "bookId": "b1", "bookTitle": "斗破苍穹",
    "chapterIndex": 12, "chapterTitle": "第十三章 陨落的天才",
    "paragraphIndex": 0,
    "ref": "《斗破苍穹》第 13 章第 1 段",
    "excerpt": "……"
  }],
  "coverage": { "searchedBooks": 1, "indexedBooks": 1, "hits": 3 },
  "elapsedMs": 1234,
  "reason": "ok"
}
```

字段名一律 **camelCase**（对齐轻读侧的 Jackson）。
⚠️ Pydantic 默认不匹配的多余字段会被**静默丢弃**，所以这个对齐是硬要求，
`tests/test_retrieval.py::TestCamelCaseContract` 专门钉住它。

### 错误分类

| 情况 | HTTP | `reason` | 界面提示 |
|---|---|---|---|
| 服务没起 | 连接失败 | — | "AI 服务未启动，请先运行 python -m qingdu_ai" |
| 没配 key | 503 | — | "未配置模型 API key" |
| 召不回片段 | 200 | `no-chunks` | "没找到相关原文，换个说法试试" |
| 引用编号全无效 | 200 | `invalid-citations` | "模型没能给出可核对的出处" |
| 模型调用失败 | 502 | — | "模型调用失败，检查网络或稍后重试" |
| 正常 | 200 | `ok` | 答案 + 可点击引用 |

**"召不回"和"引用无效"是 200 而不是 4xx**：它们不是调用失败，
而是有效结论 —— 库里确实没有能回答这个问题的原文。

---

## 防幻觉：两道闸

这是 RAG 最重要的一环，也是面试必问。

**第一道：提示词**（`prompts.py`）写死三条 —— 只能依据原文、
每个结论必须带 `[n]`、原文没提就说不知道。
第三条最容易被忽略：不给模型"我不知道"这个出口，它会觉得"不回答"是任务失败，
从而硬凑一个带引用的答案。

**第二道：服务端校验**（`llm.py::validate`）不信任模型的编号：

| 模型行为 | 处理 |
|---|---|
| 编号全部不在片段集合内（含 `[100]` 这类越界） | `answer=null`，判为无法回答 |
| 编号部分有效 | 保留有效部分，**删掉无引用的句子** |
| 引用了有效编号但内容其实来自别的片段 | ⚠️ 机器拦不住，靠用户核对原文 |

第二行"删掉无引用的句子"是刻意加严的：一段模型用自身知识补出来的文字
混在有出处的答案里，**危害比整段拒答更大** —— 用户会以为整段都有出处。

⚠️ 引用校验**只能拦形式错误，拦不住内容错位**。这是它的真实边界。
引用的价值是**让错误可被发现**，不是保证不出错。

---

## 片段重排：为什么 Python 还要再排一次

它**不是**再做一次检索（FTS5 是唯一有检索语义的地方，在轻读侧）。
它做三件轻读不方便做的事：

1. **去重** —— 轻读可能对同一段落重复命中
2. **章节打散** —— 同章最多取 2 段，避免挤掉其他章
3. **截断到 topK** —— 控制 token 预算（单片段上限 1200 字）

打散是**软上限**：若唯一一章的片段数少于 `topK`，会全部保留 ——
没有其他章时就不存在"被挤掉"，白扔已召回的原文才是浪费。

---

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest
```

37 条用例，覆盖：

- **引用编号校验** —— `[99]` 被过滤、越界高位编号、无引用句子被删、引用能映射回真实片段
- **错误分类** —— 无 key 返 503（不是 500）、召不回返 `answer=null` 而非编一段
- **重排** —— 去重、章节打散、轮转补齐、顺序可复现
- **契约** —— camelCase 收发
- **健康检查** —— 三态回报，且 `ok` 恒为 true

---

## 这一版不做什么

- **向量检索 / rerank** —— 留 v0.5，v0.4 只做关键词召回。
  问"他怎么变强的"这种不含专有名词的问题会召不回，此时明确告诉用户，
  不硬凑答案
- **流式输出（打字机）** —— 先跑通再优化
- **多轮对话** —— 先把单轮问答的引用链路做扎实
- **AI 改写 / 续写正文** —— 这是阅读器，不是写作工具
