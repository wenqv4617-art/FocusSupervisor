package com.focussupervisor.app.domain.model

/**
 * 识图的来源。
 *
 * ===========================================================================
 * 这个二选一和「向量模型」「对话模型」那两处是同一个设计
 * ===========================================================================
 * ```
 *   在线多模态 API   把截图发出去，由云端模型描述   —— 需要网、需要钱、数据出手机
 *   本地端侧识图     在本机把图变成一句事实         —— 离线、免费、数据不出手机
 * ```
 *
 * 两者同样**互斥、不自动回退**。对识图这条链路，不回退还有一个额外理由：
 * 用户选本地识图，多半正是因为他**不想让屏幕内容离开这台手机** ——
 * 那种情况下偷偷回退到云端，性质上和背叛没有区别。
 */
enum class VisionSource {
    /** 云端多模态模型。 */
    ONLINE_API,

    /** 本机识图。 */
    LOCAL,
    ;

    companion object {
        fun sanitize(name: String): VisionSource =
            entries.firstOrNull { it.name == name } ?: ONLINE_API
    }
}

/**
 * 端侧识图的两个备选引擎。
 *
 * ===========================================================================
 * 为什么把「不可用」写进枚举，而不是干脆不提供这一项
 * ===========================================================================
 * [MICRO_VLM] 现在**没有可用的运行时**：候选模型（SmolVLM-256M、FastVLM-0.5B）
 * 只有 `.tflite` / `.litertlm` 两种格式，后者要 LiteRT-LM，而它在 Google Maven
 * 与 Maven Central 上都查不到。
 *
 * 把它从界面上删掉当然更干净。但那会让「为什么没有画面理解」变成一个只有翻代码
 * 才能回答的问题，而且等运行时一发布，还得先把界面改回来才能接。
 *
 * 所以留着它，并让 [unavailableReason] **明明白白写在卡片上**。一个写着
 * 「暂不可用 · 运行时未发布」的选项，比一个干脆不存在的选项诚实，也比一个
 * 点下去没反应的按钮好得多。
 *
 * @param isAvailable 现在能不能用。界面据此决定是显示「开始使用」还是「暂不可用」。
 * @param unavailableReason 不可用的原因，直接显示给用户。可用时为 null。
 */
enum class VisionEngineKind(
    val label: String,
    val tagline: String,
    val detail: String,
    val isAvailable: Boolean,
    val unavailableReason: String? = null,
) {
    /**
     * 备选 A：ML Kit 中文文本识别。
     *
     * 只有约 10MB 原生组件、毫秒级、零大模型负担。它能做的是「把屏幕上的字读出来」，
     * 再从中提炼前台标题与关键文本 —— 对绝大多数场景其实已经够了，
     * 因为那些界面上**全是字**。
     */
    ML_KIT_OCR(
        label = "ML Kit 极速文本萃取",
        tagline = "默认 · 无需下载 · 毫秒响应 · 零耗电",
        detail = "把屏幕上的文字读出来，提炼成一句事实。刷视频、看小说、逛购物这类" +
            "「满屏都是字」的场景完全够用；但它读不懂没有文字的纯画面。",
        isAvailable = true,
    ),

    /**
     * 备选 B：微型端侧视觉语言模型。
     *
     * 能理解游戏画面、视频内容与无文字富媒体。**目前没有运行时**。
     */
    MICRO_VLM(
        label = "微型端侧 VLM 画面理解",
        tagline = "需下载独立模型 · 支持游戏与视频识别",
        detail = "真正的画面理解：能看懂没有文字的纯图像内容。" +
            "模型体积 350MB~1.3GB，只有在下载之后才会占用内存。",
        isAvailable = false,
        unavailableReason = "当前版本暂不可用：端侧 VLM 的推理运行时（LiteRT-LM）" +
            "尚未发布到 Maven 仓库，模型文件也已换成它专属的 .litertlm 格式，" +
            "现有的推理库读不了。运行时一发布即可接上，无需等一次应用改版。",
    ),
    ;

    companion object {
        fun sanitize(name: String): VisionEngineKind =
            entries.firstOrNull { it.name == name } ?: ML_KIT_OCR
    }
}
