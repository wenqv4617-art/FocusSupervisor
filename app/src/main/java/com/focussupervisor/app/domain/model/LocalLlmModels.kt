package com.focussupervisor.app.domain.model

/**
 * 端侧对话模型矩阵。
 *
 * ===========================================================================
 * 一、为什么是这四个，而不是「0.5B / 1.5B / 3B / 7B」四档
 * ===========================================================================
 * 原计划是 Qwen2.5 的 0.5B / 1.5B / 3B / 7B。但**3B 与 7B 在 MediaPipe 格式下
 * 不存在**：MediaPipe 只吃 `.task` 打包格式，而 litert-community（Google 官方
 * 托管方）只发布了 0.5B 与 1.5B 两个规格，3B / 7B 无论在哪家都只有 GGUF，
 * 那是另一套运行时（llama.cpp）的格式，读不了。
 *
 * Gemma 全系（2B / 3-4B / 3-12B）有 `.task`，但**被 Google 门控**：下载要先在
 * HuggingFace 上接受许可并带 token，做不到「点一下就开始下」。
 *
 * 所以这一版上的是**四个真实能下、能跑、校验值可核对**的模型：
 *
 * ```
 *   Qwen2.5-0.5B-Instruct          极速档   0.5B   522 MB   6GB 运存
 *   Qwen2.5-1.5B-Instruct          均衡档   1.5B  1524 MB   8GB 运存
 *   DeepSeek-R1-Distill-Qwen-1.5B  思考档   1.5B  1775 MB   8GB 运存
 *   Phi-4-mini-instruct            旗舰档   3.8B  3762 MB  12GB 运存
 * ```
 *
 * 另外留了一个**自定义模型**入口：将来 3B / 7B 的 `.task` 一发布，或者用户自己
 * 转换出了模型，填一个地址与校验值就能加进来，不需要等我们发版。
 *
 * ===========================================================================
 * 二、为什么下载源是 hf-mirror 而不是 github
 * ===========================================================================
 * GitHub Release 单个资产上限 2GB，这四个里有两个超了；而这个仓库的 Release
 * 也不该被塞进 7.5GB 的模型文件。
 *
 * 上游是 HuggingFace 的 `litert-community`，国内直连不通，所以用 hf-mirror
 * （国内可直连的只读镜像）。**镜像不可信也没关系** —— 每个模型都带 SHA-256，
 * 下载完必须核对，对不上就删掉重下。校验才是完整性的保证，来源不是。
 *
 * ===========================================================================
 * 三、每个模型都是「多预填充 + q8 量化 + 1280 KV 缓存」那一版
 * ===========================================================================
 * 同一份模型在 litert-community 上有好几个变体，选这一版的原因是：
 *
 *  - **multi-prefill**：支持把长提示词切成多段预填充。没有它，一段 1500 token
 *    的提示词会一次性灌进去，端侧延迟非常难看 —— 而这恰恰是我们在提示词里
 *    省吃俭用要保住的东西；
 *  - **q8**：8 位量化。f32 版本体积翻倍、速度更慢，而 q8 的质量损失在
 *    「说人话 + 守格式」这个任务上几乎看不出来；
 *  - **ekv1280**：1280 token 的 KV 缓存。听起来比 4096 那版小，但这正是我们要的 ——
 *    我们的端侧提示词预算硬卡在 1500~2048，1280 的缓存配上多预填充足够周转，
 *    而更大的缓存在手机上直接就是内存占用。4096 那版留给将来真需要长上下文时。
 */
data class LocalLlmModel(
    val modelId: String,
    val displayName: String,
    /** 参数量档位，例如 `0.5B`、`3.8B`。界面上的主标签。 */
    val parameterTier: String,
    /** 一句话定位，例如「极速秒回，低运存适配」。 */
    val tierLabel: String,
    val downloadUrl: String,
    /** 落盘文件名。 */
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    /** 建议的最低运行内存（GB）。低于这个值时界面会提示，但不阻止。 */
    val recommendedRamGb: Int,
    val description: String,
    /**
     * 交给 [com.focussupervisor.app.core.ai.prompt.PromptProfiles] 做风格判定的
     * 模型名。
     *
     * 单独一个字段而不是直接拿 [displayName] 用：判定逻辑靠的是名字里的参数量
     * 与系列特征（`qwen` / `r1` / `llama`），而展示名是给人看的、随时会改字。
     * 把两者绑在一起，某天改一句文案就会静默换掉整套提示词 —— 那种问题极难归因。
     */
    val promptModelName: String,
    /** 是否用户自己添加的。自定义模型不参与「推荐」文案。 */
    val isCustom: Boolean = false,
) {
    /** 体积的展示文案。 */
    val sizeLabel: String get() = formatSize(fileSizeBytes)
}

