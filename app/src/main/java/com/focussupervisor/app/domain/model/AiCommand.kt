package com.focussupervisor.app.domain.model

/**
 * 领域层：AI 可以请求执行的系统操作
 *
 * ===========================================================================
 * 为什么需要这个类型
 * ===========================================================================
 * AI 不该只会在聊天框里说话 —— 它得能真的「开一个白名单」「记一件事」。
 * 但模型只能输出文本，所以约定一套**结构化指令**：模型在回复末尾另起一行输出
 *
 * ```
 * [[cmd:{"type":"whitelist","package":"com.tencent.mm","minutes":10,"reason":"回工作消息"}]]
 * ```
 *
 * 应用把它解析出来、从可见文本里**删掉**、执行、然后以一条居中的系统胶囊上屏。
 * 用户看到的是「[系统] AI 已放行 微信 · 10 分钟」，而不是一串 JSON。
 *
 * ===========================================================================
 * 安全边界（很重要）
 * ===========================================================================
 * 这套指令**只能**做用户本来就做得到的事：开临时豁免、写待办、写记忆。
 * 它做不到：
 *  - 打开任意 URL / 启动任意应用 —— 指令里根本没有这类动作；
 *  - 修改人设、前置提示、API 配置 —— 那些是用户的领域，模型碰不到；
 *  - 把设备关键应用（桌面 / 输入法 / 电话）放进白名单 —— 仓库层会直接拒绝；
 *  - 给自己授永久豁免（minutes 有上限，见 `AiCommandLimits`）。
 *
 * 换句话说：指令集是**白名单式的**，不是「模型让系统干什么就干什么」。
 * 将来加新动作时，必须同时想清楚上面这几条的边界要不要跟着松开。
 */
sealed interface AiCommand {

    /** 给某个应用开一段临时豁免。 */
    data class GrantWhitelist(
        val packageName: String,
        val minutes: Int,
        val reason: String,
    ) : AiCommand

    /** 撤销某个应用的临时豁免。 */
    data class RevokeWhitelist(
        val packageName: String,
    ) : AiCommand

    /** 记一条待办。 */
    data class AddTodo(
        val title: String,
        /** 计划完成时刻；null 表示模型没给时间，由仓库补一个默认值。 */
        val dueAtMillis: Long?,
    ) : AiCommand

    /** 把一条待办标记为完成。 */
    data class CompleteTodo(
        /** 待办 id 或标题片段。模型多半只会给标题，所以两种都接受。 */
        val target: String,
    ) : AiCommand

    /** 写一条记忆。 */
    data class WriteMemory(
        val content: String,
        val tier: MemoryTier,
        /** 是否钉住（钉住的不会被自动清理、不参与自动提升）。 */
        val pinned: Boolean,
    ) : AiCommand

    /**
     * 解析不出来的指令。
     *
     * 刻意保留而不是静默丢弃：模型偶尔会写出格式不对的 JSON，此时给用户一条
     * 「AI 想执行一个操作但格式不对」的提示，比让它悄悄消失要好 ——
     * 后者会表现为「AI 说它已经放行了，但什么都没发生」，那是最难查的一类问题。
     */
    data class Unparsable(
        val raw: String,
    ) : AiCommand
}

/**
 * 一条 AI 回复解析后的结果。
 *
 * @param visibleText 去掉全部指令标记之后、真正显示给用户的正文
 * @param commands 解析出来的指令，按出现顺序
 */
data class ParsedAiReply(
    val visibleText: String,
    val commands: List<AiCommand>,
)

/**
 * 指令的硬性上限。
 *
 * 这些数字是**应用说了算，不是模型说了算**：模型可以请求 600 分钟豁免，
 * 应用只给 60。放到这里单独定义，是为了让「AI 到底能开多久」这个问题
 * 有一个唯一的、可以被一眼看到的答案。
 */
object AiCommandLimits {

    /** 单次临时豁免的最长时长（分钟）。 */
    const val MAX_WHITELIST_MINUTES = 60

    /** 单次临时豁免的最短时长（分钟）。低于它没有意义。 */
    const val MIN_WHITELIST_MINUTES = 1

    /** 一条记忆内容的长度上限。 */
    const val MAX_MEMORY_LENGTH = 500

    /** 一条待办标题的长度上限。 */
    const val MAX_TODO_TITLE_LENGTH = 120

    /** 一条回复里最多执行多少条指令。防止模型刷屏式地写记忆。 */
    const val MAX_COMMANDS_PER_REPLY = 6

    /** 模型没给时间时，待办的默认计划时刻距现在多久（毫秒）。 */
    const val DEFAULT_TODO_DELAY_MILLIS = 60L * 60 * 1000
}
