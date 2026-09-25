<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent

**你的手机，自动化。**

这是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的一个 fork，把原生 Android LLM 聊天客户端变成了真正的端侧智能体：80+ 设备工具、AI 自己编写的工作流、定时任务、AI 驱动的应用内浏览器、免 key 网页搜索、Linux 工作区、SSH、屏幕自动化、文件管理器、音乐播放器、语音转写、可下载的端侧大模型，以及远程 Telegram 机器人。全部按需开启。

<p>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/v/release/ExTV/rikkahub-agent?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/releases"><img src="https://img.shields.io/github/downloads/ExTV/rikkahub-agent/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/rikkahub-agent/stargazers"><img src="https://img.shields.io/github/stars/ExTV/rikkahub-agent?style=flat-square&color=yellow" alt="Stars" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
</p>

<a href="https://extv.github.io/rikkahub-agent/">官网</a> ·
<a href="https://github.com/ExTV/rikkahub-agent/releases/latest">下载</a> ·
<a href="#功能">功能</a> ·
<a href="#快速上手">快速上手</a> ·
<a href="#从源码构建">构建</a>

[English](README.md) | [简体中文](README.zh-CN.md)

</div>

---

## 它能做什么？

用日常语言告诉它要做什么。手机会在后台执行，你照常过你的生活。

> *"每个工作日早上 9 点，把我未读的 WhatsApp 汇总成一条 Telegram 消息。"*
> *"如果我家里服务器的磁盘快满了，提醒我一下。"*
> *"盯着我的通知。老板发来的消息就转发到 Telegram。"*
> *"找出我手机里提到'发票'的 PDF，把第一段念给我听。"*
> *"接下来的 4 小时里，每 30 分钟把 WhatsApp 顶部内容存到文件里，我下午好回顾。"*
> *"用 Termux 给我做一个网页，列出你会做的所有事，然后在浏览器里打开。"*
> *"晚上 7 点后我在家里 WiFi 下插上耳机时，开始播放我的晚间歌单。"*
> *"打开我路由器的管理页面，用保存的密码登录，告诉我现在哪台设备最占带宽。"*
> *"同时开两个调研：一个找这个月去东京最便宜的单程机票，另一个列出涩谷 100 美元以下的酒店。"*

上面每一句都能直接生效。

---

## 功能

### 设备控制

点击、滑动、滚动、输入、打开应用、调节亮度/音量、发通知、查看电量/WiFi/信号/位置/传感器、读取联系人和短信、发短信、设置壁纸、读写 NFC 标签、用 Android Keystore 签名和加密数据、访问外部存储与 SD 卡、管理 ZIP 压缩包。**80+ 工具**，全部内置在 Android 里。每个工具默认关闭，等你亲手打开。

### 工作流与定时任务

**工作流**——用自然语言描述触发器和动作：*"到家后把铃声关掉。"* 19 种触发器（WiFi、蓝牙、耳机、地理围栏、应用启动、通知、时间、充电、屏幕状态等）加 14 种条件（电量阈值、日出日落、星期几、前台应用、屏幕状态）决定何时触发。广播接收器按需注册，耗电保持在最低水平。

**定时任务**——按任意节奏运行任务：*"每周一早上 8 点"*、*"每两小时"*、*"下周五下午 3 点"*。能扛住重启和省电模式。可以让 AI 在触发时现场思考决策，也可以预置固定动作、不烧 token。

### Telegram 机器人

在任何地方和你的助手对话。发送问题、照片、PDF 或语音都可以。审批提示用简单的 Yes/No 按钮；AI 需要你提供信息时，会在聊天里弹出可点按的选项。超长消息以可下载文件的形式送达；消息连发会做限速，避免触发 Telegram 频率限制。

### 应用内浏览器

应用内置了一个真实浏览器。AI 会帮你点掉 cookie 弹窗、填搜索框、滚动页面，再把内容读给你听。全程在后台无头运行——没有可见窗口，没有截图流。内置正文提取和操作后差异对比，token 开销压到最低。20 个浏览器工具：导航、DOM/文本读取、cookie、对话框、视口控制、点击并读取等。

### 网页搜索与抓取

搜索开箱即用、无需 API key：默认是**内置**引擎（DuckDuckGo）；遇到反爬拦截时，熔断器会诚实地返回可重试的错误，而不是悄悄给出"无结果"。如果你想用自己的 key，引擎选择器里一共列了 19 个：Tavily、Exa、Brave、Perplexity、Jina、Firecrawl、SearXNG、Bing、Serper、Ollama 等，外加一个可以指向任意接口的自定义脚本引擎。

另外，助手可以直接抓取任何页面。**网页抓取与正文提取**默认开启（设置 → 搜索），不出现在每个助手的工具菜单里：

