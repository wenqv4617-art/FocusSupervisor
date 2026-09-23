# FocusSupervisor

AI 监督自律 App。当前处于**阶段五**：固定的签名密钥（新包可以直接覆盖安装，不用再
卸载重装）、时间感知与监督时间线（每一次拦截、放行、待办、消息改动都带时间记下来，
并进入 AI 的上下文）。

---

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 工程骨架、仿微信界面、CI 打包 | 已完成 |
| 二 | 权限引导、动态白名单、全屏遮罩、TodoList 数据地基 | 已完成 |
| 三 | DataStore 持久化、OpenAI 兼容通信层、AI 配置中心 | 已完成 |
| 四 | 对话链路、人设、分层记忆、指令上屏、TodoList 注入 | 已完成 |
| 五 | 固定签名密钥、时间感知、监督时间线 | 当前 |

## 交付物一览

| 交付项 | 位置 |
| --- | --- |
| GitHub Actions 自动打包 debug APK | `.github/workflows/build_apk.yml` |
| 权限与组件声明、包可见性 `<queries>` | `app/src/main/AndroidManifest.xml` |
| 领域契约：会话模型 | `domain/model/ChatModel.kt` |
| 领域契约：待办 / 豁免 / 权限 / 播报 | `domain/model/PolicyModels.kt` |
| 策略仓库接口（同步判定 + Flow 暴露 + 播报通道） | `data/repository/AppPolicyRepository.kt` |
| 策略仓库的 DataStore 实现 | `data/repository/DataStoreAppPolicyRepository.kt` |
| Preferences DataStore 读写与 JSON 编解码 | `data/datastore/AppPreferencesDataSource.kt` |
| AI 配置仓库（预设 / 选中项 / 生效配置） | `data/repository/AiConfigRepository.kt` |
| OpenAI 兼容客户端（拉模型 / 测连接） | `core/network/OpenAiCompatibleClient.kt` |
| AI 端点与预设的领域契约 | `domain/model/AiConfigModels.kt` |
| AI 配置中心（ModalBottomSheet） | `ui/settings/AiConfigSheet.kt` |
| 配置中心的 ViewModel | `ui/settings/AiConfigViewModel.kt` |
| 依赖容器（手写 Service Locator） | `core/AppContainer.kt` |
| 权限检查与系统设置跳转 | `core/permission/PermissionManager.kt` |
| 全屏防沉迷遮罩 | `ui/overlay/LockOverlayController.kt` |
| 无障碍服务拦截闭环 | `service/FocusAccessibilityService.kt` |
| 消息气泡与系统胶囊 | `ui/components/MessageBubbles.kt` |
| 底部「+」功能面板 | `ui/components/PlusActionPanel.kt` |
| 权限 / 待办与白名单面板 | `ui/components/PolicyDialogs.kt` |
| 仿微信聊天主界面 | `ui/chat/ChatScreen.kt` |
| 状态持有者（service ↔ UI 的唯一汇合点） | `ui/chat/ChatViewModel.kt` |
| 对话编排（提示词 → 模型 → 指令 → 上屏） | `core/ai/ConversationEngine.kt` |
| 提示词组装（前置提示 / 人设 / 记忆 / 待办 / 白名单 / 近况 / 时间） | `core/ai/PromptAssembler.kt` |
| AI 指令协议解析 | `core/ai/AiCommandParser.kt` |
| 向量与关键词打分 | `core/ai/TextVectorizer.kt` |
| 人设仓库（含头像落盘） | `data/repository/PersonaRepository.kt` |
| 对话仓库 | `data/repository/ConversationRepository.kt` |
| 记忆仓库（三层 + 提升 + 向量索引） | `data/repository/MemoryRepository.kt` |
| 人设 / 记忆管理面板 | `ui/settings/PersonaSheet.kt`、`ui/settings/MemorySheet.kt` |
| 领域契约：监督时间线 | `domain/model/TimelineModels.kt` |
| 时间线仓库（投递即返回的写入 + DataStore 持久化） | `data/repository/TimelineRepository.kt` |
| 时间叙述器（相对时间 / 日历天 / 时段 / 今日统计） | `core/time/TimeNarrator.kt` |
| 时间线面板（按天分组） | `ui/settings/TimelineSheet.kt` |
| **固定的签名密钥**（公开的调试密钥，见「签名」一节） | `app/debug.keystore` |
| 启动图标生成器 | `tools/generate_icons.mjs` |

