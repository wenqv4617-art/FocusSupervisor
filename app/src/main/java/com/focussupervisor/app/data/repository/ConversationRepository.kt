package com.focussupervisor.app.data.repository

import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 数据层：对话仓库
 *
 * 这一层存在之后，「会话记录」才真正属于用户而不是属于某次界面存活 ——
 * 关掉应用、杀进程、重启手机，对话都还在。
 *
 * 为什么和记忆分成两个仓库：它们**读的是同一批数据，但关心的时间尺度完全不同**。
 *  - 对话仓库回答「刚才聊了什么」，按时间正序，只保留最近 [AppPreferencesDataSource.MAX_STORED_MESSAGES] 条；
 *  - 记忆仓库回答「关于他我知道什么」，按重要性排序，与时间顺序无关。
 *
 * 合成一个类的话，那个类既要维护时间序又要维护语义索引，两套淘汰策略会互相打架。
 */
interface ConversationRepository {

    /** 完整会话，按时间正序。 */
    val messages: StateFlow<List<ChatMessage>>

    /** 追加一条消息。 */
    suspend fun append(message: ChatMessage): Boolean

    /** 清空会话。用户主动「重新开始」时用；记忆不受影响。 */
    suspend fun clear(): Boolean
}

/**
 * [ConversationRepository] 的 DataStore 实现。
 */
class DataStoreConversationRepository(
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
) : ConversationRepository {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _messages.value = prefs.messages
            }
        }
    }

    override suspend fun append(message: ChatMessage): Boolean = try {
        preferences.appendMessage(message)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "保存消息失败", t)
        false
    }

    override suspend fun clear(): Boolean = try {
        preferences.replaceMessages(emptyList())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "清空会话失败", t)
        false
    }

    private companion object {
        const val TAG = "ConversationRepository"
    }
}
