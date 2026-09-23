package com.focussupervisor.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.data.mock.MockChatData
import com.focussupervisor.app.domain.model.ActionItem
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.ChatUiState
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.ui.components.defaultActionItems
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
 * 单向数据流（UDF）：
 * ```
 *   UI ──(事件)──> ViewModel ──(新 StateFlow 值)──> UI 重组
 * ```
 * 界面里不存任何 `remember { mutableStateOf(...) }` 的业务状态，全部收敛到这里。
 * 好处是进程被系统回收后重建（配置变更、后台被杀）时，会话内容不会丢。
 *
 * 骨架阶段这里直接读 [MockChatData]；接真实数据时只需把构造函数换成仓库接口，
 * UI 与状态结构完全不动。
 */
class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = MockChatData.initialMessages(),
            actions = defaultActionItems(),
        ),
    )

    /** 对外只暴露只读视图，杜绝 UI 侧直接改状态。 */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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
     * 处理顺序：
     *   1. 空白草稿直接忽略（[ChatUiState.canSend] 已保证按钮不可点，这里是第二道闸）；
     *   2. 立刻把用户消息追加进列表，清空草稿并收起面板；
     *   3. 起一个协程，延迟一小段时间后追加一条 AI 回复 —— 这一步是**临时的占位
     *      行为**，用来验证「发送 → 列表增长 → 自动滚到底」这条链路真的通了。
     *      接入真实模型时，把这段替换成向 domain 层的 use case 发起请求即可。
     *
     * 用 [viewModelScope] 而不是自己造 CoroutineScope：ViewModel 被清理时它会被
     * 自动取消，不会出现「页面已经销毁但协程还在改状态」的泄漏。
     */
    fun onSend() {
        val current = _uiState.value
        val draft = current.inputText.trim()
        if (draft.isEmpty()) return

        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = newMessageId(MessageSender.USER),
            sender = MessageSender.USER,
            text = draft,
            timestampMillis = now,
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
                text = "收到。这条回复是骨架阶段的占位内容，等真正接上模型后会替换成真实判断。",
                timestampMillis = System.currentTimeMillis(),
            )
            _uiState.update { it.copy(messages = it.messages + reply) }
        }
    }

    /**
     * 「+」面板里的功能被点击。
     *
     * 骨架阶段的行为刻意做成「往会话里插一条系统播报」：这样每个入口被点到时，
     * 界面上都有确定的可见反馈，便于验证点击路由是否正确，而不是静默无响应。
     * 真正实现时这里会分发到对应的 use case。
     *
     * @param action 被点击的动作项，分发键用它自己的 [ActionItem.id]。
     */
    fun onActionSelected(action: ActionItem) {
        val event = ChatMessage(
            id = newMessageId(MessageSender.SYSTEM),
            sender = MessageSender.SYSTEM,
            text = "[系统] 触发入口：${action.label}（尚未实现）",
            timestampMillis = System.currentTimeMillis(),
        )

        _uiState.update {
            it.copy(
                messages = it.messages + event,
                isActionPanelVisible = false,
            )
        }
    }

    private fun newMessageId(sender: MessageSender): String =
        "${sender.name.lowercase()}-${UUID.randomUUID()}"

    private companion object {
        /** AI 占位回复的模拟延迟，让「思考中」不至于快到看不见。 */
        const val AI_REPLY_DELAY_MILLIS = 600L
    }
}
