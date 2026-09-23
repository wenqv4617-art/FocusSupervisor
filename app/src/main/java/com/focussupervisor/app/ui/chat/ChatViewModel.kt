package com.focussupervisor.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.data.mock.MockChatData
import com.focussupervisor.app.domain.model.ActionItem
import com.focussupervisor.app.domain.model.ChatDialog
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.ChatUiState
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PermissionStatus
import com.focussupervisor.app.domain.model.PermissionTarget
import com.focussupervisor.app.domain.model.SystemNotice
import com.focussupervisor.app.ui.components.ActionIds
import com.focussupervisor.app.ui.components.defaultActionItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 聊天主界面的状态持有者。
 *
 * ===========================================================================
 * 单向数据流
 * ===========================================================================
 * ```
 *   UI ──(意图)──> ViewModel ──(新 StateFlow 值)──> UI 重组
 *                       ↑
 *   无障碍服务 / 仓库 ──(Flow)──┘
 * ```
 * 界面里不存任何业务状态，全部收敛到这里。**同时它也是 service 层与 UI 层唯一的
 * 汇合点**：无障碍服务把拦截事件写进仓库，ViewModel 订阅仓库，把事件翻译成聊天流里
 * 的居中系统胶囊。服务层完全不知道聊天的存在。
 *
 * ===========================================================================
 * 为什么是 AndroidViewModel
 * ===========================================================================
 * 需要拿到 `AppContainer`（仓库、遮罩控制器、权限管理器）。这三者都由
 * `Application` 持有，因此走 AndroidViewModel 的构造注入是最短路径 ——
 * 不需要自定义 Factory，`viewModel()` 的默认工厂就能创建它。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val policy = container.policy
    private val permissionManager = container.permissions
    private val lockOverlay = container.lockOverlay

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = MockChatData.initialMessages(),
            actions = defaultActionItems(),
        ),
    )

    /** 对外只暴露只读视图，杜绝 UI 侧直接改状态。 */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * 已经插进会话的播报 id。
     *
     * StateFlow 会在每个新订阅者接入时重放一次当前值，没有这个集合，每次回到界面
     * 都会把历史播报重新插一遍。
     */
    private val appendedNoticeIds = mutableSetOf<String>()

    /** 是否已经处理过第一次播报发射（用于判断「冷启动补历史」还是「实时追加」）。 */
    private var noticesSeeded = false

    /** 「强制锁定测试」的定时任务。重复点击时先取消上一个，避免两个定时器互相打架。 */
    private var lockTestJob: Job? = null

    init {
        observeWhitelist()
        observeTodos()
        observeNotices()
        observeOverlay()
        refreshPermissions()
    }

    // -----------------------------------------------------------------------
    // 输入与发送
    // -----------------------------------------------------------------------

    /** 输入框内容变化。 */
    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** 展开 / 收起「+」面板。 */
    fun onToggleActionPanel() {
        _uiState.update { it.copy(isActionPanelVisible = !it.isActionPanelVisible) }
    }

    /**
     * 发送当前草稿。
     *
     * 阶段二里这一步仍然只有「用户消息 + 占位回复」—— 真正的 AI 接入在阶段三。
     * 之所以先保留，是因为它是验证「发送 → 列表增长 → 自动滚到底」这条链路的唯一手段；
     * 去掉它，界面就从「能验证」退化成「只能看」。
     */
    fun onSend() {
        val current = _uiState.value
        val draft = current.inputText.trim()
        if (draft.isEmpty()) return

        val userMessage = ChatMessage(
            id = newMessageId(MessageSender.USER),
            sender = MessageSender.USER,
            text = draft,
            timestampMillis = System.currentTimeMillis(),
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isActionPanelVisible = false,
            )
        }

        viewModelScope.launch {
            delay(AI_REPLY_DELAY_MILLIS)
            val reply = ChatMessage(
                id = newMessageId(MessageSender.AI),
                sender = MessageSender.AI,
                text = "收到。这条回复仍是占位内容，等接上模型后会替换成基于待办与白名单的真实判断。",
                timestampMillis = System.currentTimeMillis(),
            )
            _uiState.update { it.copy(messages = it.messages + reply) }
        }
    }

    // -----------------------------------------------------------------------
    // 「+」面板分发
    // -----------------------------------------------------------------------

    /**
     * 「+」面板里的功能被点击。
     *
     * 分发键用 [ActionItem.id]（见 [ActionIds]），不用中文 label —— label 是展示文案，
     * 改字的时候分发会静默失效。
     */
    fun onActionSelected(action: ActionItem) {
        when (action.id) {
            ActionIds.PERMISSION_CHECK -> {
                // 打开前先重查一遍：用户可能刚从设置页回来。
                refreshPermissions()
                openDialog(ChatDialog.PERMISSION_CHECK)
            }

            ActionIds.FORCE_LOCK_TEST -> runLockTest()

            ActionIds.POLICY_STATUS -> openDialog(ChatDialog.POLICY_STATUS)

            ActionIds.AI_CONFIG -> {
                // 「AI 配置中心」是 ModalBottomSheet，不走 ChatDialog。
                // 先收起「+」面板，否则输入法弹起来之后面板还挂在 Sheet 底下。
                _uiState.update {
                    it.copy(
                        isAiConfigSheetVisible = true,
                        isActionPanelVisible = false,
                    )
                }
            }

            else -> {
                _uiState.update { it.copy(isActionPanelVisible = false) }
                policy.publishNotice("触发入口：${action.label}（尚未实现）")
            }
        }
    }

    /** 关闭「AI 配置中心」。 */
    fun onAiConfigSheetDismiss() {
        _uiState.update { it.copy(isAiConfigSheetVisible = false) }
    }

    /** 关闭当前模态面板。 */
    fun onDialogDismiss() {
        _uiState.update { it.copy(dialog = null) }
    }

    /**
     * 跳转到某项权限的系统设置页。
     *
     * 跳转失败时不留白：插一条系统播报让用户知道要手动去设置里找。这是「点了没反应」
     * 和「点了但失败了」之间的区别，用户有权知道是哪一种。
     */
    fun onOpenPermissionSettings(target: PermissionTarget) {
        if (!permissionManager.openSettings(target)) {
            policy.publishNotice("无法打开「${target.label}」的设置页，请手动前往系统设置")
        }
    }

    /** 重新采集权限状态。界面回到前台时调用。 */
    fun refreshPermissions() {
        val snapshot = permissionManager.snapshot()
        _uiState.update { state ->
            state.copy(
                permissions = snapshot,
                // 有权限没开就在「权限检查」上点红点 —— 主界面唯一允许的异常提示。
                actions = defaultActionItems(
                    needsPermissionAttention = snapshot.any { !it.granted },
                ),
                statusText = computeStatusText(snapshot, state.overlayActive),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 强制锁定测试
    // -----------------------------------------------------------------------

    /**
     * 「强制锁定测试」：拉起遮罩 5 秒后自动解除，并在会话里留一条结果播报。
     *
     * 为什么不直接调 `LockOverlayController` 就完事：这个入口的价值在于**验证**。
     * 用户点它，是为了确认三件事都通了 —— 悬浮窗权限拿到了、遮罩能盖住整屏、
     * 并且真的能解除。所以结果必须以播报的形式落进会话，而不是一闪而过。
     *
     * 遮罩的解除放在 `finally` 里：即使协程被取消（用户中途退出界面），遮罩也一定会
     * 被撤掉。**任何情况下都不能留下一块解除不掉的黑屏。**
     */
    fun runLockTest() {
        _uiState.update { it.copy(isActionPanelVisible = false) }

        if (!lockOverlay.isPermissionGranted) {
            policy.publishNotice("强制锁定测试无法执行：缺少悬浮窗权限")
            return
        }

        val selfPackage = getApplication<Application>().packageName
        lockOverlay.show(packageName = selfPackage, headline = LOCK_TEST_HEADLINE)

        lockTestJob?.cancel()
        lockTestJob = viewModelScope.launch {
            var finished = false
            try {
                delay(LOCK_TEST_DURATION_MILLIS)
                finished = true
            } finally {
                lockOverlay.dismiss()
                policy.publishNotice(
                    if (finished) {
                        "强制锁定测试完成（5 秒），遮罩已自动解除"
                    } else {
                        "强制锁定测试被中断，遮罩已解除"
                    },
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 订阅仓库与遮罩状态
    // -----------------------------------------------------------------------

    private fun observeWhitelist() {
        viewModelScope.launch {
            policy.whitelist.collect { list ->
                _uiState.update { it.copy(whitelist = list) }
            }
        }
    }

    private fun observeTodos() {
        viewModelScope.launch {
            policy.todos.collect { list ->
                _uiState.update { it.copy(todos = list) }
            }
        }
    }

    /**
     * 把系统播报并入会话。
     *
     * 冷启动与实时两种情况的处理刻意不同：
     *  - **实时**（界面开着时发生拦截）：来一条插一条，用户能当场看见；
     *  - **冷启动**（应用没开时服务已经在拦）：只补最近 [COLD_START_NOTICE_LIMIT] 条。
     *    仓库里最多存着 200 条，一次性倒进会话会把开场白冲得无影无踪，那不是
     *    「告知」而是「刷屏」。
     */
    private fun observeNotices() {
        viewModelScope.launch {
            policy.notices.collect { notices ->
                val isColdStart = !noticesSeeded
                noticesSeeded = true

                val fresh = notices.filterNot { it.id in appendedNoticeIds }
                if (fresh.isEmpty()) return@collect
                fresh.forEach { appendedNoticeIds += it.id }

                val toAppend = if (isColdStart) fresh.takeLast(COLD_START_NOTICE_LIMIT) else fresh

                _uiState.update { state ->
                    state.copy(
                        messages = (state.messages + toAppend.map { it.toChatMessage() })
                            .takeLast(MAX_MESSAGES),
                    )
                }
            }
        }
    }

    /**
     * 跟随遮罩的显示状态，把顶栏副标题切换成「监管中 · 已压制」。
     *
     * 这是用户在被遮罩拦住之后，回到应用时能看到的第一个反馈：刚才那次压制确实
     * 发生过，而且现在还在生效。
     */
    private fun observeOverlay() {
        viewModelScope.launch {
            lockOverlay.isShowing.collect { showing ->
                _uiState.update { state ->
                    state.copy(
                        overlayActive = showing,
                        statusText = computeStatusText(state.permissions, showing),
                    )
                }
            }
        }
    }

    /**
     * 顶栏副标题文案。
     *
     * 三种状态对应三件不同的事，顺序不能反：正在压制 > 能力就绪 > 权限没配齐。
     */
    private fun computeStatusText(
        permissions: List<PermissionStatus>,
        overlayActive: Boolean,
    ): String {
        if (overlayActive) return "监管中 · 已压制"

        val accessibilityReady = permissions
            .firstOrNull { it.target == PermissionTarget.ACCESSIBILITY }?.granted == true
        val overlayReady = permissions
            .firstOrNull { it.target == PermissionTarget.OVERLAY }?.granted == true

        return if (accessibilityReady && overlayReady) "已连接 · 监督中" else "已连接 · 待授权"
    }

    private fun openDialog(dialog: ChatDialog) {
        _uiState.update {
            it.copy(
                dialog = dialog,
                isActionPanelVisible = false,
            )
        }
    }

    /**
     * ViewModel 被销毁。
     *
     * 只解除「测试遮罩」—— 判据是被遮罩盖住的包名恰好是本应用自己，那是
     * [runLockTest] 的特征。真实拦截的遮罩归无障碍服务管（它在 `onDestroy` 里
     * 无条件撤掉），在这里误撤会打断一次有效的拦截。
     */
    override fun onCleared() {
        super.onCleared()
        val selfPackage = getApplication<Application>().packageName
        if (lockOverlay.lockedPackage.value == selfPackage) {
            lockOverlay.dismiss()
        }
    }

    private fun newMessageId(sender: MessageSender): String =
        "${sender.name.lowercase()}-${UUID.randomUUID()}"

    private fun SystemNotice.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        sender = MessageSender.SYSTEM,
        text = text,
        timestampMillis = timestampMillis,
    )

    private companion object {
        /** AI 占位回复的模拟延迟，让「思考中」不至于快到看不见。 */
        const val AI_REPLY_DELAY_MILLIS = 600L

        /** 强制锁定测试的持续时长。 */
        const val LOCK_TEST_DURATION_MILLIS = 5_000L

        /** 测试遮罩上的提示语，与真实拦截的文案刻意区分开。 */
        const val LOCK_TEST_HEADLINE = "强制锁定测试 · 5 秒后自动解除"

        /** 冷启动时最多补几条历史播报。 */
        const val COLD_START_NOTICE_LIMIT = 6

        /** 会话消息上限。长时间运行的应用不设上限就是慢性内存泄漏。 */
        const val MAX_MESSAGES = 300
    }
}
