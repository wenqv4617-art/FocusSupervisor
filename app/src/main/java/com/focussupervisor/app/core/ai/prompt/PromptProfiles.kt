package com.focussupervisor.app.core.ai.prompt

import com.focussupervisor.app.core.network.ChatTurn
import kotlin.math.ceil

/**
 * 模型个性化提示词工程（Prompt Profiles）。
 *
 * ===========================================================================
 * 为什么必须按模型分档，而不是写一套「好提示词」
 * ===========================================================================
 * 一套提示词不可能同时喂好 0.5B 和 DeepSeek V3。这不是调参能解决的，而是
 * **能力边界**的差别：
 *
 * | 模型量级 | 它能做的事 | 喂长规则的后果 |
 * |----------|-----------|----------------|
 * | 0.5B | 听懂一句直接指令，照格式吐字 | 三百字人设它抓不住重点，开始编 |
 * | 1.5B~3B | 能守规则、能用指令、有语气 | 规则太多会漏掉其中几条 |
 * | 7B+ / 云端 | 能理解分层记忆与复杂约束 | 没有上限问题 |
 *
 * 所以这里做的不是「写五套文案」，而是**按模型量级决定给多少、给什么形式**：
 * 预算（多少 token）、历史（留几轮）、人设（丰满还是极简）、输出格式（给不给范例）、
 * 以及对话模板（ChatML / Llama 3 header）。
 *
 * ===========================================================================
 * 五个 Profile 对应什么
 * ===========================================================================
 * ```
 *   A  QWEN_MICRO   0.5B 级  极简、给死格式范例、要求极短回答
 *   B  QWEN_MAIN    1.5B~3B  标准 ChatML，丰满人设，规则与指令分离
 *   C  LLAMA        1B~3B    英文底模，顶部加中文强约束锚点
 *   D  REASONING    R1 蒸馏   think 链约束，禁止把指令写进思考
 *   E  CLOUD        云端    全量长上下文、分层记忆、完整时间线
 * ```
 */

/** Profile 的稳定 id。 */
enum class PromptProfileId {
    QWEN_MICRO,
    QWEN_MAIN,
    LLAMA,
    REASONING,
    CLOUD,
}

/**
 * 人设的写法。
 *
 * [TERSE] 不是「把 [FULL] 截短」，而是**换一种写法**：微型模型对连续散文的
 * 注意力很弱，但对「你是什么 / 你不能做什么」这种短句列表响应良好。
 */
enum class PersonaStyle {
    /** 丰满的自律管家人设：冷淡、严格、关心专注度。适合 1.5B 以上。 */
    FULL,

    /** 极简单刀直入：三句话讲完身份与底线。适合 0.5B 级。 */
    TERSE,
}

/**
 * 本地对话模板。
 *
 * 云端走的是 messages 数组，模板由服务端负责；**本地模型拿到的是一个纯字符串**，
 * 所以必须由我们自己把角色标记拼出来，并在末尾留下 assistant 的引导头 ——
 * 少了那个引导头，模型不知道自己该接着谁说话。
 */
enum class ChatFormat {
    /** Qwen 系：`<|im_start|>role ... <|im_end|>`，末尾留 `<|im_start|>assistant`。 */
    CHATML,

    /** Llama 3 系：`<|start_header_id|>role<|end_header_id|>`。 */
    LLAMA3,
}

/**
 * 一份提示词预算。
 *
 * 单位是**估算 token**，见 [TokenEstimator]。所有字段都是「上限」，
 * 实际用多少取决于这一轮真正有多少内容。
 *
 * @param maxPromptTokens 整份提示词的硬上限。超了就按块丢最旧的历史。
 * @param historyMessages 最多保留多少条历史消息（不含本轮输入与 system）。
 * @param historyTrimChunk 丢历史时的最小块大小。**按块丢而不是一条一条丢**：
 *        逐条丢会让每一轮的窗口起点都不同，上一轮发过的内容这一轮全部错位，
 *        上下文缓存前缀当场作废（云端按缓存价计费，本地省的是预填充时间）。
 * @param recallLimit 向量召回条数上限。
 * @param recalledConversationSnippets 召回的对话原文片段条数上限。
 * @param timelineEvents 时间线事件条数上限。
 * @param shortTermMemories 短期记忆条数上限。
 * @param whitelistEntries 白名单条目上限。**不能砍太狠** —— 那份名单直接决定
 *        模型能不能正确解释「为什么这个应用被拦了」。
 * @param visionDescriptionLength 视觉描述截断长度。
 */