## 技术栈

- Kotlin 2.0.21 / Jetpack Compose（BOM 2024.12.01）/ Material 3
- Preferences DataStore 1.1.7（持久化）+ OkHttp 4.12.0（网络）
- AGP 8.7.3，Gradle 8.11.1，JDK 17
- `compileSdk = 35`，`minSdk = 26`，`targetSdk = 35`
- Clean Architecture 分层：`domain` / `data` / `core` / `ui` / `service`
- 单向数据流：`ChatViewModel` 持有 `StateFlow<ChatUiState>`，界面只发意图

---

## 拦截闭环是怎么走的

```
  窗口变化事件（系统回调，主线程）
        ↓
  取出前台包名 ────→ AppPolicyRepository.isAppWhitelisted()      ← 同步判定
        │                    │
        │                    ├─ 命中常驻白名单（桌面/输入法/电话/系统界面/自身）
        │                    ├─ 命中运行时解析出的设备关键应用
        │                    └─ 命中未过期的临时豁免
        │
        ├─ 放行 → 撤掉可能存在的遮罩
        │
        └─ 拦截 → LockOverlayController.show()   全屏 #0A0A0A 遮罩
                   AppPolicyRepository.publishNotice()   插入居中系统胶囊
                        ↓
                   ChatViewModel 订阅 notices → 会话里多出一条
                   「[系统] 启动未受豁免应用 [包名]，已执行压制」

  遮罩期间按下返回键
        ↓
  onKeyEvent 吞掉按键（DOWN + UP）──→ performGlobalAction(GLOBAL_ACTION_HOME)
```

### 三个关键设计决定

**1. 判定必须同步，所以仓库是内存实现 + 接口**

`onAccessibilityEvent` 跑在主线程上，回调每迟到一帧，用户就多看到一帧目标应用的内容。
DataStore 是异步 API，在这里挂起就等于放行。所以正确形态本来就是「内存里保一份权威
快照，判定走同步读；持久化只是快照的备份」。阶段三接上 DataStore 时兑现了这一点：
`DataStoreAppPolicyRepository` 实现同一个接口，上层（无障碍服务）一行都没改。

数据流是这样的 ——
```
  DataStore（磁盘，唯一权威）
       │  Flow 持续监听（后台协程）
       ▼
  内存快照  MutableStateFlow<List<...>>
       │  同步 .value 读取
       ▼
  isAppWhitelisted()   ← 无障碍服务在主线程直接调，零挂起、零 IO

  写路径：调用方 → 原子 edit 事务 → DataStore → Flow 回环 → 内存快照
```
**内存快照只由 Flow 回环写，写路径不直接改内存。** 看起来比「同时写内存」慢，但那点
延迟在毫秒级，肉眼不可见；而省掉乐观写入就省掉了「磁盘写失败但内存以为成功了」这类
最难查的不一致。单一事实来源比省一毫秒重要得多。

**2. 系统常驻白名单是「静态清单 + 运行时解析」两层**

拦掉桌面就回不了家，拦掉输入法就打不了字，拦掉电话就可能漏掉急救来电。
所以除了写死的常见包名清单，还在运行时向 PackageManager 问出这台设备**真正的**
桌面（谁响应 HOME）、拨号器（默认拨号器 + 所有能处理 `DIAL/tel` 的应用）、
输入法（用户启用的每一套），两层取并集。清单里为此声明了对应的 `<queries>`，
刻意**不申请 `QUERY_ALL_PACKAGES`**。

**3. 遮罩的不可绕过性来自三件事，而不是一件事**

- 窗口没有 `FLAG_NOT_TOUCHABLE`，根视图 clickable 且显式消费触摸 → 触摸不下传；
- 底色是完全不透明的 #0A0A0A，Android 12 的 untrusted touch 保护会掐死任何
  穿过不透明悬浮窗的触摸；
- 界面上**没有任何可点控件**，解除只能靠代码 `dismiss()` 或按返回键回桌面。

---

## 怎么拿到 APK

**不需要在本地装 Android SDK**，APK 由 GitHub Actions 构建。

