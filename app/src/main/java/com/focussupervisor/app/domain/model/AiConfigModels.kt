package com.focussupervisor.app.domain.model

/**
 * 领域层：AI 通信配置
 *
 * 这一层只描述「连上一个大模型需要哪些参数」，不关心它是怎么被发出去的
 * （OkHttp 在 core/network），也不关心它存在哪（DataStore 在 data/datastore）。
 *
 * 之所以把配置做成「预设列表 + 当前选中项」而不是「一组全局字段」：
 * 用户很可能同时有两个端点 —— 平时用 DeepSeek，断网或者想省钱时切到本机 Ollama。
 * 每次切换都重新手打一遍 URL 和 Key 是不可接受的，而把两套配置都存下来、
 * 一键切换几乎不增加成本。
 */

/**
 * 一份完整的端点配置。
 *
 * @param baseUrl API 根地址。允许用户填 `https://api.deepseek.com` 或
 *        `https://api.deepseek.com/v1` 两种写法，补全 `/v1` 的逻辑在
 *        `OpenAiCompatibleClient` 里统一处理，不在这里做 —— 领域模型不该关心 URL 拼装。
 * @param apiKey 密钥。本地明文存 Preferences DataStore，见 `AppPreferencesDataSource`
 *        的类注释里关于这个取舍的说明。
 * @param model 模型 id，例如 `deepseek-chat`、`qwen2.5:7b`。
 * @param temperature 采样温度。允许 0.0 ~ 1.5：比常见 SDK 的 0~2 上限更窄，
 *        因为监督场景不需要更强的发散，而超过 1.5 之后模型的输出质量会明显下滑。
 * @param embeddingModel 向量模型名，用于记忆检索。留空表示不做向量索引，
 *        记忆会退回到关键词打分（见 `MemoryRepository`）。它和对话模型共用同一套
 *        baseUrl / apiKey，因为绝大多数端点把两者放在同一个 `/v1` 下。
 * @param prePrompt 前置提示。**在所有其它内容之前注入**，位置比人设、记忆、
 *        待办都靠前。留空表示不注入。
 *        它的定位是「用户自己写的一段全局设定」：语气风格、角色扮演的前置条件、
 *        或者任何希望每轮都生效的约束。应用只负责把它原样放到最前面，
 *        不解释、不过滤、不评价内容 —— 那是用户对自己这台设备上这个模型的设定权。
 */
data class AiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float,
    val embeddingModel: String = "",
    val prePrompt: String = "",
) {
    /** 是否已经填够了发起请求所需的最小信息。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        /** 温度允许区间。滑块与校验共用这两个常量，避免两处各写一遍导致对不上。 */
        const val MIN_TEMPERATURE: Float = 0f
        const val MAX_TEMPERATURE: Float = 1.5f

        /** 默认温度。0.7 是通用对话的常见默认值，不偏不倚。 */
        const val DEFAULT_TEMPERATURE: Float = 0.7f
    }
}

/**
 * 一个可切换的配置预设。
 *
 * @param id 稳定唯一标识。界面上的横向切换、DataStore 里的选中项都用它。
 * @param name 展示名，用户可以改。
 * @param config 具体参数。
 */
data class AiPreset(
    val id: String,
    val name: String,
    val config: AiConfig,
)

/**
 * 内置预设与新建预设的工厂。
 *
 * 预置的三家覆盖了三种典型形态：
 *  - DeepSeek：国内直连的云端 OpenAI 兼容端点；
 *  - 本地 Ollama：**跑在同一台手机上**（127.0.0.1:11434），完全离线、不需要 Key，
 *    是这个应用在「不想把监督数据发出去」时唯一正确的选择；
 *  - OpenAI：标准参考实现。
 *
 * 每个预置只填 URL 与模型名，**绝不预填任何 Key** —— 预置里出现密钥是最典型的
 * 「示例代码被抄进生产」事故。
 */
object AiPresetDefaults {

    const val ID_DEEPSEEK = "preset-deepseek"
    const val ID_OLLAMA = "preset-ollama-local"
    const val ID_OPENAI = "preset-openai"

    /** 新建预设时使用的 id 前缀，实际 id 会在后面接一段随机串。 */
    const val ID_CUSTOM_PREFIX = "preset-custom"

    /** 新建预设的默认名字。 */
    const val CUSTOM_NAME = "自定义端点"

    /** 本地 Ollama 的默认地址。 */
    const val OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"

    /**
     * 首次启动时写入的三个预置。
     *
     * 顺序即界面上的展示顺序：把最可能被用到的 DeepSeek 放第一个。
     */
    fun defaults(): List<AiPreset> = listOf(
        AiPreset(
            id = ID_DEEPSEEK,
            name = "DeepSeek",
            config = AiConfig(
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "",
                model = "deepseek-chat",
                temperature = AiConfig.DEFAULT_TEMPERATURE,
                // DeepSeek 目前没有公开的 embeddings 接口，留空即走关键词召回。
                embeddingModel = "",
            ),
        ),
        AiPreset(
            id = ID_OLLAMA,
            name = "本地 Ollama",
            config = AiConfig(
                baseUrl = OLLAMA_BASE_URL,
                apiKey = "",
                model = "qwen2.5:7b",
                temperature = AiConfig.DEFAULT_TEMPERATURE,
                embeddingModel = "nomic-embed-text",
            ),
        ),
        AiPreset(
            id = ID_OPENAI,
            name = "OpenAI",
            config = AiConfig(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "",
                model = "gpt-4o-mini",
                temperature = AiConfig.DEFAULT_TEMPERATURE,
                embeddingModel = "text-embedding-3-small",
            ),
        ),
    )

    /** 默认选中的预置 id。 */
    fun defaultSelectedId(): String = ID_DEEPSEEK

    /** 造一个空的「自定义端点」预设。 */
    fun customPreset(id: String): AiPreset = AiPreset(
        id = id,
        name = CUSTOM_NAME,
        config = AiConfig(
            baseUrl = "",
            apiKey = "",
            model = "",
            temperature = AiConfig.DEFAULT_TEMPERATURE,
            embeddingModel = "",
        ),
    )

    /**
     * 内置预置的 id 集合。
     *
     * 用途：内置预置不允许删除 —— 删掉之后用户就没有可以「恢复默认」的锚点了。
     */
    val BUILT_IN_IDS: Set<String> = setOf(ID_DEEPSEEK, ID_OLLAMA, ID_OPENAI)
}
