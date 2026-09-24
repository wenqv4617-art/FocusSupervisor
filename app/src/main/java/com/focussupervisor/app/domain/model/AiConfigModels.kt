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
        ),
    )

    /**
     * 内置预置的 id 集合。
     *
     * 用途：内置预置不允许删除 —— 删掉之后用户就没有可以「恢复默认」的锚点了。
     */
    val BUILT_IN_IDS: Set<String> = setOf(ID_DEEPSEEK, ID_OLLAMA, ID_OPENAI)
}

/**
 * 向量模型配置。**与对话配置完全独立。**
 *
 * ===========================================================================
 * 为什么要拆开
 * ===========================================================================
 * 合在一份配置里看起来省事，但现实是这两件事经常不在一家：
 *  - DeepSeek 没有公开的 embeddings 接口，用户只能在别处（OpenAI、硅基流动、
 *    本地 Ollama）单独配一个向量服务；
 *  - 中转站往往只代理对话模型，不代理 embeddings。
 *
 * 硬塞进同一份配置的后果是：用户为了让记忆用上向量，不得不把对话模型也切到
 * 那家能提供 embeddings 的服务上 —— 这不是他能接受的取舍。
 *
 * 所以它是独立的一份配置、独立的入口、独立的页面。唯一共用的东西是
 * 「怎么发 HTTP 请求」，那在 `OpenAiCompatibleClient` 里。
 *
 * @param baseUrl 向量服务的地址。留空表示不使用向量（记忆退回关键词匹配）
 * @param apiKey 向量服务的 Key
 * @param model 向量模型名，例如 text-embedding-3-small / bge-m3 / nomic-embed-text
 */
data class EmbeddingConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /**
     * 是否改用**下载到本机的**向量模型。
     *
     * 它与上面三项是**互斥的两条路**，而不是「先试本地、失败再退回在线」：
     * 本地模型一旦下好就是确定的、离线的、不花钱的，用户明确选了它就应该只走它 ——
     * 自动回退会让「为什么这次变慢了 / 这次花了钱」变成一个无法解释的现象。
     *
     * 模型文件不存在时，本地路径直接报错并提示去下载，不会偷偷去调在线端点。
     */
    val useLocalModel: Boolean = false,
) {
    /**
     * 是否已经填够了发起请求所需的最小信息。
     *
     * 走本地模型时不看 URL 与模型名 —— 那是另一条路径，它需要的是模型文件。
     */
    val isUsable: Boolean
        get() = if (useLocalModel) true else isRemoteUsable

    /** 在线端点是否配置完整。界面用它决定「在线」那一栏要不要提示未配置。 */
    val isRemoteUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        /** 本地 Ollama 的默认地址，作为新建时的建议值。 */
        const val OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"

        /** 本地 Ollama 常用的向量模型。 */
        const val OLLAMA_DEFAULT_MODEL = "nomic-embed-text"
    }
}

/**
 * 一个可下载到本机的向量模型。
 *
 * ===========================================================================
 * 为什么值得为它加十几 MB
 * ===========================================================================
 * 记忆检索是这个应用里**唯一一个每几分钟就要跑一次**的模型调用（见 `AppContainer`
 * 里那个五分钟一轮的补向量循环）。把它挂在在线端点上意味着三件事：
 *
 *  - **没网就不能用** —— 而「管住自己不刷手机」这件事，恰恰经常发生在想断网的时候；
 *  - **每条记忆都要发一次请求** —— 长期看是一笔持续的支出；
 *  - **记忆是最私密的一类数据** —— 他在坚持什么、反复失败在什么地方。
 *    为它单独找一家可信任的端点，比自己在本机算要难得多。
 *
 * all-MiniLM-L6-v2 量化版是这件事上性价比很高的一档：384 维、22MB，
 * 在手机 CPU 上单条文本是几十毫秒量级，完全离线。
 *
 * @param fileName 落在 `filesDir/models/` 下的文件名。
 * @param downloadUrl 公开下载地址。
 * @param sizeBytes 期望字节数。用于进度显示与完整性判断。
 * @param sha256 期望摘要。下载完必须核对 —— 一个被截断的 22MB 模型会让
 *        ONNX Runtime 抛出一句极难归因的错，不如在这里就拦住。
 * @param dimension 向量维度。维度对不上的历史向量必须被丢弃重建，
 *        否则余弦相似度会静默返回 0（见 `TextVectorizer.cosine`）。
 */