data class PromptBudget(
    val maxPromptTokens: Int,
    val historyMessages: Int,
    val historyTrimChunk: Int,
    val recallLimit: Int,
    val recalledConversationSnippets: Int,
    val timelineEvents: Int,
    val shortTermMemories: Int,
    val whitelistEntries: Int,
    val visionDescriptionLength: Int,
)

/**
 * 一份完整的模型适配档案。
 *
 * @param systemAnchor 插在 system **最顶部**的强约束。它是唯一「排在用户前置提示
 *        之前」的东西 —— 用在那些必须压过一切的场景（Llama 的语种锚点）。
 *        留空表示不插。
 * @param replyLengthHint 写进 system 的回答长度要求。微型档必须给死，
 *        否则它会把一句话写成一段。
 * @param reasoningProtocol R1 系的思考链约束。
 * @param format 本地渲染用的对话模板。
 * @param profileTemperature 建议温度。为 null 表示用用户自己配的值。
 */
data class PromptProfile(
    val id: PromptProfileId,
    val displayName: String,
    val description: String,
    val budget: PromptBudget,
    val personaStyle: PersonaStyle,
    val format: ChatFormat,
    val systemAnchor: String = "",
    val replyLengthHint: String = "",
    val reasoningProtocol: String = "",
    val profileTemperature: Float? = null,
) {
    /** 是否要求模型把回答压得很短。界面用它显示「极简模式」。 */
    val isTerse: Boolean get() = personaStyle == PersonaStyle.TERSE
}

/**
 * Profile 的解析与定义。
 */
object PromptProfiles {

    // -----------------------------------------------------------------------
    // A · Qwen 0.5B 级微型模型
    // -----------------------------------------------------------------------

    /**
     * 微型档。
     *
     * 这一档的设计原则只有一条：**把「理解」的负担全部从模型身上拿走**。
     *
     * 做法是三件事，缺一不可：
     *  1. 人设压到三句话（[PersonaStyle.TERSE]）；
     *  2. **给死一个输出范例**，让它照着填，而不是让它理解一套规则再自己组织；
     *  3. 明确要求极短回答 —— 小模型写长文必然开始编。
     *
     * 预算卡在 1500 左右。端侧 0.5B 的预填充速度大约每秒几百 token，
     * 1500 token 的提示词对应首字延迟 1 秒上下；再长就直接顶到 3 秒以上，
     * 那时候「秒回」这个唯一的优势也没了。
     */
    private val QWEN_MICRO = PromptProfile(
        id = PromptProfileId.QWEN_MICRO,
        displayName = "微型模型模式",
        description = "给 0.5B 级模型用的极简提示词：人设三句话、给死输出范例、强制短回答。",
        personaStyle = PersonaStyle.TERSE,
        format = ChatFormat.CHATML,
        budget = PromptBudget(
            maxPromptTokens = 1500,
            // 3 轮对话。微型模型的注意力很短，给更多历史它反而会去复读更早的内容。
            historyMessages = 6,
            historyTrimChunk = 2,
            recallLimit = 1,
            recalledConversationSnippets = 1,
            timelineEvents = 3,
            shortTermMemories = 2,
            whitelistEntries = 8,
            visionDescriptionLength = 40,
        ),
        replyLengthHint = "每次只说一到两句话，**最多 60 个字**。不要解释，不要总结，不要写小作文。",
        profileTemperature = 0.5f,
    )

    // -----------------------------------------------------------------------
    // B · Qwen 1.5B ~ 3B 主力档
    // -----------------------------------------------------------------------

