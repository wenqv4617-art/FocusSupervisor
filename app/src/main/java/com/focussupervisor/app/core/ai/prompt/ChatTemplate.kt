package com.focussupervisor.app.core.ai.prompt

import com.focussupervisor.app.core.network.ChatTurn

/**
 * 本地模型的对话模板渲染。
 *
 * ===========================================================================
 * 为什么云端不需要它，本地必须要
 * ===========================================================================
 * 云端走的是 OpenAI 兼容的 `messages` 数组：角色、边界、结束标记全部由服务端
 * 按它自己的模板渲染，我们只交出「谁说了什么」。
 *
 * **本地模型拿到的是一个纯字符串。** 谁说了什么、哪里是系统提示、哪里该换人 ——
 * 全靠我们自己拼。拼错了模型不会报错，它只会表现得很奇怪（把 system 当用户的话来
 * 回答、或者不知道轮到自己了）。
 *
 * ===========================================================================
 * 最重要的一段：末尾那个 assistant 引导头
 * ===========================================================================
 * ```
 *   ... <|im_start|>user
 *       （他刚说的话）
 *   <|im_end|>
 *   <|im_start|>assistant      ← 就是这一行
 * ```
 *
 * 没有它，模型看到的是一个「完整的、已经结束的」对话，它不知道自己是下一个发言者，
 * 于是常见的行为是：重复最后一条 user 消息、或者自己补一个 `<|im_end|>` 就停下。
 *
 * 云端接口里对应的是「消息数组以 user 结尾」—— 语义一样，形式不同。
 * 这是本地路径上**唯一**一个不能少、也最容易漏掉的结构。
 */
object ChatTemplate {

    /** Qwen 系的 ChatML 特殊标记。 */
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    /** Llama 3 的原生 header 标记。 */
    private const val HEADER_START = "<|start_header_id|>"
    private const val HEADER_END = "<|end_header_id|>"
    private const val EOT = "<|eot_id|>"

    /**
     * 把消息序列渲染成模型能吃的单串提示词。
     *
     * @param format 由 Profile 决定：Qwen 系走 ChatML，Llama 系走它自己的 header。
     * @param turns 已经组装好的消息序列（末尾通常是一条 user）。
     * @param assistantPriming 引导头之后要不要补一段文字。留空表示只留引导头 ——
     *        这是常规做法。填内容相当于「替模型开个头」，只在需要强制格式时用。
     */
    fun render(
        format: ChatFormat,
        turns: List<ChatTurn>,
        assistantPriming: String = "",
    ): String = when (format) {
        ChatFormat.CHATML -> renderChatMl(turns, assistantPriming)
        ChatFormat.LLAMA3 -> renderLlama3(turns, assistantPriming)
    }

    private fun renderChatMl(turns: List<ChatTurn>, priming: String): String = buildString {
        turns.forEach { turn ->
            append(IM_START).append(turn.role).append('\n')
            append(turn.content)
            append(IM_END).append('\n')
        }
        // 引导头本身不带换行结尾：模型要接着这一行往下写。
        append(IM_START).append(ChatTurn.ROLE_ASSISTANT).append('\n')
        append(priming)
    }

    private fun renderLlama3(turns: List<ChatTurn>, priming: String): String = buildString {
        // Llama 3 要求第一个特殊标记是 <|begin_of_text|>，少了对齐会明显变差。
        append("<|begin_of_text|>")
        turns.forEach { turn ->
            append(HEADER_START).append(turn.role).append(HEADER_END).append("\n\n")
            append(turn.content)
            append(EOT)
        }
        append(HEADER_START).append(ChatTurn.ROLE_ASSISTANT).append(HEADER_END).append("\n\n")
        append(priming)
    }
}
