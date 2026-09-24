# FocusSupervisor

AI 监督自律 App。当前处于**阶段九**：提示词工程、端侧识图与资源管理。

- 修掉了一个真实存在过的缺陷：AI 总是回答**上一轮**的内容；
- 提示词有了双轨预算（云端 10k / 本机端点严格 2048），并针对 Qwen、Llama、
  R1 思考模型、云端各写了一套适配；
- 识图多了一条完全本机的路：ML Kit 中文 OCR 把屏幕变成一句事实，
  **不看图的对话模型也能知道屏幕上发生了什么**；
- 模型资源（向量 / 识图）收进了「资源管理」一页；
- **端侧对话模型整条链路被移除**。它曾经能跑（四个 `.task` 模型、逐字输出、
  跑分卡片都在），但真机上试过之后结论很清楚：手机上跑得动的量级答得不够聪明，
  答得够聪明的量级跑不动。宁可删掉，也不留一个「装得上、用不下去」的功能。
  见「为什么删掉了端侧对话模型」一节。

---

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 工程骨架、仿微信界面、CI 打包 | 已完成 |
| 二 | 权限引导、动态白名单、全屏遮罩、TodoList 数据地基 | 已完成 |
| 三 | DataStore 持久化、OpenAI 兼容通信层、AI 配置中心 | 已完成 |
| 四 | 对话链路、人设、分层记忆、指令上屏、TodoList 注入 | 已完成 |
| 五 | 固定签名密钥、时间感知、监督时间线 | 已完成 |
| 六 | 小窗/画中画拦截、端侧注视监控（CameraX + ML Kit） | 已完成 |
| 七 | 主动盘问引擎、跨天机制、待办闭环 | 已完成 |
| 八 | 聊天页美化（主题 + 背景图 + 样式注入）、数据管理与分片备份、本地向量模型、面板淡彩重做 | 已完成 |
| 九 | 对话时序缺陷修复、双轨 Token 预算、模型个性化提示词、端侧识图、模型资源管理；端侧对话模型试做后移除 | 当前 |

## 交付物一览

路径全部相对仓库根，逐条核对过存在性。

### 构建与交付

| 交付项 | 路径 |
| --- | --- |
| 自动打包 debug APK（带签名校验，指纹打进日志） | `.github/workflows/build_apk.yml` |
| 模块配置：签名、abiFilters、依赖 | `app/build.gradle.kts` |
| 全工程唯一的版本号来源 | `gradle/libs.versions.toml` |
| 固定的调试签名密钥（公开，只为让覆盖安装可用） | `app/debug.keystore` |
| 权限、组件、包可见性 `<queries>` | `app/src/main/AndroidManifest.xml` |
| 无障碍运行期配置（含 canTakeScreenshot） | `app/src/main/res/xml/accessibility_service_config.xml` |
| 只放行回环地址的明文 HTTP | `app/src/main/res/xml/network_security_config.xml` |
| 启动图标生成器 | `tools/generate_icons.mjs` |
| 本地向量模型的词表（BERT WordPiece，30522 条，231KB，内置进 APK） | `app/src/main/assets/vocab.txt` |

### 领域契约（纯 Kotlin，不依赖 Android 框架）

| 交付项 | 路径 |
| --- | --- |
| 会话与界面状态模型 | `app/src/main/java/com/focussupervisor/app/domain/model/ChatModel.kt` |
| 待办 / 豁免 / 权限 / 播报 | `app/src/main/java/com/focussupervisor/app/domain/model/PolicyModels.kt` |
| 对话 / 向量 / 视觉三份独立端点配置 | `app/src/main/java/com/focussupervisor/app/domain/model/AiConfigModels.kt` |
| AI 结构化指令与上限 | `app/src/main/java/com/focussupervisor/app/domain/model/AiCommand.kt` |
| 记忆三层、召回结果、向量索引文档 | `app/src/main/java/com/focussupervisor/app/domain/model/MemoryModels.kt` |
| AI 与用户的人设 | `app/src/main/java/com/focussupervisor/app/domain/model/PersonaModels.kt` |
| 监督时间线的事件类型与容量取舍 | `app/src/main/java/com/focussupervisor/app/domain/model/TimelineModels.kt` |
| 注视监控配置与 VisionStatus | `app/src/main/java/com/focussupervisor/app/domain/model/GazeModels.kt` |
| 聊天页外观、样式注入的解析结果 | `app/src/main/java/com/focussupervisor/app/domain/model/AppearanceModels.kt` |
| 识图来源与两档端侧引擎（含「暂不可用」的原因） | `app/src/main/java/com/focussupervisor/app/domain/model/VisionModels.kt` |

### 数据层

| 交付项 | 路径 |
| --- | --- |
| Preferences DataStore 的进程内唯一入口 | `app/src/main/java/com/focussupervisor/app/data/datastore/AppPreferencesDataSource.kt` |
| 全部 JSON 编解码（向量为量化 + Base64） | `app/src/main/java/com/focussupervisor/app/data/datastore/PreferencesCodec.kt` |
| 策略仓库接口（同步判定 + Flow + 播报） | `app/src/main/java/com/focussupervisor/app/data/repository/AppPolicyRepository.kt` |
| 策略仓库实现（内存快照，供主线程同步读） | `app/src/main/java/com/focussupervisor/app/data/repository/DataStoreAppPolicyRepository.kt` |
| 端点配置仓库（对话 / 向量 / 视觉） | `app/src/main/java/com/focussupervisor/app/data/repository/AiConfigRepository.kt` |
| 对话仓库（含改写与删除留痕） | `app/src/main/java/com/focussupervisor/app/data/repository/ConversationRepository.kt` |
| 记忆仓库（三层 + 提升 + 向量索引） | `app/src/main/java/com/focussupervisor/app/data/repository/MemoryRepository.kt` |
| 人设仓库（含头像落盘） | `app/src/main/java/com/focussupervisor/app/data/repository/PersonaRepository.kt` |
| 监督时间线仓库（投递即返回的写入） | `app/src/main/java/com/focussupervisor/app/data/repository/TimelineRepository.kt` |
| 注视监控仓库（配置落盘 + 运行期状态） | `app/src/main/java/com/focussupervisor/app/data/repository/GazeRepository.kt` |
| 最近一次请求的缓存命中情况（纯内存） | `app/src/main/java/com/focussupervisor/app/data/repository/PromptCacheRepository.kt` |
| 设备关键应用与桌面/输入法的运行时解析 | `app/src/main/java/com/focussupervisor/app/data/repository/CriticalPackages.kt` |
| 首次启动的开场白（唯一一处示例内容） | `app/src/main/java/com/focussupervisor/app/data/mock/MockChatData.kt` |
| 聊天页外观仓库（主题 / 背景图落盘 / 样式文本） | `app/src/main/java/com/focussupervisor/app/data/repository/AppearanceRepository.kt` |
| 本地向量模型仓库（下载状态机 + 引擎加载） | `app/src/main/java/com/focussupervisor/app/data/repository/LocalModelRepository.kt` |
| 数据管理仓库（体量统计 + 导出 + 恢复的原子性） | `app/src/main/java/com/focussupervisor/app/data/repository/DataMaintenanceRepository.kt` |