    /**
     * 主力档。
     *
     * 这是**推荐**的一档：模型已经能同时守住人设、规则与指令格式，
     * 所以可以给它丰满的人设与完整的指令协议。
     *
     * 与微型档的关键差别是「规则与指令分离」：把「怎么说话」和「怎么输出指令」
     * 分成两段独立说明，而不是混在一段里。1.5B 以上能处理这种结构，
     * 而混在一起的写法会让它把指令格式当成语气要求。
     *
     * 预算 2048：端侧 1.5B 在旗舰 SoC 上预填充约每秒 300~600 token，
     * 2048 token 对应首字 1 秒上下，正好卡在「不觉得卡」的边界内。
     */
    private val QWEN_MAIN = PromptProfile(
        id = PromptProfileId.QWEN_MAIN,
        displayName = "主力模型模式",
        description = "给 1.5B ~ 3B 模型：丰满的管家人设、规则与指令分离、四轮上下文。",
        personaStyle = PersonaStyle.FULL,
        format = ChatFormat.CHATML,
        budget = PromptBudget(
            maxPromptTokens = 2048,
            historyMessages = 8,
            historyTrimChunk = 4,
            recallLimit = 2,
            recalledConversationSnippets = 2,
            timelineEvents = 3,
            shortTermMemories = 3,
            whitelistEntries = 12,
            visionDescriptionLength = 60,
        ),
        replyLengthHint = "通常一到三句话。不要复述他刚说的话，也不要写总结段落。",
        profileTemperature = 0.7f,
    )

    // -----------------------------------------------------------------------
    // C · Llama 3.2 系列
    // -----------------------------------------------------------------------

    /**
     * Llama 档。
     *
     * ===========================================================================
     * 为什么必须有一条语种锚点，而且要压在**最顶部**
     * ===========================================================================
     * Llama 3.2 的中文能力来自训练语料里的中文，但它的**指令遵循层是英文的**：
     * 用中文写 system 时，它有一定概率用英文回答，或者中英混着说。这在聊天界面里
     * 是致命的 —— 用户看到的是一半英文的自律管家。
     *
     * 实测有效的做法是在**最前面**放一条英文的强约束（大写 + MUST），
     * 而不是在中文人设里补一句「请用中文回答」。原因是模型对「最前面的、
     * 英文的、命令式的」内容遵循度最高；夹在中文段落中间的一句中文请求会被淹没。
     *
     * @param anchor 语种锚点全文。
     */
    private fun llamaAnchor(): String = buildString {
        append("[CRITICAL INSTRUCTION: You MUST speak in fluent, natural Simplified Chinese ONLY]")
        append("\n")
        append("[你只能说简体中文。禁止使用英文单词、英文句子或任何其他语言作答。]")
        append("\n")
        append("[Your entire response must be written in Simplified Chinese.]")
    }

    private val LLAMA = PromptProfile(
        id = PromptProfileId.LLAMA,
        displayName = "Llama 适配模式",
        description = "给 Llama 3.2 系列：顶部注入中文强约束锚点，使用 Llama 3 原生对话模板。",
        personaStyle = PersonaStyle.FULL,
        format = ChatFormat.LLAMA3,
        systemAnchor = llamaAnchor(),
        budget = PromptBudget(
            maxPromptTokens = 2048,
            historyMessages = 8,
            historyTrimChunk = 4,
            recallLimit = 2,
            recalledConversationSnippets = 2,
            timelineEvents = 3,
            shortTermMemories = 3,
            whitelistEntries = 12,
            visionDescriptionLength = 60,
        ),
        replyLengthHint = "通常一到三句话，用简体中文。",
        profileTemperature = 0.7f,
    )

    // -----------------------------------------------------------------------
    // D · DeepSeek-R1 蒸馏思考模型
    // -----------------------------------------------------------------------