- `web_fetch` — 抓取页面、按响应 charset 解码、对长文档分页，避免撑爆上下文窗口
- `web_extract` — 基于 jsoup 的可读性提取，剥掉导航和样板内容，只留正文

两者都有 30 秒超时上限、读取有界的响应体（超大页面不会 OOM），并在 DNS 解析阶段就拦截指向内网地址的请求。

### 文件管理器

查找文件、读取、保存新文件、复制、移动、重命名、删除。*"找出我手机里所有提到'发票'的 PDF"* 一句话搞定。应用沙箱之外的系统目录一律禁止访问，你开口也没用。

### 工作区（Workspace）

手机上真实的 Linux 环境。AI 可以在里面跑 shell 命令、读写和打补丁文件，还能通过内置文件管理器浏览——带文本编辑器和图片/视频预览。可以通过系统文件选择器把设备上任何位置的文件复制进来。

长时间运行的任务可以跨轮次存活：`workspace_run_background` 启动开发服务器、安装或文件监视器并返回任务 id，`workspace_background_status` 轮询最近输出，`workspace_background_kill` 停止它。任务 id 按工作区隔离，删除工作区会连带杀掉它启动的所有进程。

### SSH

服务器信息保存一次即可。跑命令、传文件、拉备份、看磁盘、tail 日志——全在聊天里完成。可以向命令管道输入、写远程文件，或启动返回 PID 而不阻塞的长驻服务。WiFi 和蜂窝网络下都能用。

### 音乐与媒体

走 Android 标准媒体控制播放音乐：锁屏封面、耳机按键，一应俱全。暂停、继续、调音量——聊天里和 Telegram 里都能操作。队列通过快照兜底，强杀应用后也能恢复。

### Skills

丢一个 Markdown skill 文件进去，AI 就多了一套 playbook。内置目录附带二维码生成器、维基百科查询、钢琴、交互式地图等。默认启用两个 skill：一个常驻的智能体 playbook 和一个 OpenClaw 转换器。可以从 URL 添加 skill，或把 Markdown 文件分享进应用。

### 子智能体

长任务由主助手派发专注的子智能体到干净的侧上下文中运行，可选换用更小更便宜的模型。多个可以并行，每个结果汇总成一条摘要返回。`/stop` 一个动作级联取消所有活跃子任务。

### Doctor

内置健康检查。对权限、后台服务、数据库完整性、网络、Termux 和诊断做一轮全面体检。点"自动修复"可以授予权限、重启服务或重建搜索索引。也可以通过 Telegram 远程执行 `/doctor`。

### MCP 服务器