1. 推到 `main`，或在仓库页面进 **Actions → Build Debug APK → Run workflow**；
2. 等构建变绿；
3. 在那次 run 页面底部的 **Artifacts** 里下载 `FocusSupervisor-debug-apk`。

**artifact 只保留 1 天**（个人免费账号的存储算在配额里）。要留的包自己下载下来。

本地复现（需要自备 Android SDK）：

```bash
./gradlew assembleDebug --stacktrace
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 签名：为什么新包能直接覆盖安装

**Android 只允许同一签名的包覆盖安装。** AGP 默认会在**每台构建机**上现生成一把调试
密钥，于是 CI 每次产出的 APK 签名都不同，覆盖安装必然报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` —— 用户看到的现象就是「每次更新都得先卸载」，
而卸载会清空全部数据（对话、记忆、时间线全没）。

所以仓库里提交了一把**固定的**调试密钥 `app/debug.keystore`，`debug` 变体用它签名：

| 项目 | 值 |
| --- | --- |
| 文件 | `app/debug.keystore`（PKCS12） |
| alias / 口令 | `androiddebugkey` / `android`（改动见 `app/build.gradle.kts` 顶部常量） |
| 证书 SHA-256 | `0B:76:30:21:61:73:15:58:FE:2F:82:07:8C:35:4C:7A:5C:DB:E5:6C:AC:59:B4:1B:0E:72:28:3F:AC:C4:43:CE` |

CI 每次构建后都会跑一次 `apksigner verify --print-certs` 并把这个指纹打进日志，
所以「这次用的是不是那把固定密钥」在日志里可以直接核对。

**它是一把公开密钥，不提供任何保密性。** 口令就是 Android 约定的 `android`，
仓库里任何人都能拿它签出一个系统愿意当作升级包接受的 APK。它只适合自用与内测，
**不适合上架**。如果哪天要正式发布，请按下面切换到独立的发布密钥：

1. 生成一把新密钥：`keytool -genkeypair -v -keystore release.jks -storetype PKCS12
   -alias <alias> -keyalg RSA -keysize 2048 -validity 10950`；
2. 把 keystore base64 之后连同口令存进仓库 Secrets（**不要**提交进仓库）；
3. 让 CI 把它解码到某个路径，并导出 `FOCUS_KEYSTORE_PATH` / `FOCUS_KEYSTORE_PASSWORD`
   / `FOCUS_KEY_ALIAS` / `FOCUS_KEY_PASSWORD` 四个环境变量 —— `app/build.gradle.kts`
   已经支持这套变量，检测到之后 release 构建会自动改用这把密钥；
4. 换密钥的那一次仍然要**先卸载再安装**（签名变了），此后就一直能覆盖安装。

## 首次安装后的五步

1. 打开应用 → 「+」→ **权限检查**，把 **无障碍服务** 与 **悬浮窗** 两项开掉
   （入口上有红点就说明还没配齐）；
2. 回应用 → 「+」→ **强制锁定测试**，确认遮罩能盖满全屏、5 秒后自动解除；
3. 「+」→ **AI 配置**，选一个预设（DeepSeek / 本地 Ollama / OpenAI），填 Key，
   点 **拉取** 选模型，再点 **测试连接**。**保存与测试是两件事**：测试不动当前配置、
   也不关面板，结果就留在面板里；要生效得自己点保存。配对成功会在会话里留下一条
   `[系统] AI 终端握手成功，当前模型：…`；
4. 「+」→ **向量模型**（可选，但强烈建议）：它和对话端点是**分开**的两份配置。
   DeepSeek 没有公开的 embeddings 接口，把两者合在一份配置里，等于逼用户为了用上
   记忆功能而把对话模型也换掉；
5. 「+」→ **待办与白名单**，看一眼当前的待办和豁免情况。之后随时可以打开
   「+」→ **时间线**，看这段时间到底拦了什么、放行了什么。

之后打开任何不在白名单里的应用，都会被全屏遮罩压下；按返回键回到桌面。
所有配置（白名单、待办、AI 端点、对话、记忆、时间线）都会持久化，杀进程重开不会丢。
消息气泡、系统胶囊、时间线上的每一条记录**都带时间**，跨天的会显示成「昨天 21:30」。