    /**
     * 思考模型档。
     *
     * ===========================================================================
     * 唯一真正要紧的约束：指令不能写进思考里
     * ===========================================================================
     * R1 系列会先输出一段 `<think>...</think>` 再做正式回答。**思考链是会被
     * 用户看到的**（我们不做隐藏），而且在思考里写 `[[cmd:...]]` 有两个后果：
     *
     *  1. 用户会看到一串 JSON，那是纯粹的噪音；
     *  2. 更糟的是——我们的解析器**会把思考里的指令当成真的去执行**。
     *     模型在思考里写「也许应该放行小红书」，解析器就真的放行了。
     *
     * 所以这条约束的语气必须足够硬，并且要给出**正面示范**（在思考结束后、
     * 正文最末尾输出指令），而不只是说「不要」。
     *
     * 另外，思考模型不适合做「秒回」场景：它的思考本身就要几百毫秒到几秒。
     * 所以预算里历史给得比主力档更少 —— 反正时间都花在思考上了，省下的
     * 预填充时间是唯一能补救的。
     */
    private fun reasoningProtocol(): String = """
        # 思考链的处理方式

        你可以先输出一段 <think>...</think> 来推演，但必须严格遵守：

        1. 思考里**只做推演**：现在几点、他上一次说话是什么时候、待办还剩多久、
           他这句话背后可能是什么状态。不要写结论式的指令。
        2. **严禁把 [[cmd:...]] 写进 <think> 块内部。**
           系统会扫描整条回复里的指令并真的去执行 —— 你在思考里随手写的
           「也许该放行小红书」如果带上了指令格式，就会**真的被放行**。
        3. 需要执行操作时，指令只能出现在**思考结束之后、正式回答的最末尾**，
           和正文分开、单独一行。
        4. 正式回答部分不要复述思考内容，直接说话。

        正确结构：
        <think>（推演，不含任何指令）</think>
        （正式回答，一到三句话）
        [[cmd:{"type":"whitelist","package":"com.tencent.mm","minutes":10,"reason":"回消息"}]]
    """.trimIndent()

    private val REASONING = PromptProfile(
        id = PromptProfileId.REASONING,
        displayName = "思考模型模式",
        description = "给 R1 蒸馏系列：允许思考链，但严禁把可执行指令写进思考块。",
        personaStyle = PersonaStyle.FULL,
        format = ChatFormat.CHATML,
        reasoningProtocol = reasoningProtocol(),
        budget = PromptBudget(
            maxPromptTokens = 2048,
            // 思考模型的时间大头在思考本身，历史再省一点。
            historyMessages = 6,
            historyTrimChunk = 2,
            recallLimit = 2,
            recalledConversationSnippets = 1,
            timelineEvents = 3,
            shortTermMemories = 3,
            whitelistEntries = 12,
            visionDescriptionLength = 60,
        ),
        replyLengthHint = "正式回答部分一到三句话，不要复述思考内容。",
        profileTemperature = 0.6f,
    )

    // -----------------------------------------------------------------------
    // E · 云端长文本档
    // -----------------------------------------------------------------------

    /**
     * 云端档。
     *
     * 保持原有的全量行为：长历史、完整分层记忆、完整时间线。
     *
     * 10000 token 的预算不是上限，而是**我们主动设的止损线**：
     * 一段对话真的涨到一万 token 以上时，多出来的那部分对回答质量几乎没有增益，
     * 但每一轮都要为它付全价（缓存命中也只是打折，不是免费）。
     */
    private val CLOUD = PromptProfile(
        id = PromptProfileId.CLOUD,
        displayName = "云端长文本模式",
        description = "给 DeepSeek V3 / GPT-4o 这类云端模型：长历史、完整分层记忆、完整时间线。",
        personaStyle = PersonaStyle.FULL,
        format = ChatFormat.CHATML,
        budget = PromptBudget(
            maxPromptTokens = 10_000,
            historyMessages = 24,
            historyTrimChunk = 12,
            recallLimit = 6,
            recalledConversationSnippets = 4,
            timelineEvents = 12,
            shortTermMemories = 6,
            whitelistEntries = 24,
            visionDescriptionLength = 200,
        ),
    )