data class LocalEmbeddingModel(
    val id: String,
    val name: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val dimension: Int,
    val description: String,
)

/**
 * 内置的本地模型清单。
 *
 * 模型托管在**本仓库自己的公开 Release** 上，而不是模型作者的其他仓库：
 * 公开的 Release 资产任何人都能下，不需要 token，也不会随另一个仓库的可见性变化
 * 而失效。出处写在 README 里。
 */
object LocalEmbeddingModels {

    /** 量化版 all-MiniLM-L6-v2。 */
    val MINI_LM = LocalEmbeddingModel(
        id = "all-MiniLM-L6-v2-int8",
        name = "all-MiniLM-L6-v2（量化版）",
        fileName = "model_quantized.onnx",
        downloadUrl = "https://github.com/wenqv4617-art/FocusSupervisor/releases/download/vector-model-v1/model_quantized.onnx",
        sizeBytes = 22_972_370L,
        sha256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1",
        dimension = 384,
        description = "384 维、约 22MB。中英文都可用，完全离线，不产生任何请求。",
    )

    /** 全部可选模型。目前只有一个，列成清单是为了将来加模型时不必动界面。 */
    val ALL: List<LocalEmbeddingModel> = listOf(MINI_LM)

    /** 默认模型。 */
    val DEFAULT: LocalEmbeddingModel = MINI_LM

    /** 模型存放目录名（相对 `filesDir`）。 */
    const val MODEL_DIR = "models"

    /** 分词器词表在 assets 里的路径。 */
    const val VOCAB_ASSET = "vocab.txt"

    /**
     * 分词后最多保留多少个 token。
     *
     * 256 是 all-MiniLM-L6-v2 的 max_seq_length。超出的部分被截掉而不是报错 ——
     * 一段记忆写到 256 个 token 以上，真正有用的信息已经在里面了。
     */
    const val MAX_SEQ_LENGTH = 256
}

/**
 * 视觉模型配置。**同样与对话配置完全独立。**
 *
 * ===========================================================================
 * 为什么必须是独立的一份
 * ===========================================================================
 * 原因比向量模型更硬：**DeepSeek 的对话模型不接受图片**。用户的主力端点几乎一定
 * 是 DeepSeek，而注视监控需要「看一眼屏幕截图，说一句他在干什么」这种多模态能力。
 * 把两者混在一份配置里的唯一后果，就是用户为了用上注视监控而被迫换掉对话模型 ——
 * 那不是取舍，是功能互斥。
 *
 * 所以它独立存储、独立入口、独立页面，用户可以在 DeepSeek 之外再配一家
 * （通义千问 VL、智谱 GLM-4V、GPT-4o mini，或任何代理了多模态模型的中转站）。
 *
 * @param baseUrl 视觉服务的地址。留空表示不做视觉描述 —— 注视监控仍然工作，
 *        只是不截屏、不产生描述。
 * @param apiKey 视觉服务的 Key
 * @param model 视觉模型名，例如 qwen-vl-plus / glm-4v-flash / gpt-4o-mini
 * @param prompt 让模型看图时回答什么。默认问「他在做什么」，用户可以按自己的
 *        监督目标改写（例如「他是不是在打游戏」）。
 */
data class VisionConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val prompt: String = DEFAULT_PROMPT,
) {
    /** 三项都填了才算配置完整。prompt 有默认值，不参与判断。 */
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        /**
         * 默认提问。
         *
         * 措辞刻意约束了长度与格式：回答会被写进时间线、再进 AI 的上下文，
         * 一段三百字的散文会把整段提示词挤掉。要一句话、要具体、不要评价。
         */
        const val DEFAULT_PROMPT: String =
            "用一句不超过 40 字的中文说明：画面里的人正在做什么（在用什么应用、做什么事）。" +
                "只描述你看到的事实，不要评价，不要猜测他的想法。"

        const val MAX_PROMPT_LENGTH = 500
    }
}
