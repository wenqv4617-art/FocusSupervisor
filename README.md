# FocusSupervisor

AI 监督自律 App。当前处于**阶段三**：本地持久化、OpenAI 兼容通信层与 AI 配置中心
已经就位；真正调用模型的「白名单审批」与「TodoList 盘问」还差最后一层业务编排。

---

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 工程骨架、仿微信界面、CI 打包 | 已完成 |
| 二 | 权限引导、动态白名单、全屏遮罩、TodoList 数据地基 | 已完成 |
| 三 | DataStore 持久化、OpenAI 兼容通信层、AI 配置中心 | 当前 |
| 四 | TodoList 盘问、AI 白名单审批（调用模型） | 未开始 |

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

## 首次安装后的四步

1. 打开应用 → 「+」→ **权限检查**，把 **无障碍服务** 与 **悬浮窗** 两项开掉
   （入口上有红点就说明还没配齐）；
2. 回应用 → 「+」→ **强制锁定测试**，确认遮罩能盖满全屏、5 秒后自动解除；
3. 「+」→ **AI 配置**，选一个预设（DeepSeek / 本地 Ollama / OpenAI），填 Key，
   点 **拉取** 选模型，再点 **测试连接**。成功会自动收起面板，并在会话里留下一条
   `[系统] AI 终端握手成功，当前模型：…`；
4. 「+」→ **待办与白名单**，看一眼当前的待办和豁免情况。

之后打开任何不在白名单里的应用，都会被全屏遮罩压下；按返回键回到桌面。
所有配置（白名单、待办、AI 端点）都会持久化，杀进程重开不会丢。

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

## 阶段边界（现在还没有的东西）

- **还没有真正调用模型**：通信层只有 `fetchModels` 与 `testConnection` 两个方法，
  白名单审批（`grantTemporaryWhitelist`）接口已经就位，但还没有调用方；
- **待办仍是样例数据**：仓库里预置了两条，只为让面板有东西可展示，等接上真正的
  TodoList 盘问后应删掉；
- **注视监控未实现**：`FocusMonitorService` 能正确进入前台态，但不会被自动拉起；
- **没有 release 签名构建**，没有单元测试（JSON 编解码目前是纯函数，可直接测，
  但 `org.json` 在 JVM 单测里不可用 —— 要补测试得先把它换成 kotlinx.serialization）。