### 能力层（core）

| 交付项 | 路径 |
| --- | --- |
| 手写 Service Locator + 后台任务编排 | `app/src/main/java/com/focussupervisor/app/core/AppContainer.kt` |
| 对话编排；也承载主动盘问的模型调用 | `app/src/main/java/com/focussupervisor/app/core/ai/ConversationEngine.kt` |
| 提示词组装：稳定段 + 每轮状态段（为前缀缓存而分） | `app/src/main/java/com/focussupervisor/app/core/ai/PromptAssembler.kt` |
| 指令协议解析（手写扫描，不用正则） | `app/src/main/java/com/focussupervisor/app/core/ai/AiCommandParser.kt` |
| 向量相似度与关键词兜底打分 | `app/src/main/java/com/focussupervisor/app/core/ai/TextVectorizer.kt` |
| 主动盘问引擎：触发 → 闸门 → 开口 | `app/src/main/java/com/focussupervisor/app/core/ai/ProactiveSupervisor.kt` |
| OpenAI 兼容客户端（对话 / 向量 / 视觉 / 缓存统计） | `app/src/main/java/com/focussupervisor/app/core/network/OpenAiCompatibleClient.kt` |
| 权限状态检查与系统设置跳转 | `app/src/main/java/com/focussupervisor/app/core/permission/PermissionManager.kt` |
| 时间叙述器与日历天判据 dayKey | `app/src/main/java/com/focussupervisor/app/core/time/TimeNarrator.kt` |
| 注视判定状态机（纯 Kotlin，可单测） | `app/src/main/java/com/focussupervisor/app/core/vision/GazeEstimator.kt` |
| CameraX + ML Kit 取帧分析 | `app/src/main/java/com/focussupervisor/app/core/vision/GazeAnalyzer.kt` |
| 截屏能力通道（无障碍 → 注视监控） | `app/src/main/java/com/focussupervisor/app/core/vision/ScreenCaptureProvider.kt` |
| 主动提醒的触达（通知 + 震动） | `app/src/main/java/com/focussupervisor/app/core/notify/ProactiveNotifier.kt` |
| 样式注入解析器（类 CSS 子集，全程手写扫描不用正则） | `app/src/main/java/com/focussupervisor/app/core/style/StyleInjector.kt` |
| 备份的正文编解码（JSON，复用 PreferencesCodec） | `app/src/main/java/com/focussupervisor/app/core/backup/BackupCodec.kt` |
| 备份打包与恢复（zip + 分片 + 目录枚举） | `app/src/main/java/com/focussupervisor/app/core/backup/BackupManager.kt` |
| 模型下载器（断点续传 + 进度流 + SHA-256 校验） | `app/src/main/java/com/focussupervisor/app/core/model/LocalModelDownloader.kt` |
| BERT WordPiece 分词器（中文按字、英文按最长匹配） | `app/src/main/java/com/focussupervisor/app/core/ai/WordPieceTokenizer.kt` |
| 本地向量引擎（ONNX Runtime + mean pooling + L2 归一化） | `app/src/main/java/com/focussupervisor/app/core/ai/LocalEmbeddingEngine.kt` |
| 五套模型个性化提示词档案 + Token 估算 + 双轨预算 | `app/src/main/java/com/focussupervisor/app/core/ai/prompt/PromptProfiles.kt` |
| 端侧识图引擎（ML Kit OCR + 启发式提炼） | `app/src/main/java/com/focussupervisor/app/core/vision/LocalVisionEngine.kt` |

### 系统服务

| 交付项 | 路径 |
| --- | --- |
| 拦截执行端（含小窗/画中画判定与截屏能力） | `app/src/main/java/com/focussupervisor/app/service/FocusAccessibilityService.kt` |
| 注视监控前台服务（camera 类型） | `app/src/main/java/com/focussupervisor/app/service/FocusMonitorService.kt` |

### 界面层

