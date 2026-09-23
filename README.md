# FocusSupervisor

AI 监督自律 App。当前处于**骨架阶段**：界面、权限声明与自动打包流水线已就位，
具体的监督策略尚未实现。

---

## 这个阶段做了什么

| 交付项 | 位置 |
| --- | --- |
| GitHub Actions 自动打包 debug APK | `.github/workflows/build_apk.yml` |
| 权限与组件声明（无障碍 / 悬浮窗 / 前台服务 / 摄像头） | `app/src/main/AndroidManifest.xml` |
| 领域模型与界面状态契约 | `app/src/main/java/com/focussupervisor/app/domain/model/ChatModel.kt` |
| 消息气泡与系统胶囊 | `app/src/main/java/com/focussupervisor/app/ui/components/MessageBubbles.kt` |
| 底部「+」功能面板 | `app/src/main/java/com/focussupervisor/app/ui/components/PlusActionPanel.kt` |
| 仿微信聊天主界面 | `app/src/main/java/com/focussupervisor/app/ui/chat/ChatScreen.kt` |
| 入口 Activity | `app/src/main/java/com/focussupervisor/app/MainActivity.kt` |
| 启动图标生成器 | `tools/generate_icons.mjs` |

## 技术栈

- Kotlin 2.0.21 / Jetpack Compose（BOM 2024.12.01）/ Material 3
- AGP 8.7.3，Gradle 8.11.1，JDK 17
- `compileSdk = 35`，`minSdk = 26`，`targetSdk = 35`
- Clean Architecture 分层：`domain` / `data` / `ui` / `service`
- 单向数据流：`ChatViewModel` 持有 `StateFlow<ChatUiState>`，界面只发意图

## 目录结构

```
app/src/main/java/com/focussupervisor/app/
├── FocusSupervisorApp.kt          进程级初始化（通知渠道）
├── MainActivity.kt                唯一入口，只做「套主题 + 挂界面」
├── domain/model/ChatModel.kt      纯 Kotlin 领域模型，无框架依赖
├── data/mock/MockChatData.kt      骨架期假数据（两条对话 + 两条系统事件）
├── ui/
│   ├── theme/                     配色（含自定义语义色板）/ 字体 / 主题
│   ├── util/TimeFormat.kt         时间格式化
│   ├── components/
│   │   ├── MessageBubbles.kt      用户气泡 / AI 气泡 / 居中系统胶囊
│   │   └── PlusActionPanel.kt     「+」网格面板
│   └── chat/
│       ├── ChatScreen.kt          顶栏 + 消息列表 + 输入栏
│       └── ChatViewModel.kt       状态持有者
└── service/
    ├── FocusAccessibilityService.kt   无障碍服务骨架（识别前台 App）
    └── FocusMonitorService.kt         前台服务骨架（摄像头视线检测宿主）
```

## 设计约定

- 主界面只有三样东西：顶栏、消息流、输入栏。**所有配置类入口都收进底部「+」面板。**
- 三类消息三种形态，互不混用：用户靠右绿气泡、AI 靠左白气泡、系统事件居中灰胶囊。
- 不用 Emoji、不用渐变、不用阴影。层次只靠底色明度和一条 0.5dp 发丝线表达。
- 图标只用 `material-icons-core`（约 50 个），刻意不引 `material-icons-extended`
  —— 那是个几万图标的巨型依赖，会明显拖慢 debug 构建并让 APK 虚胖。

---

## 怎么拿到 APK

**不需要在本地装 Android SDK**，APK 由 GitHub Actions 构建。

1. 推到 `main`，或在 GitHub 仓库页面进 **Actions → Build Debug APK → Run workflow** 手动触发；
2. 等构建变绿；
3. 在那次 run 页面底部的 **Artifacts** 里下载 `FocusSupervisor-debug-apk`，解开就是
   `app-debug.apk`。**artifact 保留 7 天**，过期重跑一次即可。

同一个 workflow 也支持本地复现：

```bash
./gradlew assembleDebug --stacktrace
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 怎么重新生成图标

```bash
npm install sharp
node tools/generate_icons.mjs
```

会从 `art/icon-source.webp` 重新生成全部 mipmap（传统方形 / 传统圆形 / 自适应前景 /
自适应背景）以及 `art/icon-master-1024.png`、`art/icon-512.png`。
尺寸与安全区的取舍写在脚本文件头的注释里。

## 阶段边界（现在还没有的东西）

- 无障碍服务只把「前台应用切换」写进 logcat，**不做任何拦截**；
- 前台服务能被正确启动为前台态，但**不会被任何地方自动拉起**；
- 「+」面板的四个入口点击后只往会话里插一条 `[系统] …（尚未实现）`；
- 发送消息后的 AI 回复是固定文案 + 600ms 延迟的**占位实现**；
- 没有签名 release 构建，没有单元测试。