## 怎么重新生成图标

```bash
npm install sharp
node tools/generate_icons.mjs
```

## AI 配置中心

底部「+」→ **AI 配置**，自底向上滑出。四段结构对应四个问题：

| 区域 | 内容 |
| --- | --- |
| 预设栏 | 横向切换预设，支持新建（克隆当前草稿）与删除（内置预设不可删） |
| 端点 | Base URL、API Key（可切换明文/密文） |
| 模型 | 手填，或点「拉取」让端点自己报出模型列表后就地选择 |
| 温度 | 滑块 0.0 ~ 1.5，步进 0.1 |

**Base URL 只认一条规则**：以 `/v1` 结尾就用原样，否则补一个 `/v1`。所以
`https://api.deepseek.com` 与 `https://api.deepseek.com/v1` 都能用。刻意不做
「智能识别是否已有版本号」—— 像 `/api/v1`、`/openai/v1` 这类路径，任何猜测都会在
某一家网关上翻车，而用户看到拼错的 URL 改一下就行，看到自作聪明的 URL 连从哪改起
都不知道。

**网络错误一律被翻译成中文**，界面上不会出现 `SocketTimeoutException`：
401 → 「401 无效的 API Key」、超时 → 「连接超时：… 在 30 秒内没有响应」、
404 → 「404 路径不存在，请检查 Base URL 是否包含 /v1」，并尽量附带服务端自己返回的
`error.message`。失败时面板保持打开，错误显示为一行低饱和红字。

**关于明文存储 Key**：现在把 Key 明文写进应用私有目录的 DataStore。更安全的
EncryptedSharedPreferences 依赖 Android Keystore，而它在部分定制 ROM 上会随机失效，
失效的后果是「用户的 Key 悄悄读不出来了」—— 对监督类应用来说比明文更糟。所以选明文，
并在界面上明确告知。

**关于明文 HTTP**：只放行 `127.0.0.1` / `localhost` / `10.0.2.2` 三个回环地址
（见 `res/xml/network_security_config.xml`），云端端点必须走 HTTPS。局域网里的
`http://192.168.x.x` 不在放行之列。

## AI 怎么真的做事

模型只能输出文本，所以约定了一套**结构化指令**。它在回复末尾另起一行输出：

```
[[cmd:{"type":"whitelist","package":"com.tencent.mm","minutes":10,"reason":"回工作消息"}]]
```

应用把它解析出来、**从可见文本里删掉**、执行、再以一条居中的系统胶囊上屏：
`[系统] AI 已放行 微信 · 10 分钟 · 理由：回工作消息`。
用户永远看不到那串 JSON。

**指令集是白名单式的，不是「模型让系统干什么就干什么」**：

| 能做 | 不能做 |
| --- | --- |
| 开临时豁免（上限 60 分钟） | 开永久豁免 |
| 撤销临时豁免 | 碰设备关键应用（桌面 / 输入法 / 电话） |
| 写待办、勾选待办 | 改人设、前置提示、API 配置 |
| 写记忆 | 打开任意 URL 或启动任意应用 |

上限由 `AiCommandLimits` 一处定义；解析时就把模型的请求夹到合法区间，
不依赖调用方记得校验。

## 记忆是怎么分层的

```
  写入（用户 / AI）
       │
       ▼
  短期记忆 ──被召回 3 次──▶ 长久记忆
       │                        │
       └────────┬───────────────┘
                ▼
     向量索引（记忆条目 + 对话原文共用同一个向量空间）
                │
                ▼
     召回：余弦相似度 Top-K ──▶ 注入提示词
```

判据是**被召回次数**，不是创建时间、也不是用户手动标星：时间筛不掉一次性噪音，
而用户恰恰是记不住的那个人。反复被检索到才是「这件事对他真的重要」的客观证据 ——
每一次检索都源于一句真实的当前输入。所以 `MemoryRepository.recall()` 是唯一一个
**读操作会写数据**的方法，那是设计要求，不是副作用。

没有配向量模型时不会失灵，只会变差：文档照样进索引，召回退回关键词打分
（中文按字符二元组），提升机制照常工作。补上向量模型后会自动把积压的补上。