| 交付项 | 路径 |
| --- | --- |
| Application：容器与通知渠道 | `app/src/main/java/com/focussupervisor/app/FocusSupervisorApp.kt` |
| 唯一的 Activity | `app/src/main/java/com/focussupervisor/app/MainActivity.kt` |
| 仿微信聊天主界面（无状态） | `app/src/main/java/com/focussupervisor/app/ui/chat/ChatScreen.kt` |
| 状态持有者（service ↔ UI 的唯一汇合点） | `app/src/main/java/com/focussupervisor/app/ui/chat/ChatViewModel.kt` |
| 长按消息的操作 / 编辑 / 删除面板 | `app/src/main/java/com/focussupervisor/app/ui/chat/MessageActionSheets.kt` |
| 消息气泡与系统胶囊（都带时间） | `app/src/main/java/com/focussupervisor/app/ui/components/MessageBubbles.kt` |
| 底部「+」面板（9 个入口） | `app/src/main/java/com/focussupervisor/app/ui/components/PlusActionPanel.kt` |
| 权限检查 / 待办与白名单面板 | `app/src/main/java/com/focussupervisor/app/ui/components/PolicyDialogs.kt` |
| 头像组件 | `app/src/main/java/com/focussupervisor/app/ui/components/Avatar.kt` |
| 面板通用组件（标题 / 分组卡片 / 输入 / 按钮） | `app/src/main/java/com/focussupervisor/app/ui/settings/SheetComponents.kt` |
| AI 配置中心 | `app/src/main/java/com/focussupervisor/app/ui/settings/AiConfigSheet.kt` |
| AI 配置的状态与缓存命中显示 | `app/src/main/java/com/focussupervisor/app/ui/settings/AiConfigViewModel.kt` |
| 向量模型（独立配置） | `app/src/main/java/com/focussupervisor/app/ui/settings/EmbeddingSheet.kt` |
| 向量模型配置的状态 | `app/src/main/java/com/focussupervisor/app/ui/settings/EmbeddingViewModel.kt` |
| 人设管理 | `app/src/main/java/com/focussupervisor/app/ui/settings/PersonaSheet.kt` |
| 人设的状态与头像选择 | `app/src/main/java/com/focussupervisor/app/ui/settings/PersonaViewModel.kt` |
| 记忆管理 | `app/src/main/java/com/focussupervisor/app/ui/settings/MemorySheet.kt` |
| 记忆的状态与增删改 | `app/src/main/java/com/focussupervisor/app/ui/settings/MemoryViewModel.kt` |
| 时间线面板（按天分组） | `app/src/main/java/com/focussupervisor/app/ui/settings/TimelineSheet.kt` |
| 注视监控面板 | `app/src/main/java/com/focussupervisor/app/ui/settings/GazeSheet.kt` |
| 注视监控的状态与拉取模型 | `app/src/main/java/com/focussupervisor/app/ui/settings/GazeViewModel.kt` |
| 全屏防沉迷遮罩 | `app/src/main/java/com/focussupervisor/app/ui/overlay/LockOverlayController.kt` |
| 消息与待办的时间格式（跨天补日期） | `app/src/main/java/com/focussupervisor/app/ui/util/TimeFormat.kt` |
| 面板淡彩色板（薄荷 / 天青 / 藕荷 / 藕粉） | `app/src/main/java/com/focussupervisor/app/ui/theme/Palette.kt` |
| 聊天皮肤：与面板色板解耦的一层主题槽位 | `app/src/main/java/com/focussupervisor/app/ui/theme/ChatSkin.kt` |
| 三套预置主题与「主题 + 注入」的合成规则 | `app/src/main/java/com/focussupervisor/app/ui/theme/ChatPresets.kt` |
| 聊天页美化面板（预览 / 主题 / 背景 / 样式注入） | `app/src/main/java/com/focussupervisor/app/ui/settings/AppearanceSheet.kt` |
| 美化面板的状态持有者 | `app/src/main/java/com/focussupervisor/app/ui/settings/AppearanceViewModel.kt` |
| 数据管理面板（概览 / 导出 / 恢复确认） | `app/src/main/java/com/focussupervisor/app/ui/settings/DataManageSheet.kt` |
| 数据管理面板的状态持有者 | `app/src/main/java/com/focussupervisor/app/ui/settings/DataManageViewModel.kt` |
| 背景图裁剪（拖动 + 捏合缩放，自绘不引库） | `app/src/main/java/com/focussupervisor/app/ui/components/ImageCropScreen.kt` |
| 按路径异步加载图片（解码不进主线程） | `app/src/main/java/com/focussupervisor/app/ui/components/FileImage.kt` |
| 资源管理面板（向量 / 识图两类模型一页管完） | `app/src/main/java/com/focussupervisor/app/ui/settings/ResourceSheet.kt` |
| 资源管理面板的状态持有者 | `app/src/main/java/com/focussupervisor/app/ui/settings/ResourceViewModel.kt` |

---

## 技术栈

- Kotlin 2.0.21 / Jetpack Compose（BOM 2024.12.01）/ Material 3
- Preferences DataStore 1.1.7（持久化）+ OkHttp 4.12.0（网络）
- CameraX 1.4.2（取帧）+ ML Kit 人脸检测 16.1.7（模型打包版）
- ONNX Runtime 1.20.0（`onnxruntime-android`）：在手机上跑本地向量模型
- ML Kit 中文文本识别 16.0.1（打包版）：完全离线的屏幕文字理解
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

## 首次安装后的六步

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
   「+」→ **时间线**，看这段时间到底拦了什么、放行了什么；
6. 「+」→ **注视监控**（可选）：它会先要摄像头权限 —— 那是硬前提，Android 要求
   `camera` 类型的前台服务必须先拿到它。打开后会有一条常驻通知，正文跟着状态变
   （Android 12+ 状态栏还会亮绿点）。**截图上传默认关闭**，需要自己打开；打开前
   建议先把「+」→ 注视监控里的**视觉模型**配好（DeepSeek 不接受图片，要另配一家）。

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
（见 `app/src/main/res/xml/network_security_config.xml`），云端端点必须走 HTTPS。局域网里的
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

时间说法由 `app/src/main/java/com/focussupervisor/app/core/time/TimeNarrator.kt` 统一生成，提示词和界面共用同一套规则 ——
否则会出现「界面上说 3 分钟前、AI 以为隔了一小时」这种对不上的情况。

### 用户能看到什么

「+」→ **时间线**，按日历天分组（今天 / 昨天 / 09-21 周一），天内的记录由新到旧，
「今天」那一组顶上还带一句统计。这个面板是用户唯一能验证「AI 到底知道些什么」的
地方 —— 一个不透明的监督者是不可信的。

## 小窗与画中画：为什么原来拦不住

用户反馈：「只要一开小窗，遮罩就解除了」。根因**不在遮罩盖不住**（无障碍浮层在
层级上高于应用窗口，小窗也在它的覆盖范围内），而在**判定方式**：

```
  进小窗后：被拦应用的小窗还在播，但「顶层全屏窗口」变成了桌面
      ↓
  旧判定只问「前台是谁」→ 桌面在白名单里 → 撤掉遮罩
      ↓
  小窗里的应用从此无人看管
```

修法是把判定从「前台是谁」换成**「屏幕上有没有不该看的窗口」**：

| 改动 | 为什么 |
| --- | --- |
| 无障碍配置补 `typeWindowsChanged` | 小窗 / 分屏 / 画中画的进出**只发这一类事件**。少了它，用户一开小窗服务就收不到任何相关事件 |
| 遍历 `getWindows()` 做判定 | 不再看焦点，只看屏幕上有哪些 `TYPE_APPLICATION` 窗口 |
| 用 `isInPictureInPictureMode()` 单独认画中画 | 这类窗口通常**既不 active 也不 focused**，用焦点过滤正好会漏掉它 |
| 要求「占半屏或抢焦点」才拦 | `TYPE_APPLICATION` 里混着别的应用弹的 Toast。不设这条，别人弹条提示就能让你满屏黑一次 |
| 白名单分支也先扫一遍窗口 | 「刚发生事件的包在白名单里」只说明它自己没问题，不代表屏幕上没有别的 |

判定全程**同步**、不挂起（无障碍回调在主线程，挂起就等于漏帧）；窗口扫描有 250ms
去抖，但用的是 Handler 而不是协程 —— 躲开动画抖动，又不引入挂起。

