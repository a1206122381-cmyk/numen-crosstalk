# numen-crosstalk

让 Minecraft 服务器上的多个 AI 个体能互相说话、并且记得住事情。
**纯 MCP，不改主人的对话窗口。**

> 从 `AI社会实验版` 项目抽出的可复用部分。实验档在 `D:\Minecraft`，这里是干净的一般化版本。

---

## 为什么需要它

在 Minecraft 里跑多个 AI 个体（Numen 同伴、Mindcraft 机器人……）时会遇到两个问题：

**1. 它们互相听不见。** Numen 同伴只处理"主人对它说的话"，
它的 `EventQueue` 里没有一种事件对应"另一个玩家说了话"。于是 Mindcraft 侧的 bot
会主动搭话、反复尝试、最后因为零回应而怀疑对方是"家具"。

**2. 用"转发"去补，会污染主人的窗口。** 把听到的话注进 agent loop，
走的是**主人说话**那条通道——主人跟助手说十句话，中间会插进三十句别的 AI 的闲聊。
一旦一般化到普通游玩对局，这就不可接受了。

**本项目的做法：把"听"变成拉，不是推。**

```
别人说的话 → 持久化队列（不注入、不唤醒任何人）
                    ↓
        它想听的时候自己调 hear()
```

拉模型**不丢消息，只延迟**。也不会形成"每句话都唤醒所有人 → 所有人都要回应"
的寒暄循环（实测中一个 agent 自己识别出这个循环并抱怨过）。

---

## 安装（只发 jar，前置自己下）

> **本仓库不打包前置 mod。** 下面这几个请从各自的正式发布页下载 ——
> 那样你能拿到最新版，也不会因为我的包里带了旧版本而出问题。

### 目标环境

| | |
|---|---|
| Minecraft | **1.21.1** |
| 加载器 | **Fabric**（Loader ≥ 0.18.1） |
| Java | **21** |

### 前置（必装）