/** 内置的端侧对话模型清单。 */
object LocalLlmModels {

    /** 模型存放目录（相对 `filesDir`）。 */
    const val MODEL_DIR = "llm"

    /**
     * 极速档：Qwen2.5 0.5B。
     *
     * 这一档的存在意义是「在很旧的手机上也能跑」。它的实际能力边界很清楚：
     * 能听懂一句话、能照着格式吐字、能守住三句人设 —— 但指望它做推理是不现实的。
     * 所以提示词走 QWEN_MICRO 那一档（人设压到三句、给死输出范例）。
     */
    val QWEN_05B = LocalLlmModel(
        modelId = "qwen2.5-0.5b-instruct-q8",
        displayName = "Qwen2.5 0.5B Instruct",
        parameterTier = "0.5B",
        tierLabel = "极速档",
        downloadUrl = "https://hf-mirror.com/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        fileName = "Qwen2.5-0.5B-Instruct_q8_ekv1280.task",
        fileSizeBytes = 546_660_344L,
        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
        recommendedRamGb = 6,
        description = "秒回级响应，低运存设备也能跑。能守格式、能执行指令，但别指望它推理。",
        promptModelName = "qwen2.5:0.5b",
    )

    /**
     * 均衡档：Qwen2.5 1.5B。
     *
     * 这一档是**多数人的最优解**。它已经能同时守住人设、规则与指令格式，
     * 首字延迟在中端机上也还在可接受范围（约 1 秒）。
     */
    val QWEN_15B = LocalLlmModel(
        modelId = "qwen2.5-1.5b-instruct-q8",
        displayName = "Qwen2.5 1.5B Instruct",
        parameterTier = "1.5B",
        tierLabel = "均衡档",
        downloadUrl = "https://hf-mirror.com/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        fileName = "Qwen2.5-1.5B-Instruct_q8_ekv1280.task",
        fileSizeBytes = 1_597_913_616L,
        sha256 = "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0",
        recommendedRamGb = 8,
        description = "能力与速度平衡得最好的一档：语气、指令、规则都能稳住，首字约一秒。",
        promptModelName = "qwen2.5:1.5b",
    )

    /**
     * 思考档：DeepSeek-R1-Distill-Qwen 1.5B。
     *
     * 与均衡档同参数量，但**行为完全不同**：它会先输出一段思考链再回答。
     * 好处是「他为什么这么说」能被看到，追问的质量明显更高；
     * 代价是首字延迟里多了一段思考时间，而且必须严防它把指令写进思考块里
     * （见 `PromptProfiles.REASONING` 的注释）。
     */
    val R1_15B = LocalLlmModel(
        modelId = "deepseek-r1-distill-qwen-1.5b-q8",
        displayName = "DeepSeek-R1-Distill-Qwen 1.5B",
        parameterTier = "1.5B",
        tierLabel = "思考档",
        downloadUrl = "https://hf-mirror.com/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_q8_ekv1280.task",
        fileSizeBytes = 1_861_094_737L,
        sha256 = "31e6e3fdcb846e20e34f9f51b584f272b0869bf50bfd74dd2da03740854531f7",
        recommendedRamGb = 8,
        description = "会先想再说的蒸馏模型：追问质量更高，代价是首字要多等一段思考时间。",
        promptModelName = "deepseek-r1-distill-qwen-1.5b",
    )