## 注视监控：他到底在不在

在它之前，所有信号都是「他做了什么」（拦了哪个应用、说了什么），没有任何信号能
回答「他现在是不是正看着屏幕」。于是 AI 会对着一个早就走开的空房间说话。

### 端侧管道

```
  前置摄像头 ──CameraX──▶ 640x480 YUV 帧 ──每 0.7 秒取一帧──▶ ML Kit 人脸检测
                                                                   │ 欧拉角 + 睁眼概率
                                                                   ▼
                                                        GazeEstimator（去抖状态机）
                                                                   │
                        ┌──────────────────────────────────────────┼───────────────────┐
                        ▼                                          ▼                   ▼
                  状态（在看/没看/没人）              进入注视 → 截屏 → 视觉模型    疲劳里程碑
                        │                                          │                   │
                        └──────────▶ 面板 + 提示词 ◀──────────────┴───────────────────┘
```

**画面只在设备本地处理**：帧用完即弃，不落盘、不上传。ML Kit 的模型打在 APK 里，
不依赖 Google Play 服务在运行时下载（但它的支撑库仍有 `play-services-*` 传递依赖，
无 GMS 的机型需要实测一次）。

### 为什么后台也能用摄像头

Android 9 起后台应用不能访问摄像头，唯一例外是**带着 `camera` 类型前台服务运行**。
而这个前台服务必须**从用户可见的状态启动**（Android 12+ 限制后台启动前台服务），
所以入口只能是用户在界面上打开开关。换句话说：**要能在后台跑，就必须有一条用户
可见的常驻通知**。这不是设计选择，是平台硬性要求 —— 顺带也符合「摄像头在工作时
用户必须看得见」这条常识（Android 12+ 还会在状态栏亮一个绿点）。

通知正文跟着实际状态走（「画面里没有人」/「正在注视屏幕」），因为用户看到绿点时
唯一能确认「它在干什么」的地方就是那条通知。

### 判定：为什么不是「单帧说了算」

单帧判断一定不准：眨一下眼、偏一下头、光照晃一下，都会让结果在同一秒里翻好几次。
所以 `GazeEstimator` 是一台**去抖状态机**（纯 Kotlin，可单测）：

- 候选状态要**连续保持 1.5 秒**才真的切换（离开也一样，对称去抖）；
- 头部偏转容差默认 15°（`yaw`/`pitch`），眼睛睁开的概率只作为**附加**条件 ——
  拿不到概率时不能因此判成闭眼，否则会出现「所有帧都判为离开」这种彻底失效；
- 进入注视时按**冷却时间**（默认 3 分钟）决定要不要截屏上传。

### 隐私边界

| | 默认 | 说明 |
| --- | --- | --- |
| 判断「在看 / 没在看」 | 开启 | 全程端侧，不出设备 |
| 截屏 + 发给视觉模型 | **关闭** | 这是整个应用里唯一会把**屏幕内容**送出设备的功能 |

打开上传时的文案把话说全了：抖音、微信、相册里的内容都可能被一起发出去。
截图会压到 720 宽、JPEG 70 再以 data URL 内联发送 —— 不经过任何图床。

视觉模型是**独立的一份配置**（和向量模型一样），因为 **DeepSeek 不接受图片**：
混在一起等于逼用户为了用注视监控而换掉对话模型，那不是取舍，是功能互斥。

### 记什么

| 事件 | 时机 | 为什么是这个粒度 |
| --- | --- | --- |
| 开始注视屏幕 | 5 分钟最多一条 | 一天拿起手机几十次，全记会把时间线塞满 |
| 放下手机（带时长） | 单次注视 ≥ 30 秒 | 瞄一眼不留痕迹 |
| 连续注视提醒 | 20 / 30 / 45 / 60 / 90 / 120 分钟 | 唯一需要**当场告诉用户**的事件，同时发一条系统胶囊 |
| 视觉描述 | 进入注视且允许上传时 | 让 AI 知道「他在刷小红书」，而不只是「他在看」 |

另外 `VisionStatus` 带两个累计量：**本轮连续注视**与**本次运行累计注视**。它们会作为
易变段进入提示词 —— 至此 AI 才第一次能说出「你今天已经盯着屏幕三个小时了」。

## AI 主动盘问：他不在的时候，不说话

在它之前，AI 永远是被动的：只有用户开口，它才有机会判断。而监督里最该发生的事
（他连着硬扛一个应用、他盯着屏幕已经一小时）恰恰都发生在**用户不说话的时候**。

### 一条铁律

**只在他正在注视屏幕的时候开口。** 这不是一条附加规则，是整个引擎的设计前提。

不在场时说的话等于对着空房间说话：他看不到、不会回，这次模型调用还照付；更糟的是
等他十分钟后回到屏幕前，会话里躺着一句「你刚才为什么又打开小红书」—— 那件事早
过去了，这句话只会让他莫名其妙。

由此推出三条必须同时成立的推论：

| 推论 | 为什么 |
| --- | --- |
| **触发 ≠ 开口** | 触发源只登记「该问一次」进待问队列，真正开口由闸门决定 |
| **待问条目会过期**（默认 30 分钟） | 没有 TTL，队列会攒一堆陈年旧事，等他终于出现时一次问一个 |
| **注视监控没开 = 不主动盘问** | 没有摄像头就不知道他在不在。界面与这里都写明，否则用户会以为功能坏了 |

### 四类触发源

```
  RAMPAGE   10 分钟内同一个未豁免应用被拦 ≥3 次     他正在硬扛，人就在当场
  FATIGUE   连续注视屏幕达到 60 分钟                人就在屏幕前
  RETURNED  离开 ≥15 分钟之后又回来                 问「刚才去哪了」
  TODO_DUE  待办将在 30 分钟内到期且未完成          人可能不在，入队等
```

**RETURNED 不是「离开超过 15 分钟」。** 那件事发生时他人不在，闸门根本不会放行，
那条触发只会一路堆积到过期。正确的触发点是他**回来**的那一刻。

**FATIGUE 用 60 分钟而不是 45 分钟**：45 分钟那档已经由注视服务发了一条系统胶囊
（事实播报），如果盘问也在同一刻发出，用户会同时收到一句话和一条通知。错开之后形成
递进：20/30/45 分钟是「告诉事实」，60 分钟是「AI 真的开口」。

### 闸门与触达

四道闸门依次是：**正在注视** → 队列非空且未过期 → 15 分钟冷却 → 同一时刻只投递
一次。任何一道不通过就原样留在队列里等下一次机会（或过期消失）。