    /** 全部 Profile，供界面遍历与展示。 */
    val all: List<PromptProfile> = listOf(QWEN_MICRO, QWEN_MAIN, LLAMA, REASONING, CLOUD)

    /** 兜底档。识别不出来时用它 —— 云端档的行为与历史版本完全一致。 */
    val fallback: PromptProfile = CLOUD

    /**
     * 按模型名与运行位置解析 Profile。
     *
     * ===========================================================================
     * 这里有两个互相独立的轴，不能混为一谈
     * ===========================================================================
     * ```
     *   风格轴（看模型）  →  用哪套人设写法、哪个对话模板、要不要语种锚点、要不要思考链约束
     *   预算轴（看运行位置）→  给多少 token、留几轮历史、召回几条
     * ```
     *
     * 混在一起的后果是具体的：一个部署在云端的 0.5B 模型，如果按「微型档」发预算，
     * 我们就会为了一个根本不存在首字延迟问题的端点，白白丢掉十几轮对话；
     * 反过来，一个跑在本机、名字认不出来的模型，如果按「云端档」发预算，
     * 10000 token 的提示词会让端侧首字延迟直接顶到十几秒。
     *
     * 所以：**风格照模型走，预算照运行位置走**。
     *
     * ===========================================================================
     * 为什么靠名字猜风格，而不是让用户选
     * ===========================================================================
     * 让用户选「你用的是哪一档模型」是把一个纯技术问题推给他 ——
     * 大部分人不知道自己那个中转站背后的模型是 1.5B 还是 70B。
     * 而模型名里几乎总是带着参数量（`qwen2.5:0.5b`、`llama3.2:1b`、
     * `deepseek-r1-distill-qwen-1.5b`），足够做判断。
     *
     * 猜错的代价也是有界的：最多是提示词风格不完全贴合，功能不会坏。
     * 界面上会把**当前判定结果**显示出来，用户看到不对能知道发生了什么。
     *
     * @param model 模型名，例如 `qwen2.5:1.5b`、`deepseek-chat`。
     * @param isLocal 是否跑在本机（端侧 MediaPipe，或本机 Ollama 的 127.0.0.1）。
     */
    fun resolve(model: String, isLocal: Boolean): PromptProfile {
        val style = resolveStyle(model, isLocal)
        // 云端：无论什么模型，都用长上下文预算。
        return if (isLocal) style else style.copy(budget = CLOUD_BUDGET)
    }

    /** 云端档的预算。抽出来是因为 [resolve] 要把它盖到任意风格上。 */
    private val CLOUD_BUDGET: PromptBudget = CLOUD.budget

    /**
     * 只解析**风格**，不管预算。
     *
     * 单独暴露出来是给界面用的：用户看到「当前按『思考模型模式』组装提示词」时，
     * 能知道我们认出了他的 R1；如果认错了，他也能立刻发现自己模型名里
     * 有个我们没料到的写法。
     */
    fun resolveStyle(model: String, isLocal: Boolean): PromptProfile {
        val name = model.lowercase()

        // ---- 思考模型：名字里带 r1 / reasoner / thinking 的都算 ----
        if (name.contains("r1") || name.contains("reasoner") || name.contains("thinking")) {
            return REASONING
        }

        // ---- Llama 系 ----
        if (name.contains("llama") || name.contains("meta-llama")) {
            return LLAMA
        }

        // ---- Qwen 系：按参数量分微型与主力 ----
        if (name.contains("qwen")) {
            return if (looksTiny(name)) QWEN_MICRO else QWEN_MAIN
        }

        // ---- 其它小模型：按量级判 ----
        if (looksTiny(name)) return QWEN_MICRO

        // ---- 认不出来：按运行位置给默认 ----
        //
        // 这一步很关键。端侧出现一个我们没见过的模型名时，绝不能退回云端档的风格 ——
        // 那意味着把写给 70B 的复杂规则喂给一个 1B 模型。
        return if (isLocal) QWEN_MAIN else CLOUD
    }

