package com.focussupervisor.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.ai.SendOutcome
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

/**
 * 聊天主界面的状态持有者。
 *
 * ===========================================================================
 * 单向数据流
 * ===========================================================================
 * ```
 *   UI ──(意图)──> ViewModel ──(新 StateFlow 值)──> UI 重组
 *                       ↑
 *   仓库（对话 / 播报 / 人设 / 白名单）──(Flow)──┘
 * ```
 * 界面里不存任何业务状态，全部收敛到这里。
 *
 * ===========================================================================
 * 消息列表的唯一来源是「对话仓库」
 * ===========================================================================
 * 上一版把消息拼成「Mock 开场白 + 仓库播报」，那是骨架期的权宜之计。
 * 现在所有消息（包括系统胶囊）都先落进对话仓库，再由这里读出来显示：
 *  - 关掉应用、杀掉进程，聊天记录都还在；
 *  - 系统胶囊也进了历史，用户回头能看见「那次拦截是什么时候发生的」。
 *
 * 播报变成消息只在一个地方发生（[observeNotices]），且按 id 去重 ——
 * StateFlow 会在每个新订阅者接入时重放当前值，没有去重就会把历史播报重新插一遍。
 *
 * ===========================================================================
 * 业务逻辑不在这里
 * ===========================================================================
 * 「组装提示词、调模型、解析并执行指令」全部在 `ConversationEngine` 里。
 * 这个类只剩下三件事：收集状态、转发意图、显示结果。它能保持这么薄是刻意的。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val policy = container.policy
    private val permissionManager = container.permissions
    private val lockOverlay = container.lockOverlay
    private val conversation = container.conversation
    private val personaRepository = container.personas
    private val engine = container.conversationEngine

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = emptyList(),
            actions = defaultActionItems(),
        ),
    )

    /** 对外只暴露只读视图，杜绝 UI 侧直接改状态。 */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 已经转成聊天消息的播报 id，用于去重。 */
    private val consumedNoticeIds = mutableSetOf<String>()

    /** 是否已经处理过第一次播报发射（区分「冷启动补历史」与「实时追加」）。 */
    private var noticesSeeded = false

    /** 「强制锁定测试」的定时任务。重复点击时先取消上一个。 */
    private var lockTestJob: Job? = null

    init {
        observeMessages()
        observePersonas()
        observeWhitelist()
        observeTodos()
        observeNotices()
        observeOverlay()
        refreshPermissions()
    }

    // -----------------------------------------------------------------------
    // 输入与发送
    // -----------------------------------------------------------------------

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onToggleActionPanel() {
        _uiState.update { it.copy(isActionPanelVisible = !it.isActionPanelVisible) }
    }

    /**
     * 发送。
     *
     * 用户消息由 [com.focussupervisor.app.core.ai.ConversationEngine] 负责落库，
     * 所以这里只做两件事：清掉输入框、把「正在等回复」的标记打开。
     *
     * 失败不在这里处理 —— 引擎会往会话里插一条系统胶囊说明原因，用户能在
     * 聊天记录里看到。这里只负责把转圈停掉。
     */
    fun onSend() {
        val draft = _uiState.value.inputText.trim()
        if (draft.isEmpty() || _uiState.value.isSending) return

        _uiState.update {
            it.copy(
                inputText = "",
                isActionPanelVisible = false,
                isSending = true,
            )
        }

        viewModelScope.launch {
            val outcome = engine.send(draft)
            _uiState.update { it.copy(isSending = false) }

            // 「还没配置端点」是个高频且可自愈的失败：直接把配置面板弹出来，
            // 比让用户自己去「+」里翻要少两步。
            if (outcome is SendOutcome.NotConfigured) {
                openSheet(SheetTarget.AI_CONFIG)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 「+」面板分发
    // -----------------------------------------------------------------------

    fun onActionSelected(action: ActionItem) {
        when (action.id) {
            ActionIds.PERMISSION_CHECK -> {
                refreshPermissions()
                openDialog(ChatDialog.PERMISSION_CHECK)
            }

            ActionIds.FORCE_LOCK_TEST -> runLockTest()

            ActionIds.POLICY_STATUS -> openDialog(ChatDialog.POLICY_STATUS)

            ActionIds.PERSONA_MANAGE -> openSheet(SheetTarget.PERSONA)

            ActionIds.MEMORY_MANAGE -> openSheet(SheetTarget.MEMORY)

            ActionIds.AI_CONFIG -> openSheet(SheetTarget.AI_CONFIG)

            else -> {
                _uiState.update { it.copy(isActionPanelVisible = false) }
                policy.publishNotice("触发入口：${action.label}（尚未实现）")
            }
        }
    }

    fun onDialogDismiss() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun onAiConfigSheetDismiss() = closeSheet(SheetTarget.AI_CONFIG)

    fun onPersonaSheetDismiss() = closeSheet(SheetTarget.PERSONA)

    fun onMemorySheetDismiss() = closeSheet(SheetTarget.MEMORY)

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

    fun refreshPermissions() {
        val snapshot = permissionManager.snapshot()
        _uiState.update { state ->
            state.copy(
                permissions = snapshot,
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
     * 「强制锁定测试」：拉起遮罩 5 秒后自动解除。
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
    // 订阅
    // -----------------------------------------------------------------------

    private fun observeMessages() {
        viewModelScope.launch {
            conversation.messages.collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    private fun observePersonas() {
        viewModelScope.launch {
            personaRepository.personas.collect { pair ->
                _uiState.update {
                    it.copy(
                        personas = pair,
                        // 顶栏显示 AI 的名字 —— 用户给它起的名字应该出现在最显眼的位置。
                        agentName = pair.ai.name,
                    )
                }
            }
        }
    }

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
     * 把系统播报并入对话历史。
     *
     * 冷启动与实时两种情况的处理刻意不同：
     *  - **实时**（界面开着时发生）：来一条插一条，用户能当场看见；
     *  - **冷启动**（应用没开时服务已经在拦）：只补最近几条。仓库里最多存着 200 条，
     *    一次性倒进会话会把开场白冲得无影无踪，那不是「告知」而是「刷屏」。
     */
    private fun observeNotices() {
        viewModelScope.launch {
            policy.notices.collect { notices ->
                val isColdStart = !noticesSeeded
                noticesSeeded = true

                val fresh = notices.filterNot { it.id in consumedNoticeIds }
                if (fresh.isEmpty()) return@collect
                fresh.forEach { consumedNoticeIds += it.id }

                val toAppend = if (isColdStart) fresh.takeLast(COLD_START_NOTICE_LIMIT) else fresh
                toAppend.forEach { notice -> conversation.append(notice.toChatMessage()) }
            }
        }
    }

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
     * 顺序不能反：正在压制 > 能力就绪 > 权限没配齐。
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

    // -----------------------------------------------------------------------
    // 面板开关
    // -----------------------------------------------------------------------

    /** 三个 BottomSheet 的标识。 */
    private enum class SheetTarget { AI_CONFIG, PERSONA, MEMORY }

    private fun openDialog(dialog: ChatDialog) {
        _uiState.update {
            it.copy(dialog = dialog, isActionPanelVisible = false)
        }
    }

    /**
     * 打开一个 BottomSheet。
     *
     * 三个 Sheet 互斥是通过「每次只把一个置 true」保证的，而不是三个独立开关 ——
     * 后者迟早会出现两个同时为真的状态，那时界面上会叠两层遮罩。
     */
    private fun openSheet(target: SheetTarget) {
        _uiState.update {
            it.copy(
                isActionPanelVisible = false,
                isAiConfigSheetVisible = target == SheetTarget.AI_CONFIG,
                isPersonaSheetVisible = target == SheetTarget.PERSONA,
                isMemorySheetVisible = target == SheetTarget.MEMORY,
            )
        }
    }

    private fun closeSheet(target: SheetTarget) {
        _uiState.update {
            when (target) {
                SheetTarget.AI_CONFIG -> it.copy(isAiConfigSheetVisible = false)
                SheetTarget.PERSONA -> it.copy(isPersonaSheetVisible = false)
                SheetTarget.MEMORY -> it.copy(isMemorySheetVisible = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val selfPackage = getApplication<Application>().packageName
        if (lockOverlay.lockedPackage.value == selfPackage) {
            lockOverlay.dismiss()
        }
    }

    private fun SystemNotice.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        sender = MessageSender.SYSTEM,
        text = text,
        timestampMillis = timestampMillis,
    )

    private companion object {
        const val LOCK_TEST_DURATION_MILLIS = 5_000L
        const val LOCK_TEST_HEADLINE = "强制锁定测试 · 5 秒后自动解除"
        const val COLD_START_NOTICE_LIMIT = 6
    }
}
