package com.focussupervisor.app.data.mock

import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MessageSender

/**
 * 骨架阶段的假数据。
 *
 * 归属 data 层：它和将来真正的 DataStore / Room 实现是同一种东西 —— 消息的来源。
 * 界面只认 `List<ChatMessage>`，所以后续把这里换成真实仓库时，UI 一行都不用改。
 *
 * 时间戳按「相对于当前时刻」生成，而不是写死常量：这样每次冷启动看到的都是
 * 「几分钟前」，不会出现 1970 年或未来时间的诡异观感。
 */
object MockChatData {

    private const val MINUTE_MILLIS = 60_000L

    /**
     * 初始化会话：两条对话 + 两条系统事件。
     *
     * 顺序刻意安排成一次完整的监督开场：
     *   1. 系统宣告能力已就绪（视线感知开启）
     *   2. AI 说明今天的规则
     *   3. 用户回应
     *   4. 系统捕获到一次违规进入
     * 这样一屏之内就能同时验证三种气泡的排版、长文本换行和胶囊居中效果。
     */
    fun initialMessages(now: Long = System.currentTimeMillis()): List<ChatMessage> = listOf(
        ChatMessage(
            id = "sys-1",
            sender = MessageSender.SYSTEM,
            text = "[系统] 已开启屏幕视线感知",
            timestampMillis = now - 6 * MINUTE_MILLIS,
        ),
        ChatMessage(
            id = "ai-1",
            sender = MessageSender.AI,
            text = "我在。今天的目标是连续三小时深度专注，中途如果你打开娱乐类应用，我会先提醒一次，再拦下来。",
            timestampMillis = now - 5 * MINUTE_MILLIS,
        ),
        ChatMessage(
            id = "user-1",
            sender = MessageSender.USER,
            text = "明白，先把这一节代码写完再说。",
            timestampMillis = now - 3 * MINUTE_MILLIS,
        ),
        ChatMessage(
            id = "sys-2",
            sender = MessageSender.SYSTEM,
            text = "[系统] 监测到进入：小红书",
            timestampMillis = now - 1 * MINUTE_MILLIS,
        ),
    )
}