    /**
     * 名字里是否带着「小到需要用极简提示词」的信号。
     *
     * 判据是参数量数字加一个单位后缀。**不能只匹配 `0.5b`**——
     * 真实世界的模型名写法太杂：`qwen2.5:0.5b`、`Qwen2.5-0.5B-Instruct`、
     * `smollm-135m`、`gemma-3-270m`、`tinyllama`。
     */
    private fun looksTiny(name: String): Boolean {
        if (name.contains("tiny") || name.contains("micro") || name.contains("nano")) return true
        if (name.contains("135m") || name.contains("270m") || name.contains("360m")) return true
        if (name.contains("0.5b") || name.contains("0.6b")) return true
        // 形如 1b / 1.5b 的：1.5b 及以下算微型到主力之间，归主力；
        // 只有 1b 整（例如 llama3.2:1b）才需要压一压。
        if (name.contains("1b") && !name.contains("1.5b") && !name.contains("11b")) return true
        return false
    }
}

/**
 * Token 估算。
 *
 * ===========================================================================
 * 为什么是估算而不是真分词
 * ===========================================================================
 * 精确 token 数需要**每个模型各自的词表**：Qwen 是 tiktoken 系 BPE、
 * Llama 是 SentencePiece、DeepSeek 又是另一套。为五个模型各内置一份词表，
 * 加起来好几 MB，而它换来的只是「预算判得更准一点」。
 *
 * 而我们用这个数的目的不是计费，是**决定要不要丢历史**。这个决策对
 * ±15% 的误差完全不敏感 —— 预算 2048 时少估 300 个 token，最坏结果不过是
 * 多留了一轮对话。所以这里刻意偏**保守估算**（宁可高估），
 * 让预算成为硬约束而不是一个大概齐的数字。
 *
 * ===========================================================================
 * 估算规则
 * ===========================================================================
 * | 内容 | 系数 | 依据 |
 * |------|------|------|
 * | 中日韩汉字 | 0.6 / 字 | Qwen 类 BPE 对常用汉字多为 1~2 字一个 token |
 * | 拉丁字母与数字 | 0.30 / 字符 | 接近 GPT 系 BPE 的 4 字符 1 token |
 * | 标点、空白 | 0.4 / 字符 | 标点常独立成 token，但空格多被合并 |
 * | 换行 | 0.5 / 个 | 常与相邻内容合并 |
 *
 * 最后一律向上取整再加一个固定开销，覆盖角色标记等结构成本。
 */
object TokenEstimator {

    /** 每条消息的结构开销（角色标记、分隔符）。 */
    private const val PER_MESSAGE_OVERHEAD = 4

    /** 整份请求的固定开销。 */
    private const val REQUEST_OVERHEAD = 8

    /** 估算一段文本的 token 数。 */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var cjk = 0
        var latin = 0
        var other = 0
        var newlines = 0

        for (char in text) {
            when {
                char == '\n' -> newlines++
                isCjk(char) -> cjk++
                char.isLetterOrDigit() -> latin++
                else -> other++
            }
        }

        val tokens = cjk * 0.6 + latin * 0.30 + other * 0.4 + newlines * 0.5
        return ceil(tokens).toInt()
    }

    /** 估算整份消息序列的 token 数。 */
    fun estimate(turns: List<ChatTurn>): Int {
        var total = REQUEST_OVERHEAD
        turns.forEach { turn ->
            total += PER_MESSAGE_OVERHEAD + estimate(turn.content)
        }
        return total
    }

    /**
     * 汉字与中文标点判定。
     *
     * 与 `WordPieceTokenizer` 里的判据保持一致：只看 BMP，不用正则
     * （Android 的正则引擎是 ICU，本项目已经被它对花括号的判定坑过一次）。
     */
    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return (code in 0x4E00..0x9FFF) ||   // 基本区
            (code in 0x3400..0x4DBF) ||      // 扩展 A
            (code in 0xF900..0xFAFF) ||      // 兼容表意文字
            (code in 0x3000..0x303F)         // 中文标点
    }
}
