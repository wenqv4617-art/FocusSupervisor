package com.focussupervisor.app.core

import android.content.Context
import com.focussupervisor.app.core.ai.ConversationEngine
import com.focussupervisor.app.core.network.OpenAiCompatibleClient
import com.focussupervisor.app.core.permission.PermissionManager
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.data.repository.AiConfigRepository
import com.focussupervisor.app.data.repository.AppLabelResolver
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.CriticalPackageResolver
import com.focussupervisor.app.data.repository.DataStoreAiConfigRepository
import com.focussupervisor.app.data.repository.ConversationRepository
import com.focussupervisor.app.data.repository.DataStoreAppPolicyRepository
import com.focussupervisor.app.data.repository.DataStoreConversationRepository
import com.focussupervisor.app.data.repository.DataStoreGazeRepository
import com.focussupervisor.app.data.repository.DataStoreMemoryRepository
import com.focussupervisor.app.data.repository.DataStorePersonaRepository
import com.focussupervisor.app.data.repository.DataStoreTimelineRepository
import com.focussupervisor.app.data.repository.GazeRepository
import com.focussupervisor.app.data.repository.InMemoryPromptCacheRepository
import com.focussupervisor.app.data.repository.MemoryRepository
import com.focussupervisor.app.data.repository.PersonaRepository
import com.focussupervisor.app.data.repository.PromptCacheRepository
import com.focussupervisor.app.data.repository.TimelineRepository
import com.focussupervisor.app.data.repository.buildBuiltInWhitelist
import com.focussupervisor.app.data.mock.MockChatData
import com.focussupervisor.app.domain.model.AiPresetDefaults
import com.focussupervisor.app.ui.overlay.LockOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程内的依赖容器（手写 Service Locator）。
 *
 * ===========================================================================
 * 为什么是手写容器，而不是 Hilt / Koin
 * ===========================================================================
 * 本应用需要共享依赖的地方只有几种，而且它们**都不走常规的构造注入路径**：
 *
 *  - `FocusAccessibilityService`：由系统实例化，我们无法参与它的构造；
 *  - `LockOverlayController`：生命周期比任何 Activity 都长；
 *  - `ChatViewModel` / `AiConfigViewModel`：需要共享同一份仓库实例，
 *    否则一个界面改了配置、另一个界面看不到。
 *
 * 引入 Hilt 的代价是 KSP、注解处理器、额外的构建时间，以及一套「为什么这个注入能用
 * 那个不能用」的隐式规则；而收益在这个规模下只是省掉本文件。等到依赖图真的长到
 * 十来个节点时再换，那时替换点也正好只有这一个文件。
 *
 * ===========================================================================
 * 生命周期
 * ===========================================================================
 * 由 [com.focussupervisor.app.FocusSupervisorApp] 在 `onCreate` 里创建，存活时间
 * 等于进程寿命。所有成员都只持有 applicationContext，不会泄漏任何界面对象。
 * [appScope] 也随进程存活 —— 这是**刻意**的：DataStore 的监听与过期豁免清理必须
 * 活得比任何界面都久，无障碍服务在用户从没打开过界面的情况下也要能正常判定。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 进程级协程作用域。
     *
     * 用 `Dispatchers.Default` 而不是 Main：挂在这个作用域上的都是磁盘与后台任务
     * （DataStore 监听、过期清理），没有任何一处需要主线程。
     * `SupervisorJob` 保证某一个任务失败不会连坐其它任务。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 持久化入口。策略仓库与配置仓库共用同一个 DataStore 文件。 */
    private val preferences = AppPreferencesDataSource(appContext)

    /**
     * 监督时间线：带时间的「发生过什么」。
     *
     * 必须**排在下面几个仓库前面**构造：策略、对话、记忆三个仓库都要往它里面记事件，
     * 而它们的构造函数会持有这个引用。
     */
    val timeline: TimelineRepository = DataStoreTimelineRepository(
        preferences = preferences,
        scope = appScope,
    )

    /** 监督策略仓库：白名单、待办、系统播报。 */
    val policy: AppPolicyRepository = DataStoreAppPolicyRepository(
        context = appContext,
        preferences = preferences,
        scope = appScope,
        timeline = timeline,
    )

    /** AI 端点配置仓库：预设、选中项、当前生效配置。 */
    val aiConfig: AiConfigRepository = DataStoreAiConfigRepository(
        preferences = preferences,
        scope = appScope,
    )

    /** 人设：AI 与用户各自的姓名、头像、设定。 */
    val personas: PersonaRepository = DataStorePersonaRepository(
        context = appContext,
        preferences = preferences,
        scope = appScope,
    )

    /** 对话历史。 */
    val conversation: ConversationRepository = DataStoreConversationRepository(
        preferences = preferences,
        scope = appScope,
        timeline = timeline,
    )

    /** 记忆与向量索引。 */
    val memory: MemoryRepository = DataStoreMemoryRepository(
        preferences = preferences,
        scope = appScope,
        timeline = timeline,
    )

    /**
     * 注视监控：配置（落盘）+ 运行期状态（内存）。
     *
     * 由前台服务 [com.focussupervisor.app.service.FocusMonitorService] 写状态、
     * 由界面读；配置反过来：界面写，服务读。
     */
    val gaze: GazeRepository = DataStoreGazeRepository(
        preferences = preferences,
        scope = appScope,
    )

    /** 权限状态检查与系统设置跳转。 */
    val permissions: PermissionManager = PermissionManager(appContext)

    /**
     * 最近一次请求的上下文缓存命中情况。
     *
     * 纯内存：它是「刚才那一次怎么样」，重启后没有意义。放在容器里是为了让引擎写、
     * 让「AI 配置」面板读。
     */
    val promptCache: PromptCacheRepository = InMemoryPromptCacheRepository()

    /**
     * 全屏遮罩控制器。
     *
     * 放在容器里而不是某个 Service 里，是因为它有两个使用方：无障碍服务的真实拦截，
     * 以及界面上「强制锁定测试」的模拟弹窗。两者必须是同一个实例，否则会出现
     * 「测试的遮罩把自己盖住了，真实拦截却以为没遮罩」这种对不上的状态。
     */
    val lockOverlay: LockOverlayController = LockOverlayController(appContext)

    /**
     * OpenAI 兼容端点客户端。
     *
     * 全局一个实例：OkHttp 自己维护连接池与线程池，每次请求新建客户端等于把池化
     * 全扔掉。
     */
    val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient()

    /**
     * 对话编排层：把「发送 → 召回记忆 → 组装提示词 → 调模型 → 解析指令 → 上屏」
     * 这条链路收在一个地方。界面只调用它，不碰网络、不碰提示词。
     */
    val conversationEngine: ConversationEngine = ConversationEngine(
        client = openAiClient,
        policy = policy,
        memory = memory,
        conversation = conversation,
        personas = personas,
        aiConfig = aiConfig,
        timeline = timeline,
        cache = promptCache,
        gaze = gaze,
    )

    init {
        // 首次启动时把内置白名单与默认预置一次性灌进 DataStore。
        // 放在容器初始化里而不是某个仓库里，理由见 AppPreferencesDataSource.seedIfNeeded：
        // 让两个仓库各自判断「我该不该初始化」会产生竞态。
        appScope.launch {
            val labelResolver = AppLabelResolver(appContext)
            val criticalResolver = CriticalPackageResolver(appContext)
            val builtInWhitelist = buildBuiltInWhitelist(
                selfPackage = appContext.packageName,
                labelResolver = labelResolver,
                criticalPackages = criticalResolver.resolve(),
            )
            val seeded = preferences.seedIfNeeded(
                builtInWhitelist = builtInWhitelist,
                defaultPresets = AiPresetDefaults.defaults(),
                defaultSelectedPresetId = AiPresetDefaults.defaultSelectedId(),
            )

            // 开场白只在**真正的首次启动**写入，靠 seedIfNeeded 的返回值判断。
            // 不用「对话是否为空」判断：用户可能自己清空过聊天记录，
            // 那不代表他想再看一遍开场白。
            if (seeded) {
                preferences.replaceMessages(MockChatData.initialMessages())
            }
        }

        // 后台定期给还没向量化的记忆与对话补向量。
        // 放在这里而不是界面里：用户不打开记忆面板的时候，索引也应该继续追上。
        appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(EMBED_SWEEP_INTERVAL_MILLIS)
                conversationEngine.embedPendingMemories()
            }
        }
    }

    private companion object {
        /** 后台补向量的周期。5 分钟一次，对个人使用强度足够。 */
        const val EMBED_SWEEP_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