接入 [Model Context Protocol](https://modelcontextprotocol.io) 服务器后，AI 就能获得它们暴露的任何工具。AI 可以自己添加、更新和管理 MCP 连接——每一步都需要审批。

支持三种传输方式：
- **SSE**（`type: sse`）和 **Streamable HTTP**（`type: streamable_http`）用于远程服务器，内置 OAuth 2.1 授权
- **stdio**（`type: stdio`）用于跑在 [Workspace](#工作区workspace) Linux 环境里的本地 MCP 服务器——`command` + `args` 在工作区 rootfs 内执行，所以 `npx`、`uvx` 或一个 Python 脚本都能在设备上充当 MCP 服务器。stdio 服务器绑定固定工作区，只在该工作区就绪后启动，永远不需要 OAuth，命令参数会经过 hardline 命令守卫检查。

### 通知与外部触发

AI 可以读取、汇总和转发你指定的应用的通知，白名单初始为空。智能体发出的通知会深链回产生它的对话，冷启动时点一下就能打开完整回复。其它应用（Tasker、自动化工具）可以通过 External Automation Intent API 把任务交给智能体。

### 安全与隐私

三层防护：

1. **按助手开关** — 每个工具默认关闭，只开你要的。
2. **逐次审批** — 会改变状态的工具执行前先询问。
3. **HARDLINE 底线** — 真正危险的命令（清空数据、重启、fork 炸弹、破坏系统文件）无条件拦截。

密码和 API key 永远不会写进日志文件。云端备份跳过已保存的凭据。Telegram 机器人只响应白名单里的人。网页抓取在 DNS 解析阶段就拒绝解析到内网地址的请求，防止助手被诱导去探测你的局域网或云元数据端点。

---

## 这个 fork 有什么不同

这个 fork 在上游 RikkaHub 之上叠加了一个端侧智能体层。上文描述的是这个 fork 的整体功能集；本节记录与上游的差异（以 2.4.5 合并为基准）——既有上游没有的能力，也有在上游功能之上修改的行为。

### Fork 独有的能力（上游没有）

| 能力 | 带来的东西 |
|---|---|
| **内嵌 Termux 运行时** | 自包含的 Termux bootstrap 直接打进 APK（debug + release，aarch64 + x86_64），首次启动自动安装。`termux_run_command` 让 AI 在宿主系统上获得真实 Linux shell，不再需要单独的 Termux 应用。由 CI 构建、构建期内嵌。 |
| **定时智能体任务** | 上游提供的是基于 WorkManager 的 cron 调度器。这个 fork 重做了投递机制：任务分两种模式——`llm`（基于 prompt，`setExactAndAllowWhileIdle` 精确闹钟 + 前台化的 WorkManager）和 `direct`（预置 `actionsJson`，经 `setAlarmClock` 精确、用户可见地触发）。包含重启恢复、重放防护、补跑链和精确闹钟权限兜底。 |
| **stdio MCP 服务器** | 跑在 Workspace Linux 环境里的本地 MCP 服务器（`type: stdio`），与上游的 SSE/Streamable-HTTP 传输并存。见 [MCP 服务器](#mcp-服务器)。 |
| **ToolSearch** | `search_tools` + `ToolRegistry.search()`：多关键字 AND 匹配、相关性打分、拼写错误的 Levenshtein 模糊兜底，以及按分类浏览。让模型发现它不知道名字的能力。 |
| **系统提示词重写** | 重写了核心 `agent-core` skill（SOUL/HEARTBEAT/TOOLS.md），移除旧的 `autonomous-agent` skill，新增懒加载的 `code-agent` skill。初始提示词从约 5,137 token 降到约 3,155 token。 |
| **子智能体的必备工具** | 无论工具配置如何，`get_time_info` + `eval_javascript` 总是注入到每个助手（包括子智能体），基础需求不再需要 shell 兜底。 |
| **WebServerHealthWorker** | 对内嵌 Web 服务器做 30 分钟周期的健康探针，与 Telegram bot 的健康检查对齐。 |
| **CI 签名与发布流水线** | 固定的 debug 签名 keystore（经 GitHub Actions secrets）保证可原地升级；release APK 工作流；`build-info.txt` 携带版本 + 签名指纹 + bootstrap 哈希。 |
| **内嵌 Termux:API shim** | `TermuxApiServer`：内嵌运行时内的 `127.0.0.1` token 鉴权 IPC 端点，应答 `termux-*` 命令——notification、toast、vibrate、torch、battery-status、clipboard get/set、tts-speak、notification-remove、volume——不需要单独的 Termux:API 应用。 |
| **后台投递加固** | 保活服务 + ROM 检测（MIUI/ColorOS 类杀后台系统）+ 资格检查、wake-lock 看门狗覆盖和端到端加固，让定时任务和通知能扛住 OEM 激进的省电策略。 |
| **分享文件** | `share_file` 把任何文件交给 Android 系统分享面板。 |
| **懒加载工具声明** | 工具 schema 只在 `search_tools` 发现或首次执行后才进入请求的 `tools` 字段——首个 prompt 的 tools 负载从约 86KB 降到约 3KB。约 30 个工具的描述被精简，MCP 描述截断到 500 字符。 |

### 与上游的行为差异

- **浏览器永久无头运行** — 前台浏览器 Activity 路径已移除；每个浏览器会话都在后台 WebView 中运行。截图相关工具（`browser_screenshot`、`take_screenshot`、流式传输器）已**删除**——不要指望它们回来。保留 20 个浏览器工具（导航、DOM/文本、cookie、对话框、视口、点击并读取）。
- **移除了单轮墙钟限制** — 单个用户请求不再在累计 10 分钟工具执行后被强制结束。每个工具仍有自己的超时（外加生成循环里 300 秒的单工具执行上限），`maxSteps` 和循环守卫依然能拦住失控的轮次。
- **工作区（Linux）环境** — 功能一节中基于 proot 的工作区是上游功能，不是 fork 新增。这个 fork 在其之上构建：`ManagedWorkspaceProcess`（结构化 `command`+`args` 进程生成、进程树清理、生命周期锁注册）支撑着工作区内的 stdio MCP 服务器，后台任务管理也做了加固。
- **系统提示词里的工具指引** 是一行固定文案，指向 `search_tools`；不再注入任何硬编码的工具名。

### 合并后的修复（上游 2.4.5 → 本分支）

以下修复是这个 fork 在合并上游 2.4.5 之后特有的：

- **Room schema v29** — 2.4.5 合并意外盖了两个不同的 v28 schema（fork 的 `schedulePrecision` 列 vs 上游的查询索引），升级时 identity-hash 不匹配直接崩溃。升到 v29 并做了自动迁移；历史 `N.json` schema 保留。
- **WorkManager 前台服务声明** — `SystemForegroundService` 的 `specialUse` 声明在合并中丢失，导致每个定时任务都以 `foregroundServiceType 0x40000000 is not a subset of ...` 崩溃。已恢复并有 manifest 测试覆盖。
- **Cron 闹钟接收器** — `DirectCronAlarmReceiver`/`ExactCronAlarmReceiver` 的 manifest 声明在合并中丢失；没有它们，AlarmManager 广播无处投递，定时任务在后台永远不会触发。已恢复（含 `CronBootReceiver` 的重调度 action）并有 manifest 测试覆盖。

### 开源说明

这个 fork 自己新增的部分（stdio MCP、调度器重做、ToolSearch、内嵌 Termux、重写的 skills、CI 签名）是基于上游 AGPL-3.0 代码库之上的原创工作。见[致谢](#致谢)和[许可证](#许可证)。

---

## 快速上手

### 1. 安装

从 [Releases](https://github.com/ExTV/rikkahub-agent/releases/latest) 下载最新的 `*-release.apk`。允许安装未知来源应用，然后打开。

> **注意：** 如果你装过旧的 debug 构建，先卸载它——release 构建的签名不同。

> **从 `2.3.1-agent.0` 之前的版本升级？** 应用 ID 改成了 `excp.rikkahub`，所以这个 fork 可以和上游 RikkaHub 并存安装。迁移数据：打开旧应用 → 设置 → 备份 → 安装此版本 → 恢复备份。

### 2. 添加 LLM 提供商

**设置 → 提供商 → 任选一个 → 粘贴 API key。**

- **OpenRouter** — 一等支持：自动检测模型能力、定价和路由，主模型宕机、限流或拒绝时按顺序尝试备选模型列表
- **Codex** — 用 ChatGPT 账号登录（OpenAI 订阅走 OAuth）
- **Grok** — 用 xAI 账号登录（SuperGrok 或 X Premium+ 走 OAuth）
- **本地 · LiteRT** — 下载本地模型（Gemma、Qwen）。不需要 key、不需要网络。支持的设备上走 GPU 加速
- **AICore** — Pixel 8/9/10 用户可以启用 Gemini Nano 做端侧推理（目前需要 AICore Beta）

### 3. 开启你要的功能

**设置 → 助手 → 点进你的助手 → 本地工具** — 打开需要的分类。

如果你什么都不开，应用的行为和原版 RikkaHub 完全一样。

### 4. （可选）Telegram 机器人

1. 给 [@BotFather](https://t.me/BotFather) 发 `/newbot` 拿到 token
2. 给 [@userinfobot](https://t.me/userinfobot) 发 `/start` 拿到你的数字用户 ID
3. 告诉助手：*"帮我配置 Telegram 机器人。Token 是 `<token>`，我的用户 ID 是 `<id>`。设为默认聊天并启用。"*

---

## 环境要求

| | |
|---|---|
| **架构** | arm64 或 x86_64 |
| **Android** | 8.0+（API 26），目标 API 37 |
| **存储** | 约 80 MB |
| **LLM 提供商** | OpenAI、Google、Anthropic、OpenRouter、Codex、Grok、Ollama 或任何 OpenAI 兼容端点。也可以用 Google 账号登录代替 Gemini API key。或者 Pixel 8/9/10+ 上通过 AICore 使用 Gemini Nano |

---

## 语言

界面自带**英语、简体中文、繁体中文、日本語、한국어、Русский 和 العربية**。应用跟随系统语言，找不到对应语言时回退到英语。RTL 语言（阿拉伯语、波斯语、乌尔都语）在聊天中渲染正常——代码块保持 LTR。

---

## 从源码构建

需要 PATH 上有 [bun](https://bun.sh) 和 [pnpm](https://pnpm.io) —— bun 安装 web-ui 依赖，pnpm 打 web 包。

```bash
git clone https://github.com/ExTV/rikkahub-agent.git
cd rikkahub-agent
./gradlew :app:installDebug
```

---

## 致谢

站在巨人的肩膀上：

| 项目 | 作用 |
|---|---|
| [RikkaHub](https://github.com/rikkahub/rikkahub) | 本 fork 的上游聊天客户端 |
| [cron-utils](https://github.com/jmrozanec/cron-utils) | 调度器的 cron 解析器 |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | 端侧语音转文字（经由 Termux） |
| [Termux](https://github.com/termux/termux-app) | Shell + 包管理器 |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | 原生 SSH 客户端 |
| [FlorisBoard](https://github.com/florisboard/florisboard) | 配套 [agent-keyboard](https://github.com/ExTV/agent-keyboard) 的基础 |

这个 fork 与上游 RikkaHub 维护者没有任何隶属关系。底层聊天客户端、提供商抽象和 UI 设计的所有功劳属于上游团队。

---

## 许可证

GNU AGPL-3.0，继承自[上游](https://github.com/rikkahub/rikkahub)。见 [LICENSE](LICENSE)。
