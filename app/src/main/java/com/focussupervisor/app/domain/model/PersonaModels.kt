package com.focussupervisor.app.domain.model

/**
 * 领域层：人设
 *
 * 两类人设分开建模，而不是塞进一个 `Persona` 里加个 `isAi` 开关 —— 它们的字段
 * 本来就不一样（AI 有性别与详细设定，用户没有），合成一个类只会得到一堆
 * 「AI 用不到、用户填不了」的空字段。
 */

/**
 * 性别。
 *
 * 加上 [UNSPECIFIED] 而不是只留男女两种：人设里的性别是**给模型看的设定**，
 * 不是给系统做统计的字段。用户没填的时候，正确的语义是「没设定」，而不是
 * 被默认成某一性别。
 */
enum class PersonaGender(val label: String) {
    UNSPECIFIED("未设定"),
    FEMALE("女"),
    MALE("男"),
    OTHER("其他"),
}

/**
 * AI 的人设。
 *
 * @param name 显示名。对话页顶栏、每条 AI 消息上方、以及注入提示词时都用它。
 * @param avatarPath 头像文件的**本地绝对路径**（已复制进应用私有目录）。
 *        存路径而不是存 Bitmap 或 base64：头像是几百 KB 的二进制，塞进
 *        Preferences DataStore 会让每次配置读写都拖着它走。
 * @param gender 性别设定，会影响模型自称与语气。
 * @param description 详细设定。原样注入提示词，是「人设」的主体。
 */
data class AiPersona(
    val name: String = DEFAULT_NAME,
    val avatarPath: String? = null,
    val gender: PersonaGender = PersonaGender.UNSPECIFIED,
    val description: String = "",
) {
    /** 详细设定是否为空。为空时提示词里就不注入这一段，省 token。 */
    val hasDescription: Boolean get() = description.isNotBlank()

    companion object {
        const val DEFAULT_NAME = "FocusSupervisor"
    }
}

/**
 * 用户的人设。
 *
 * 只保留姓名与头像 —— 用户不需要给自己写一份「设定」交给模型，那反而像是
 * 在替模型写提示词。用户在对话里呈现出来的样子，就是这两样。
 */
data class UserPersona(
    val name: String = DEFAULT_NAME,
    val avatarPath: String? = null,
) {
    companion object {
        const val DEFAULT_NAME = "我"
    }
}

/**
 * 会话双方的人设快照。
 *
 * 单独打成一个包，是因为界面渲染每一条消息时都要同时拿到两边 —— 没有它就得往
 * 每个气泡传两个参数，或者让气泡去读全局状态。打包之后消息列表只需要往下传一个值。
 */
data class PersonaPair(
    val ai: AiPersona = AiPersona(),
    val user: UserPersona = UserPersona(),
)