投递时：时间线留一条 `AI 主动盘问` → 短震一下 → 如果应用不可见，再弹一条通知
（点击直接回聊天页）。震动用固定长度的一次性震动，不走通知的默认震动 —— 后者在
「静音」类系统设置下会完全消失，而这个触觉反馈是「AI 在叫你」最直接的信号。

## 隔天机制：第二天哪些该翻页、哪些不该

判据是**日历天**，不是「满 24 小时」。昨晚 23:50 和今天 00:10 只差 20 分钟，但必须算
两天 —— 深夜恰恰是使用最密集的时段，按 24 小时判会把整段算进前一天，错得最难被发现。

| 跨天时 | 怎么做 | 为什么 |
| --- | --- | --- |
| 待问队列 | **清空** | 昨天攒下的「该问一次」今天问只会让人莫名其妙 |
| 今日累计注视 | **归零**；若仍在注视，本轮从零点重新起算 | 跨零点的那段属于昨天，而昨天的数字已被丢弃 |
| 连续注视时长 | **不重置** | 连续盯了 45 分钟就是 45 分钟，与有没有跨零点无关 |
| 盘问冷却 | **不重置** | 它是防刷机制，不是每日配额；零点刚过不该多一次盘问 |
| 待办 | **不清空、不重置** | 没完成就是没完成，只把「已问过临期」的标记清掉 |
| 今日拦截 / 放行统计 | 无需处理 | 本来就是按日历天现算的（`TimeNarrator.summarizeDay`） |

判据统一收在 `TimeNarrator.dayKey`，所有需要「每天重置一次」的地方都存一个 key 比对，
不会出现两处各写一套的情况。

## 聊天页美化：三套主题、一张背景图、一段样式

「+」→「聊天美化」。三块内容按「影响面从大到小」排：主题 → 背景 → 样式注入。

### 主题：换的不只是颜色

| 主题 | 底色 | 我的气泡 | 结构 |
| --- | --- | --- | --- |
| 跟随应用明暗 | 跟面板走 | 微信绿 + 深字 | 大圆角 + 头像 + 姓名 |
| 默认日间微信 | 中性灰 `#EDEDED` | 微信绿 `#95EC69` | 大圆角 + 头像 + 姓名 |
| 仿 iMessage | 纯白 | 蓝底白字 `#0B84FF` | 全圆角、**无头像无姓名**、时间戳居中 |
| 捡手机文学 | 蓝灰 `#B4C0CE` | 黄底深字 `#FFD75E` | 小圆角 + 圆头像、不显示姓名 |

注意第三、四行里的「无头像无姓名」「时间戳居中」。**只换颜色是做不出 iMessage 的** ——
用户一眼就知道那还是「一个染了蓝的微信」。所以皮肤里除了颜色还带圆角、头像与姓名
的开关、气泡最大宽度，这些一起构成「这套主题长什么样」。

### 主题和面板是两层，不是一套

面板（向量模型、记忆、AI 配置……）读的是 `FocusTheme.colors`，聊天页读的是
`ChatTheme.skin`。两者**故意不共用**：把气泡换成 iMessage 的蓝时，配置面板不该跟着变蓝。
皮肤通过 `LocalChatSkin` 只挂在聊天页那棵子树上。

### 背景图：选图 → 裁剪 → 透明度

1. 从系统相册选一张（`GetContent`，不需要任何存储权限）；
2. 全屏裁剪：拖动移动、双指捏合缩放，框里看到的就是聊天页上会看到的部分。
   裁剪框固定 0.62 宽高比 —— 宁可窄不要宽，宽的图铺上去会被裁掉上下，
   而人像与主体通常在画面中间偏上；
3. 透明度滑块：**下限 5% 而不是 0**。完全透明等于「我明明设了图却什么都看不到」，
   那是个只会让人以为坏了的状态。

原图会先降采样到最长边 1440 再裁剪，结果写进应用私有目录（`filesDir/chat_background.jpg`），
不会往相册里再复制一份。裁剪后先写临时文件再改名 —— 中途失败时用户原来那张图还在。

### 样式注入：先说清楚它不是什么

**Compose 没有 CSS。** 它是一棵强类型节点树，没有「样式表 + 文档树 + 选择器匹配引擎」
这套架构。想在 Compose 上做真正的 CSS 注入，等于在应用里重新实现一个 CSS 引擎。

所以这里实现的是一份**明确划定的子集**，而界面会把「哪句生效了、哪句没认出来、为什么」
逐条列出来 —— 用户写错时不需要猜：

```
.chat            { background: #EDEDED; }   /* 聊天区背景 */
.bubble-user     { background: #95EC69; color: #191919; }
.bubble-ai       { background: #FFFFFF; }
.system-pill     { background: #D6D6D6; color: #6E6E6E; }
.bubble          { border-radius: 14px; max-width: 72%; }
.avatar          { display: none; }        /* 藏掉头像 */
```

认得的属性只有 `background` / `color` / `border-radius` / `max-width` / `display`。
界面里有「复制模板」和「复制类名」两个按钮，模板是一份能直接改的完整示例。

**解析器全程手写扫描，一个正则都没用。** 这个项目已经在正则上栽过一次：Android 的
正则引擎是 ICU，它把 `\{.*?}` 里那个没有量词开头的 `}` 判成语法错误，而同样的写法在 JVM
上完全合法 —— 结果是「本机编译通过、真机一启动就崩」。CSS 的解析天然满是花括号，
继续用正则就是再赌一次同一种运气。`indexOf('{')` 在两个平台上行为完全一致。

---

## 数据管理：备份、分片、恢复

「+」→「数据管理」。

### 备份包里是什么

```
focus-backup-20260924-0130.zip
├── manifest.json          清单：时间、版本、条数、分片数、正文摘要
├── data.json              全部数据（白名单/待办/预设/人设/记忆/向量/对话/时间线/外观）
└── chat_background.jpg    自定义聊天背景（设过才有）
```

正文是 **JSON 而不是 DataStore 的 protobuf 二进制**。二进制块没法校验、没法手看，
而且和 DataStore 的内部实现绑死。JSON 可读、可校验，恢复时也能走正常的写入口。

### 超过 8MB 自动分片

向量是最占地方的：384 维浮点，一条记忆序列化后几个 KB，上千条记忆的索引就是好几 MB。
一次性写一个几十 MB 的文件在手机上会遇到两个很现实的问题：**写一半没空间**
（走到最后一步才失败，用户白等半分钟还什么都没得到），以及**传不出去**
（聊天软件对单个附件都有大小限制）。所以超过 8MB 就切成 `.part001`、`.part002`……
分片切的是**已经压好的 zip 字节**，所以每一片都不是有效 zip，必须按顺序拼回来。

