# FocusSupervisor

AI 监督自律 App。当前处于**阶段二**：应用监控、全屏遮罩、白名单判定已经跑通闭环，
AI 侧的「白名单审批」与「TodoList 盘问」还只有数据地基。

---

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 一 | 工程骨架、仿微信界面、CI 打包 | 已完成 |
| 二 | 权限引导、动态白名单、全屏遮罩、TodoList 数据地基 | 当前 |
| 三 | TodoList 盘问、AI 白名单审批 | 未开始 |

## 交付物一览

| 交付项 | 位置 |
| --- | --- |
| GitHub Actions 自动打包 debug APK | `.github/workflows/build_apk.yml` |
| 权限与组件声明、包可见性 `<queries>` | `app/src/main/AndroidManifest.xml` |
| 领域契约：会话模型 | `domain/model/ChatModel.kt` |
| 领域契约：待办 / 豁免 / 权限 / 播报 | `domain/model/PolicyModels.kt` |
| 策略仓库（同步判定 + Flow 暴露 + 播报通道） | `data/repository/AppPolicyRepository.kt` |
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
快照，判定走同步读；持久化只是快照的备份」。下一阶段接 DataStore 时，只需新增一个
实现同一接口的 `DataStoreAppPolicyRepository`，上层一行都不用改。

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

## 首次安装后的三步

1. 打开应用 → 「+」→ **权限检查**，把 **无障碍服务** 与 **悬浮窗** 两项开掉
   （入口上有红点就说明还没配齐）；
2. 回应用 → 「+」→ **强制锁定测试**，确认遮罩能盖满全屏、5 秒后自动解除；
3. 「+」→ **待办与白名单**，看一眼当前的待办和豁免情况。

之后打开任何不在白名单里的应用，都会被全屏遮罩压下；按返回键回到桌面。

## 怎么重新生成图标

```bash
npm install sharp
node tools/generate_icons.mjs
```

## 阶段边界（现在还没有的东西）

- **没有 AI**：白名单审批（`grantTemporaryWhitelist`）接口已经就位，但还没有调用方；
- **待办是样例数据**：仓库里预置了两条，只为让面板有东西可展示，等阶段三接上真正的
  TodoList 盘问后应删掉；
- **没有持久化**：白名单与待办都在内存里，进程被杀就回到初始状态；
- **注视监控未实现**：`FocusMonitorService` 能正确进入前台态，但不会被自动拉起；
- **没有 release 签名构建**，没有单元测试。