    /**
     * 旗舰档：Phi-4-mini 3.8B。
     *
     * 原本这一档应该是 Qwen2.5 3B 或 7B，但如前所述它们没有 MediaPipe 格式。
     * Phi-4-mini 是能下到的里面最大、也是指令遵循最好的一档；
     * 它来自微软而不是 Qwen 系，所以提示词走的是通用写法（不套 Qwen 的专属特征）。
     *
     * 3.7GB 的体积 + 12GB 运存门槛意味着它只适合旗舰机。界面会把这一点说清楚，
     * 而不是让用户下完 3.7GB 才发现跑不动。
     */
    val PHI4_MINI = LocalLlmModel(
        modelId = "phi-4-mini-instruct-3.8b-q8",
        displayName = "Phi-4-mini 3.8B",
        parameterTier = "3.8B",
        tierLabel = "旗舰档",
        downloadUrl = "https://hf-mirror.com/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
        fileName = "Phi-4-mini-instruct_q8_ekv1280.task",
        fileSizeBytes = 3_944_275_882L,
        sha256 = "e494f9e827fbf47ac271d67dde77308f4a7683ad1ff630ab5bec926f17573b5f",
        recommendedRamGb = 12,
        description = "端侧能拿到的最强一档：逻辑与长程一致性明显更好，代价是 3.7GB 与 12GB 运存门槛。",
        promptModelName = "phi-4-mini-3.8b",
    )

    /** 全部内置模型，按体积从大到小 —— 界面直接照这个顺序排，轻的在上面。 */
    val ALL: List<LocalLlmModel> = listOf(QWEN_05B, QWEN_15B, R1_15B, PHI4_MINI)

    /** 界面上标记为「推荐」的那一个。 */
    val RECOMMENDED: LocalLlmModel = QWEN_15B

    /** 按 id 找。 */
    fun find(modelId: String): LocalLlmModel? = ALL.firstOrNull { it.modelId == modelId }

    /**
     * 由用户填写的自定义模型。
     *
     * ===========================================================================
     * 为什么值得留这个入口
     * ===========================================================================
     * 端侧模型的生态变化很快：今天 litert-community 只有 0.5B 与 1.5B，
     * 明天可能就上 3B / 7B；用户也可能自己量化转换出一个模型。
     * 没有这个入口，那些人只能等我们发一版应用 —— 而发版要走一遍完整的
     * CI 与安装流程，为了加一行 URL 不值得。
     *
     * 用户要填的三样东西里，**校验值是可以留空的**：留空时下载器会跳过校验，
     * 并在界面上明确标出「未校验」。不强制填写，是因为算 SHA-256 对普通用户
     * 门槛太高；但要让「这份文件没有被验证过」这件事一直可见，而不是悄悄放过。
     *
     * @param url 直链。必须是 MediaPipe 的 `.task` 格式。
     * @param sha256 期望摘要，可留空。
     */
    fun custom(
        displayName: String,
        url: String,
        fileSizeBytes: Long,
        sha256: String,
        recommendedRamGb: Int,
    ): LocalLlmModel = LocalLlmModel(
        modelId = "custom-${url.hashCode().toUInt().toString(16)}",
        displayName = displayName.ifBlank { "自定义模型" },
        parameterTier = "自定义",
        tierLabel = "自定义",
        downloadUrl = url,
        // 文件名从 URL 末段取，取不到就给一个固定名。带上 hashCode 是为了让
        // 两个不同 URL 的模型不会互相覆盖 —— 用户换一个地址再下，不该把上一个删掉。
        fileName = url.substringAfterLast('/').substringBefore('?')
            .takeIf { it.endsWith(".task") }
            ?: "custom-${url.hashCode().toUInt().toString(16)}.task",
        fileSizeBytes = fileSizeBytes,
        sha256 = sha256,
        recommendedRamGb = recommendedRamGb,
        description = "你自己添加的模型。如果它在这个应用里表现不对，多半是格式不是 MediaPipe 的 .task。",
        // 自定义模型的风格判定交给名字：名字里带 qwen / r1 / llama 就能被认出来。
        promptModelName = displayName,
        isCustom = true,
    )
}

/**
 * 字节数转人话。
 *
 * 放在这里而不是各界面各写一份：四五个地方都要显示模型体积，
 * 各自实现迟早会出现「1.5 GB」和「1536.0 MB」并排出现的情况。
 */
fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
