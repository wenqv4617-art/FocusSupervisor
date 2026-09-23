package com.focussupervisor.app.domain.model

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 领域层：聊天会话模型
 *
 * 分层说明
 * --------
 * 本文件属于 domain 层，是整个应用最内圈、最稳定的一层。它描述「一个监督会话
 * 由哪些东西构成」，不关心这些东西从哪来（Room / 网络 / Mock），也不关心它们
 * 怎么被画出来。因此这里没有 Android 框架依赖，只有纯 Kotlin 数据结构。
 *
 * 唯一的例外是 [ActionItem.icon]：按产品要求直接持有 Compose 的 [ImageVector]。
 * 严格来说这是 UI 类型泄漏进 domain，代价是 domain 需要依赖 compose-ui-graphics。
 * 在动作项数量固定且极少（当前 5 个）的场景下，这样写比再引入一层 ActionIcon 枚举
 * + UI 侧映射更省事、可读性更好；如果将来动作项变成服务端下发（图标不可编译期确定），
 * 就应该把它换回字符串 key，由 UI 层负责解析成矢量图。
 */

/**
 * 消息发送者。
 *
 * SYSTEM 不是「第三方系统账号」，而是应用自身产生的状态播报（例如「监测到进入：
 * 小红书」「已开启屏幕视线感知」）。它在界面上不表现为对话气泡，而是居中的胶囊块，
 * 用来把「谁在说话」和「系统发生了什么」在视觉上彻底区分开。
 */
enum class MessageSender {
    /** 用户自己发出的消息，靠右显示。 */
    USER,

    /** AI 监管者发出的消息，靠左显示。 */
    AI,

    /** 应用产生的系统级状态事件，居中显示为胶囊块。 */
    SYSTEM,
}

/**
 * 一条聊天消息。
 *
 * @param id 稳定唯一标识。LazyColumn 的 key 直接用它，保证列表增删时不会错位、
 *           也不会因为重排而丢掉滚动位置。
 * @param sender 发送者，决定这条消息渲染成哪一类气泡。
 * @param text 消息正文。骨架阶段是纯文本；后续要支持富文本时，应把它替换成
 *             一个 sealed interface 的 content 字段，而不是在这里堆标记。
 * @param timestampMillis 消息产生的 Unix 时间戳（毫秒），展示时格式化为 HH:mm。
 */
data class ChatMessage(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestampMillis: Long,
)

/**
 * 底部「+」面板里的一个动作项。
 *
 * 微信把「图片、拍摄、视频通话……」收进加号面板，我们沿用同一套收纳逻辑：
 * 所有配置类、调试类入口都不放在主界面上，只放在这里，让主界面保持只有
 * 「消息 + 输入框」两件事。
 *
 * @param id 稳定唯一标识，用于点击回调路由（避免用中文 label 做分发键）。
 * @param label 面板上显示的短名称。
 * @param icon 面板上的矢量图标。只用 Material 图标核心集，见 ui 层注释。
 * @param needsAttention 是否需要在图标右上角点一个小红点。
 *        当前只有一个用途：权限没配齐时点亮「权限检查」。这是主界面**唯一**允许
 *        出现「异常提示」的地方 —— 把问题收进面板入口本身，而不是在主界面上加横幅。
 */
data class ActionItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val needsAttention: Boolean = false,
)

/**
 * 主界面上可能弹出的模态面板。
 *
 * 只有「+」面板里的入口会打开它们，关闭后回到纯聊天界面。用枚举而不是多个布尔量，
 * 从类型上保证同一时刻最多只有一个面板 —— 两个布尔量就可能出现都为 true 的非法状态。
 */
enum class ChatDialog {
    /** 权限检查：列出各项能力的当前状态，并提供跳转系统设置页的入口。 */
    PERMISSION_CHECK,

    /** 待办与白名单：展示当前待办列表与生效中的豁免。 */
    POLICY_STATUS,
}

/**
 * 监督会话在界面上的整体状态快照。
 *
 * 表现层只消费这一个不可变对象（配合 StateFlow），所有交互都是「发意图 → 归约
 * 出新状态」，界面本身不持有可变状态，这样旋转屏幕、进程重建都不会丢状态。
 *
 * @param agentName AI 监管者名称，显示在顶栏正中。
 * @param statusText 顶栏副标题，例如「已连接 · 待机中」。
 * @param messages 当前会话的消息列表，按时间正序。
 * @param inputText 输入框里的草稿文本。
 * @param isActionPanelVisible 「+」面板是否展开。
 * @param actions 「+」面板里的动作项。
 * @param dialog 当前弹出的模态面板；null 表示没有。
 * @param permissions 最近一次权限检查的结果快照，供权限面板展示。
 * @param todos 待办列表，供「待办与白名单」面板展示。
 * @param whitelist 当前生效的白名单（含临时豁免），同上。
 * @param overlayActive 全屏遮罩此刻是否挂着。顶栏副标题会据此变化。
 */
data class ChatUiState(
    val agentName: String = "FocusSupervisor",
    val statusText: String = "已连接 · 待机中",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isActionPanelVisible: Boolean = false,
    val actions: List<ActionItem> = emptyList(),
    val dialog: ChatDialog? = null,
    val permissions: List<PermissionStatus> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val whitelist: List<WhitelistApp> = emptyList(),
    val overlayActive: Boolean = false,
) {
    /** 有非空白草稿时才允许发送，避免发出一条空消息。 */
    val canSend: Boolean get() = inputText.isNotBlank()

    /** 是否有未开启的权限。用于决定「权限检查」入口要不要点亮红点。 */
    val hasMissingPermission: Boolean
        get() = permissions.any { !it.granted }
}