### 恢复是两步，中间那步不能省

```
选文件夹 → 读出来（只读）→ 给用户看清单 → 确认 → 才真正写入
```

恢复会覆盖掉当前全部数据且不可撤销。中间那一步「这是什么时候导出的、里面有多少条
对话和记忆」，是这个功能里唯一能防止误操作的地方。

写入走的是 DataStore 的 `edit`，**不是**拿备份文件覆盖 `.preferences_pb`。
后者一定会出事：DataStore 在进程内是常驻的，你从外面换了文件，它下一次写入会把内存
里的旧状态原样盖回去 —— 用户看到的现象是「恢复成功，但一重启就变回原样」。

### 导出到哪儿

- **Android 10+**：系统「下载 / FocusSupervisor」，走 MediaStore，不需要任何权限，
  用任何文件管理器都能看到；
- **Android 9 及以下**：系统没有免权限写公共目录的办法，退回应用私有外部目录，
  界面会把完整路径显示出来。

本地向量模型（22MB）**不进备份** —— 它是能从界面重新下回来的东西。

---

## 本地向量模型：把 22MB 搬到手机上

「+」→「向量模型」→ 切到「本地模型」。

### 为什么值得为它加十几 MB

记忆检索是这个应用里**唯一一个每几分钟就要跑一次**的模型调用（见 `AppContainer` 里
那个五分钟一轮的补向量循环）。挂在在线端点上意味着三件事：

- **没网就不能用** —— 而「管住自己不刷手机」这件事，恰恰经常发生在想断网的时候；
- **每条记忆都要发一次请求** —— 长期看是一笔持续的支出；
- **记忆是最私密的一类数据** —— 他在坚持什么、反复失败在什么地方。
  为它单独找一家可信任的端点，比自己在本机算要难得多。

### 是什么

all-MiniLM-L6-v2 的量化版 ONNX，384 维、22MB，托管在本仓库的公开 Release：

```
https://github.com/wenqv4617-art/FocusSupervisor/releases/download/vector-model-v1/model_quantized.onnx
```

模型来源是 `sentence-transformers/all-MiniLM-L6-v2`（Apache-2.0）。词表（BERT WordPiece，
30522 条，231KB）内置在 APK 里（`app/src/main/assets/vocab.txt`） —— 这么小的东西没必要让用户再下一次，
而且下载失败时整个功能就废了。

### 一次向量是怎么算出来的

```
文本
 └─ WordPieceTokenizer ──→ input_ids / attention_mask / token_type_ids
      └─ ONNX 模型 ──────→ last_hidden_state  [1, seq, 384]
           └─ mean pooling + L2 归一化 ──→ 一条 384 维单位向量
```

后两步都是**必须**的，缺一个结果就是错的：

- **mean pooling** 要按 `attention_mask` 求平均，补齐用的 `[PAD]` 必须排除 ——
  否则短句的向量会被几十个 padding 拉偏，而长句几乎不受影响，排序会整体失真；
- **L2 归一化** 与 sentence-transformers 的训练输出保持一致，才谈得上「同一套语义空间」。

分词器也是自己写的。这不是炫技：按字切或者按空格切，得到的输入分布与模型训练时的
完全不是一个东西 —— **模型不会报错**，它只会安静地给出毫无意义的向量，然后记忆检索
变成随机召回。「看起来在工作但结果是错的」比直接报错有害得多。

### 下载：断点续传 + 校验

22MB 在国内网络下断一次很常见，每次都从零开始会让用户连试三次都下不完。所以：
下载到 `.part` 临时文件，每次开始前看它有多大，用 `Range` 续传。响应的三种情况都处理
（206 追加 / 200 清空重下 / 416 已完整）。进度按 250ms 上报一次，带速度与剩余时间，
支持随时取消（取消后已下载的部分保留）。

**全部下完之后必须校验 SHA-256 再改名。** 一个被截断的 ONNX 文件不会在写入时报错，
它会在运行时抛出一句和「下载」毫无关系的解析错误 —— 那种问题极难归因。
校验不过就删掉重下，绝不把损坏的文件留在正式路径上。

### 在线与本地是互斥的两条路

不做自动回退。用户明确选了本地，就该只走本地 —— 一旦偷偷回退到在线端点，
「这次为什么变慢了 / 这次为什么花了钱」就成了一个没法解释的现象。
模型没下好时本地路径直接返回失败并带上原因，记忆仓库会安静地退回关键词召回。

**切换时旧向量会被自动清掉。** 不同模型给出的向量来自不同的语义空间，余弦相似度在
它们之间恒为 0 —— 留着不会报错，只会让「召回越来越差」变成一个查不出原因的现象。
所以 `embedPending` 里加了维度一致性检查：发现维度变了就整批清空，让它们重新排队计算。

---

## 对话为什么不再「慢半拍」

这是一个真实发生过的缺陷：AI 总是回答**上一轮**的内容，忽略刚发的那句。

### 根因不是模型，是时序

```
  ConversationEngine.send(text)
    ├─ conversation.append(新消息)          ← 写 DataStore，**异步**
    └─ val history = conversation.messages.value   ← 读内存镜像
```

`append` 落盘之后，仓库的内存镜像要等 DataStore 的 Flow 重新发射才更新 ——
中间有一个真实窗口。窗口里读到的历史**不含本轮输入**，于是旧历史以 assistant 结尾，
状态块被挂到更早的那条用户消息上，模型只能顺着往下说。

### 修法不是加锁

加锁治不了「读到的就是旧快照」。真正的修法是让组装器**不再依赖那个快照**：

1. 新增 `CurrentTurn`，由引擎在 `append` **之前**构造，作为本轮唯一真实输入显式传入；
2. `buildTurns` 先按渲染正文从历史里**幂等剔除**本轮输入（尾部同文本兜底再削一次）；
3. 无论历史长什么样，**末尾都由组装器自己重新追加一次**，状态块只挂在这一条上。

于是「末尾是本轮输入」从「一个希望」变成了「构造上的保证」。历史里有它、没有它，
结果完全一致。

---

## 提示词预算：云端与端侧走两条轨