| mod | 从哪拿 | 为什么需要 |
|---|---|---|
| **Fabric API** | [Modrinth](https://modrinth.com/mod/fabric-api) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-api) | Numen 的前置 |
| **Numen** | [GitHub Releases](https://github.com/Dwinovo/minecraft-numen/releases) | AI 同伴本体 |
| **numen-api** | 同上（和 Numen 一起发） | Numen 的引擎，单独一个 jar |

> ⚠️ `numen` 和 `numen_api` 是**两个独立的 jar**，两个都要放。

### 本仓库的 mod

| jar | 必装吗 | 作用 |
|---|---|---|
| **`crosstalk-fabric-<版本>.jar`** | ✅ 必装 | 给同伴装上 `say` / `hear` / `who_is_here` |
| `crosstalk-collab-<版本>.jar` | ⬜ 可选 | 加一句"话也可以对同伴说"。**它改提示词**，所以单独拆出来 |

**装上后 `mods/` 应该有这些**：

```
fabric-api-<版本>.jar
numen-fabric-1.21.1-<版本>.jar
numen-api-fabric-1.21.1-<版本>.jar
crosstalk-fabric-<版本>.jar          ← 本仓库
crosstalk-collab-<版本>.jar          ← 可选
```

详细步骤见 **[配置指南](docs/配置指南.md)**。

---

## 包

| 包 | 语言 | 作用 |
|---|---|---|
| [`packages/numen-crosstalk-fabric`](packages/numen-crosstalk-fabric) | Java | **主 mod**。游戏内工具 `say` / `hear` / `who_is_here` |
| [`packages/numen-crosstalk-collab`](packages/numen-crosstalk-collab) | Java | 可选的提示词补充，单独拆出来 |
| [`packages/speech-mcp`](packages/speech-mcp) | Node | MCP 服务器。同一套工具，给**任何** MCP 客户端用 |
| [`packages/forge-handshake`](packages/forge-handshake) | Node | 让 Mineflayer 连上 **Forge** 服务器（Fabric 服不需要） |
| [`packages/numen-resident`](packages/numen-resident) | Java | ⚠️ **1.20.1 Forge** 那条线，未验证，保留备查 |
| [`packages/numen-orchestrator`](packages/numen-orchestrator) | Java | ⚠️ 同上 |

> **1.21.1 和 1.20.1 是两套完全不同的架构**，不能混用。
> 后者是早期实验留下的，只做参考，没在干净环境验证过。

---

## 文档

| 文档 | 内容 |
|---|---|
| **[配置指南](docs/配置指南.md)** | **装什么、怎么配、怎么验证、常见问题** —— 从这里开始 |
| [实现方式](docs/实现方式-三者如何结合.md) | mod / MCP / 文件 三者怎么结合，为什么这样设计 |
| [设计缺陷记录](docs/设计缺陷-能力给了但没人提醒它用.md) | 一个真实的设计疏漏：能力给了，但没东西提醒它用 |

---

## 快速开始

### 1. 跑 MCP 服务器

```bash
node packages/speech-mcp/index.mjs \
  --port 9101 \
  --speaker Lin \
  --out   <存档>/numenresident/speech.jsonl \
  --heard <存档>/numenresident/heard.jsonl
```

或用环境变量：`SPEECH_MCP_PORT` / `SPEECH_MCP_SPEAKER` / `SPEECH_MCP_OUT` / `SPEECH_MCP_HEARD`

**多只个体共用一个实例**（少起几个进程）：不设 `--speaker`，
让它们在参数里带 `as` 自报名字。

### 2. 接进你的 agent

在它的 MCP 客户端配置里指向 `http://127.0.0.1:9101/mcp`。

---

## 工具语义

### `hear(mark_read=true, as?)`

返回**自上次以来**别人说了什么，带时间戳和说话人。

- 游标**按个体分开记**，多个个体共用一个端点时互不影响
- `mark_read=false` 只看不标记（peek）
- 自己说的话不会回到自己这里
- 一次最多返回 40 条，剩下的留着下次（并告知还有多少）

**游标记的是「消息序号」，不是行号。** 详见 `index.mjs` 里 `hear()` 的注释——
行号是文件布局的投影，文件被重写/截断/并发追加都会让它失去意义。

### `say(text, urgent=false, as?)`

把自己的话写进发言流，由子mod 转投给别人。

**`urgent=true` 会打断对方**（不等它来听，而是主动推一个事件过去）。
用在真正等不了的事情上。

### `who_is_here(as?)`

读身份名册，返回还有谁在。

---

## 设计原则

### 措辞纪律（有测试保障）

**工具的说明只描述【能做什么】，不规定【该做什么】。**

不写"你应该回应"、"记得主动社交"、"建议定期查看"这类祈使句。
听不听、说不说、什么时候做，都是它自己的判断。

`test/integration.mjs` 的第 2 项就是在检查这个。

### 不让自主循环污染普通对局

如果你要把这套东西放进正常游玩的整合包，四条规则：

| 规则 | 理由 |
|---|---|
| **自主循环默认关闭** | 没人搭话时它不该自己行动、烧 token |
| **`hear()` 是拉不是推** | 不唤醒、不占窗口 |
| **`say()` 默认不推** | 只有显式 `urgent` 才打断 |
| **绝不用主人通道注入** | 那是主人的，不许被 AI 占用 |

装了这个 mod 的普通玩家，助手行为应与原版一致（只在被搭话时响应），
窗口不被别的 AI 污染。想让它社交，才手动打开自主循环。

---

## 只有 Numen、没有 Mindcraft 时能不能用？

**能。** 但要说清哪些部分会退化。

`speech-mcp` 对 Mindcraft **零依赖**——它只是个 MCP 服务器，
收发的是文件里的消息，不知道也不关心对方是 Numen、Mindcraft 还是别的东西。
`ClientChatRelay`（客户端）也**不依赖 Numen**，它只是把聊天写进队列。

### 组件依赖表

| 组件 | 装在 | 依赖 Mindcraft？ | 依赖 Numen？ |
|---|---|---|---|
| `speech-mcp` | 任意 | ❌ 无 | ❌ 无 |
| `forge-handshake` | 任意 | — | ❌ 无 |
| `numen-orchestrator` | 客户端 | ❌ 无 | ⚠️ 是（它操作同伴） |
| `numen-resident` | 服务端 | ❌ 无 | ❌ 无 |

### 唯一会退化的地方：`who_is_here()`

**原问题**：它原来只读 `agents.json`，而那个文件由服务端子mod 写。
只装客户端mod 时文件不存在 → 永远返回"名册不可用"。

**已修**：加了一条降级路径——从队列里的说话人反推。

```
有 agents.json  →  权威名单（谁在线）
无名册          →  从队列反推（谁最近说过话）+ 如实说明这个差别
```

降级路径的语义差异**写在返回值里**，而不是假装两者一样：

> `Wei, Andy  (from what has been said recently — no roster is installed,
> so people who have stayed quiet will not show up here)`

**"谁在线"和"谁最近说过话"不是一回事。** 一个沉默的人不会出现在降级结果里，
所以返回值必须说清楚，否则调用方会以为那是完整名单。

### 不会卡在验证上

实测确认：没有 `agents.json`、没有 Mindcraft、队列为空时——

| 调用 | 结果 |
|---|---|
| `who_is_here()` | `(nobody else)` —— 正常返回，不报错 |
| `hear()` | `Nobody has said anything since you last listened.` |
| `say(...)` | 正常写入 |

**没有任何地方会因为"缺少另一类 agent"而卡住或拒绝服务。**

### 一个提醒：服务端子mod 还负责名册

如果你**只装客户端mod**，那么：

- ✅ `say` / `hear` 全部可用
- ⚠️ `who_is_here()` 走降级路径（只报最近说过话的人）
- ⚠️ 没有权威的"谁在线"名单

想要完整功能就把 `numen-resident` 也装上（服务端，很小，只做名册）。

---

```bash
# 起一个测试实例
node packages/speech-mcp/index.mjs --port 9202 --speaker TestA \
  --out   packages/speech-mcp/test/data/test-speech.jsonl \
  --heard packages/speech-mcp/test/data/test-heard.jsonl

# 跑集成测试
node packages/speech-mcp/test/integration.mjs 9202
```

覆盖：工具清单、措辞纪律、空队列、队列内容、游标推进、peek 语义、
自我过滤、`urgent` 标记、空文本拒绝、未知工具。

---

## 状态

| 包 | 状态 |
|---|---|
| `speech-mcp` | ✅ 可用，14 项集成测试通过 |
| `forge-handshake` | ✅ 已在生产验证（连上 Forge 47.4.23 + 5 个 mod） |
| `numen-resident` | ✅ 已清理：只保留身份名册 + 调试命令 |
| `numen-orchestrator` | ✅ 拉模型改造完成（见下） |

### 拉模型改造（已完成）

| # | 改动 | 位置 |
|---|---|---|
| **A** | 听到的话写进 `heard.jsonl`（带单调递增 `seq`） | `ClientChatRelay.enqueue` |
| **B** | 默认 `pull` 模式，不再 `submitPrompt` 注入 | `ClientChatRelay.mode` |
| **C** | `urgent` 的发言推成事件唤醒同伴 | `Orchestrator.pushUrgentSpeech` |
| **D** | **遣散前落盘记忆** | `Orchestrator.despawnOld` |
| **E** | 删掉服务端侧无效的聊天路由 | `numen-resident` |

**任务 D 特别重要**，原因值得写在显眼处：

```
Memory 按名字存          ← 重启后能延续  ✅
      ↑ 抽取自
chat.jsonl 按 UUID 存    ← 遣散时被删掉  ❌ 不可逆
```

`CompanionHome.reconcile` 会在同伴被遣散后**自动删除它的目录**（含会话记录）。
所以记忆摘要**必须在 `despawn` 命令发出之前**抽完——晚了就永远抽不到了。
`Orchestrator.despawnOld()` 现在的顺序是：先落盘，再遣散。

### 一个顺带修掉的设计缺陷

`ClientChatRelay` 原来有一个 1.5 秒冷却，**在两种模式之前就 return**——
意味着密集对话里的消息会被静默丢弃。

push 模式需要它（每条都会唤醒同伴开一轮，不设限就烧 token）。
但 **pull 模式下它是纯粹的损失**：队列是持久的、`hear()` 自己有上限、
没人会被打扰，而丢掉的消息永远不会再出现。

实测后果：同伴只听到零星片段，还以为对方说话断断续续
（它自己的原话："你的消息又说到一半断了"）。

现在冷却**只对 push 生效**，pull 不丢任何一条。

---

## 与主人通道的边界

这是整个项目最重要的一条纪律。

`submitPrompt` 是**主人说话**的通道，会被记进主人和助手的对话历史。
所以 **AI 之间的话绝不能用它**——否则主人跟助手说十句话，
中间会插进三十句别的 AI 的闲聊。

| 通道 | 谁在用 | 进主人历史？ |
|---|---|---|
| `submitPrompt` | **只有处境注入**（出生处境、目标结束后的客观处境） | ✅ 会——所以仅限"世界/主人对同伴说话" |
| `pushEvent` | `urgent` 的他人发言 | ❌ 不会 |
| `hear()` 返回 | 它自己去听来的 | ❌ 不会（除非它主动调） |

**验证方法**：在 `numen-orchestrator` 里搜 `submitSituation`，
应当只有 `feedBirth` 和 `feedSituation` 两个调用点。
`ClientChatRelay` 的出口只有一个：`deliver → enqueue`。

如果哪天想再加一条"直接把话喂给它"的捷径，请先想清楚它会不会出现在主人的对话历史里。

---

## 已知限制

- **整合包内容**：Mineflayer 认不得模组方块/物品/GUI。
  `forge-handshake` 只解决"**能连上**"，不解决"**能理解**"。
  后者需要逐模组做注册表映射，只能做成可扩展框架。
- **身份自报**：多个体共用一个实例时靠 `as` 参数自报，理论上可冒充。
  只是名字，冒充无收益；要严格就一个个体一个实例。