**向量存储**：归一化 → 8bit 量化 → Base64。一个 1536 维向量从约 14KB 压到约 2KB。
不这么做的话，500 条文档就是 7MB 的 JSON，而 DataStore 每次写入都要重写整份 ——
那是能把手机卡出白屏的量级。

## 时间感知：监督者得知道「什么时候」

「监督」如果只看当前状态（白名单里有什么、待办还没完成），是没有判断依据的 ——
它不知道这些状态**是什么时候变成这样的**。所以除了「现在是什么样」，还要记下
「什么时候发生了什么」。

### 记什么

`TimelineEvent` 是一条带时间的记录，上限 300 条，落在 DataStore 的 `timeline_json`。
写入点全部收在仓库层，所以 **AI 触发的和用户手点的是同一份记录**：

| 类型 | 什么时候写 |
| --- | --- |
| 拦截未豁免应用 | 无障碍服务每次压下遮罩（同一应用连续锁定只记一次） |
| 放行 / 收回豁免 | 豁免真的写进磁盘之后才记（写失败不记，免得 AI 以为放行了） |
| 记下待办 / 完成待办 | 待办仓库写入成功时 |
| 写入记忆 / 记忆沉入长久层 | 记忆仓库写入、以及反复召回触发提升时 |
| 修改消息 / 删除消息 | 用户长按改写了已发出的话 —— 事后修改说过的话，是监督里该留痕的事 |
| 强制锁定测试 | 界面上手动触发时 |

`TimelineRepository.record()` 是**投递即返回**的非挂起方法：它最主要的调用方是
无障碍服务的主线程回调，那里不能为了记一条日志而挂起。代价是并发投递时落盘顺序
不保证，所以读取时按 `atMillis` 排序。

### 怎么进上下文

提示词里新增三段（顺序见 `PromptAssembler` 的类注释）：

```
  # 最近发生的事（倒序）   48 小时内最多 14 条，例：昨天 21:12 拦截未豁免应用：小红书
  # 现在                  2026-09-23 周三 21:14（晚上）
                          距上一次对话：12 分钟前
                          今天到目前为止有 8 条消息
                          今天发生的事：拦截 7 次 · 放行 2 次
  # 时间感                上面这些时间该怎么用（深夜劝睡、逾期追问、反复被拦先问清…）
```

另外**每一轮对话消息前面都会加上 `[MM-dd HH:mm]`**。没有这一层，整段历史在模型
眼里就是「刚刚连续发生的」，它会把三天前的一句抱怨当成当下的情绪。

时间说法由 `core/time/TimeNarrator.kt` 统一生成，提示词和界面共用同一套规则 ——
否则会出现「界面上说 3 分钟前、AI 以为隔了一小时」这种对不上的情况。

### 用户能看到什么

「+」→ **时间线**，按日历天分组（今天 / 昨天 / 09-21 周一），天内的记录由新到旧，
「今天」那一组顶上还带一句统计。这个面板是用户唯一能验证「AI 到底知道些什么」的
地方 —— 一个不透明的监督者是不可信的。

## 阶段边界（现在还没有的东西）

- **没有流式输出**：对话是一次性等模型生成完再上屏，没有逐字效果；
- **没有「盘问」**：TodoList 和时间线都进了提示词，但还没有「到点主动问进度」的
  定时逻辑 —— 现在只有用户开口，AI 才会用上时间感；
- **待办仍是样例数据**：仓库里预置了两条，只为让面板有东西可展示；
- **注视监控未实现**：`FocusMonitorService` 能正确进入前台态，但不会被自动拉起；
- **向量索引有硬上限**：最近 300 条对话原文参与语义召回，更早的仍在聊天记录里可翻看。
  要突破这个上限，应该把索引挪出 DataStore（独立文件或 Room），而不是把数字调大；
- **时间线也有上限（300 条）**：它是「近期证据」不是档案 —— 需要长期记住的东西
  由记忆三层承载。同样地，要突破就把它挪出 DataStore；
- **没走上架签名**：CI 用的是仓库里那把公开的调试密钥（见「签名」一节），
  发布密钥的钩子已经留好但没有配 Secrets；
- **没有单元测试**：编解码是纯函数可直接测，但 `org.json` 在 JVM 单测里不可用 ——
  要补测试得先换成 kotlinx.serialization。