| | 云端（DeepSeek V3 / GPT-4o） | 端侧（手机上的 .task 模型） |
|---|---|---|
| 整份提示词上限 | 10,000 token | **1,500 ~ 2,048 token** |
| 保留历史 | 24 条 | 6 ~ 8 条（3~4 轮） |
| 向量召回 | 6 条 | 1 ~ 2 条 |
| 时间线事件 | 12 条 | 3 条 |

端侧那一栏不是「省着点」，是**硬约束**：SoC 的预填充速度是每秒几百 token，
10k token 意味着按下发送之后要盯着屏幕半分钟才看到第一个字。

预算收敛只丢历史，**绝不丢 system 与本轮输入**，而且按块丢 —— 逐条丢会让每一轮的
窗口起点都不同，上一轮发过的内容这一轮全部错位，缓存前缀当场作废。

Token 数是**估算**而非真分词：真分词要为五个模型各内置一份词表（几 MB），
而这个数只用来决定要不要丢历史，对 ±15% 不敏感。估算刻意偏保守。

---

## 五套模型个性化提示词

一套提示词不可能同时喂好 0.5B 和 DeepSeek V3。这不是调参能解决的，是能力边界的差别。

| Profile | 给谁 | 关键做法 |
| --- | --- | --- |
| A 微型 | 0.5B 级 | 人设压到三句、**给死输出范例**、强制 60 字内短回答 |
| B 主力 | 1.5B ~ 3B | 丰满管家人设，规则与指令分离 |
| C Llama | Llama 3.2 | 顶部注入中英语种强约束锚点，走 Llama 3 原生 header |
| D 思考 | R1 蒸馏 | think 链约束：**严禁把 `[[cmd:...]]` 写进思考块** |
| E 云端 | V3 / GPT-4o | 全量长上下文、分层记忆、完整时间线 |

D 那条不是洁癖：思考链是会被用户看到的，而且解析器**会把思考里的指令真的执行** ——
模型在思考里写「也许该放行小红书」，解析器就真的放行了。

**风格照模型走，预算照运行位置走。** 混在一起的具体后果：云端 0.5B 按微型档发预算
会白丢十几轮对话；跑在手机上、名字又认不出来的模型按云端档发预算，会让首字延迟顶到十几秒。

「本机」现在只有一个判据：地址指向环回（有人把 Ollama 跑在手机上）。
应用自己拉起端侧模型那条路已经移除，但五档一个都没删 —— 档位判的是**模型名**，
而用户完全可以把端点指向本机 Ollama（`qwen2.5:0.5b` 这种名字照样会出现），
云端也真的会跑 R1 与 Llama。

---

## 为什么删掉了端侧对话模型

阶段九最初是往「把对话也搬到手机本地」做的，而且做完了：MediaPipe GenAI 跑 `.task`
模型，四个真实可下载的模型（Qwen2.5 0.5B / 1.5B、R1-Distill 1.5B、Phi-4-mini 3.8B），
逐字输出、跑分卡片、下载续传、SHA-256 校验、显式释放，全都实现过并通过了 CI。

**真机装上之后，结论是这条路不划算，于是整条链路被删掉了。** 不是实现不好，是取舍：

| 量级 | 回答质量 | 代价 |
| --- | --- | --- |
| 0.5B / 1.5B | 答得动，但经常答偏、复读、抓不住上下文 | 0.5~1.5GB 磁盘，首字半秒到两秒 |
| 3.8B | 明显更好，仍然不如云端 | 3.8GB 磁盘 + 数 GB 运存，手机开始杀别的应用 |

而自律监督这个场景对回答质量的要求恰恰不低：它要理解一句含糊的辩解、要判断该不该
放行、要在正确的时候说正确的话。一个「问它一句要等五秒、答得还不一定对」的管家，
用户第三次就不会再打开它了。

更关键的是**它给人的期待是错的**：列表里摆着四个模型、有体积有跑分，用户会以为
「下完就能离线用」。实际下完之后得到的是一个明显更差的助手 —— 那比一开始就没有
这个选项更糟。

所以现在只有一条对话路径：**OpenAI 兼容端点**（云端，或用户自己在手机上跑的
Ollama）。离线优先这件事依旧成立，只是它由向量模型与识图引擎承担 ——
那两块留在本机是因为它们**在自己的位置上真的做得更好**：本地向量让记忆检索
不依赖网络，本地 OCR 让监督者不必把屏幕截图发出去。它们不是「本地版的云端功能」，
而是本来就只有本地做才合理的事。

删掉的东西（以及为什么不留着但默认关闭）：只要 `tasks-genai` 还在依赖里，
构建就会把一个 LLM 运行时打进包，每个 ABI 十几 MB 的原生库，哪怕一行代码都不用。
一个永远不打开的开关不是「保留可能性」，是**给未来的人留一个必须读懂的谜题**。

## 端侧识图：让纯文本模型也能看见

原来的链路是「截屏 → 上传云端多模态 → 描述」。它有两个硬伤：必须有网，
以及**对话模型也得支持看图**（DeepSeek 不看图，于是用户为了用上注视监控就得换掉主力对话模型）。

新链路把「看图」这一步留在本机：

```
  截屏 ──本地解析──> 一句 40 字以内的事实 ──> 时间线
                                                │
                          对话端点（不看图的纯文本模型）也能读到
```

### 备选 A · ML Kit 中文 OCR（默认，已可用）

毫秒级、零大模型负担、约 10MB 原生组件。做法是启发式提炼而不是假装理解：
顶部行当标题、中等长度的短句当内容，纯数字与底栏噪音词丢掉。

它不完美，但它**不会编造** —— 而编造恰恰是纯文本模型看图最容易犯的错，
这里因为「没有模型」根本编不出来。

### 备选 B · 微型端侧 VLM（架构已就位，运行时不存在）

真正的画面理解，能看懂游戏与视频。但**现在没有可用的运行时**：候选模型只有
`.tflite` / `.litertlm`，后者要 LiteRT-LM，而它在 Google Maven 与 Maven Central
上都查不到（都是 404）。

处理方式是：接口留好、卡片显示、**把原因写在卡片上**。一个写着「暂不可用 ·
运行时未发布」的选项，比一个干脆不存在的选项诚实，也比一个点下去没反应的按钮好得多。

---

## 资源管理

「+」→「数据管理」→「管理模型资源」。

在这之前，本机模型分居两处（向量在「向量模型」、识图在「注视监控」）。
单看每一处都合理，合起来却有一个说不通的问题：**没有任何一页能回答
「这些加起来占了我多少空间」**。

所以这一页把它们压成同一种卡片，用户要做的决定是同一个：下不下、删不删、用不用、占多少。
顶部一行给出「已占 / 全部下载需要」—— 一个还没有下任何东西的用户最需要知道的
就是这个总数。

### 那个「点两次才生效」的缺陷

资源管理是从数据管理里点进去的，而这两个面板各自是一个**独立的 Android 窗口**
（Material3 的 `ModalBottomSheet` 内部就是一个 Dialog）。`Dialog.dismiss()` 移除窗口
是**异步**的：它只往主线程消息队列投一条消息，真正 `removeView` 要等到下一轮。
于是「同一帧里关掉 A、打开 B」的真实结果是：有一小段时间 A 的窗口还在窗口栈上，
B 的窗口刚加进去 —— 两个都能吃触摸的全屏窗口叠在一起，第一下点击就被那个
正在消失的窗口拿走了，表现为**按钮不灵敏、要点两次**。

修法只能是时序：先关、等 100ms（约 6 帧，足够 `removeView` 执行完，
又远低于人能感知到卡顿的 200ms 门槛）、再开。这不是权宜之计，
而是同一个进程里同时存在两个模态窗口这件事本身的代价。

---

## 阶段边界（现在还没有的东西）

- **没有流式输出**：对话是一次性等模型生成完再上屏，没有逐字效果。等待期间列表末尾
  会挂一条「正在思考…」的临时气泡（它**不是一条消息**，不进仓库、不落盘），
  否则用户按下发送之后屏幕上什么都不会变，只能怀疑是不是没点上；
- **待办**：面板里可以自己增 / 勾 / 删，AI 也能通过指令改 —— 闭环已经通了。
  截止时间只给三个纯算术区间（30 分钟 / 1 小时 / 3 小时），要精确时间随时可以让 AI
  用指令改（它接受 `yyyy-MM-dd HH:mm`）；
- **注视监控要手动开**：`FocusMonitorService` 由「注视监控」面板里的开关拉起，
  不开就不会跑。这也是为什么**关掉注视监控就不会有 AI 主动盘问** —— 盘问的闸门
  正是「判定为正在注视屏幕」，两者是同一条链路上的上下游；
- **向量索引有硬上限**：最近 300 条对话原文参与语义召回，更早的仍在聊天记录里可翻看。
  要突破这个上限，应该把索引挪出 DataStore（独立文件或 Room），而不是把数字调大；
- **时间线也有上限（300 条）**：它是「近期证据」不是档案 —— 需要长期记住的东西
  由记忆三层承载。同样地，要突破就把它挪出 DataStore；
- **没走上架签名**：CI 用的是仓库里那把公开的调试密钥（见「签名」一节），
  发布密钥的钩子已经留好但没有配 Secrets；
- **注视监控已知的边界**：
  - **不能主动关掉别人的小窗**。Android 没有窗口级的「关闭」API，所以做法是盖住
    并拦住交互，逼用户自己关 —— 这已经足够，但不要指望它能代劳；
  - 截屏需要 **Android 11+**（无障碍的 `takeScreenshot` 是 API 30 引入的），
    低版本上只记录「在看 / 没在看」，不发截图；
  - 截屏能力还依赖**无障碍服务处于开启状态**，没开就只有状态没有描述；
  - 「本次运行累计注视」不跨进程重启，理由见 `VisionStatus` 的注释；
  - 系统全局摄像头开关被关掉时，App 收到的是**黑帧而不是异常**，所以界面上会显示
    「画面里没有人」——这是平台行为，暂时无法区分；
  - 国产 ROM（MIUI / HarmonyOS / ColorOS）对后台与摄像头有额外的省电与隐私限制，
    需要用户手动把这些应用加入白名单，没有任何办法绕过；
- **ML Kit 打包版在无 GMS 机型上的可用性**：官方没有明文保证（POM 里仍有
  `play-services-*` 传递依赖）。代码对初始化与检测失败都做了兜底 —— 失败只发一条
  错误状态，不影响其它功能。真机是国产无 GMS ROM 的话需要实测一次。
- **对话没有离线路径**：应用自己拉起端侧模型那条路已经删掉（理由见上面那一节）。
  想完全离线对话，唯一的办法是在手机上跑一个 Ollama 之类的服务、把端点指向
  环回地址 —— 那时提示词会自动按本机端点的预算收紧；
- **APK 现在 83.9 MB**（debug，含两个 ABI）。主要体积是 ML Kit 中文 OCR 与
  ONNX Runtime：两者都没有云端替代方案 —— 本地向量与本地识图之所以在包里，
  正是因为它们要能离线跑。想减的话，最直接的一刀是去掉 armeabi-v7a，
  但会连带影响 ML Kit 与 ONNX 的老设备可用性；
- **体积口径要说清楚**：这一栏写的是**磁盘上的 APK**（83.9 MB）。GitHub Actions
  的 Artifacts 里显示的是**它被压缩后的大小**（45.2 MB），两者差得很多，
  因为 `.so` 在现代 AGP 下是**不压缩**存进 APK 的 —— 也就是说，看 Artifacts
  的数字会低估用户实际要下载的体积。移除端侧对话模型那一步的真实收益是
  **−20.0 MB**（100.0 → 83.9 MB，两个 ABI 的 MediaPipe 原生库）；
- **备选 B（微型 VLM）没有运行时**：见「端侧识图」一节。架构已就位，运行时一发布即可接上；
- **样式注入不是 CSS**：认得的只有一份固定的选择器与属性清单（见「聊天页美化」一节），
  没有后代选择器、没有伪类、没有 `rgb()` 与具名颜色。这是 Compose 的架构决定的，
  不是实现偷懒；
- **背景虚化做不到**：Compose 的 `Modifier.blur` 模糊的是元素**自己的绘制内容**，
  不是它背后的东西，所以没有浏览器的 `backdrop-filter`。面板顶部的「毛玻璃」
  用的是半透明底 + 内容从下面滚过的替代做法；
- **本地模型是可选的，且要占地方**：ONNX Runtime 进包约 +13MB，模型文件再占 22MB。
  嫌它占地方就继续用在线端点，两条路随时能切；
- **备份不包含本地模型**：它是可以重新下回来的东西，打包进去只会让每份备份都大 22MB；
- **恢复是整份覆盖**：没有「只恢复记忆」「只恢复对话」这种粒度。整份覆盖也正是
  用户点那个按钮时期望的语义，但值得知道；
- **没有单元测试**：`GazeEstimator`、`TimeNarrator`、`StyleInjector` 都是纯函数式的，
  是最该补的三块。
