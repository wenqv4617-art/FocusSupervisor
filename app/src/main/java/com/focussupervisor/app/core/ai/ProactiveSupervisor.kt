package com.focussupervisor.app.core.ai

import android.util.Log
import com.focussupervisor.app.core.notify.ProactiveNotifier
import com.focussupervisor.app.core.time.TimeNarrator
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.GazeRepository
import com.focussupervisor.app.data.repository.TimelineRepository
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.TimelineKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主动盘问引擎 —— 让 AI 第一次可以不等用户开口。
 *
 * ===========================================================================
 * 一条铁律：只在他正在注视屏幕的时候开口
 * ===========================================================================
 * 这是整个引擎的设计前提，不是一条附加规则。
 *
 * 理由很直接：**不在场时说的话，等于对着空房间说话。** 他看不到、不会回，
 * 而这一次模型调用还照付。更糟的是，等他十分钟后回到屏幕前，会话里躺着一句
 * 「你刚才为什么又打开小红书」—— 那件事已经过去了，这句话只会让他觉得莫名其妙。
 *
 * 由此推出三条必须一起成立的推论，缺一条这个引擎就是错的：
 *
 * 1. **触发 != 开口。** 触发源只负责登记「该问一次」，登记进待问队列；
 *    真正开口由闸门决定。外援规格里把两者合成一件事（触发即发消息）是错的。
 * 2. **待问条目会过期。** 他 40 分钟前被拦了三次，现在才看屏幕 —— 问它没有意义。
 *    没有 TTL，队列会攒一堆陈年旧事，等他终于出现时一次问一个。默认 30 分钟。
 * 3. **注视监控没开 = 无法判断在场 = 不主动盘问。** 这是必然推论，不是偷懒：
 *    没有摄像头就不知道他看没看屏幕。界面与 README 都必须把这一点写明白，
 *    否则用户会以为功能坏了。
 *
 * ===========================================================================
 * 四类触发源（注意第二类的正确语义）
 * ===========================================================================
 * ```
 *   RAMPAGE   10 分钟内同一个未豁免应用被拦 ≥3 次   → 他正在硬扛，人就在当场
 *   FATIGUE   连续注视屏幕达到 60 分钟              → 人就在屏幕前
 *   RETURNED  离开 ≥15 分钟之后又回来               → 问「刚才去哪了」
 *   TODO_DUE  待办将在 30 分钟内到期且未完成        → 人可能不在，入队等
 * ```
 * **RETURNED 与"离开超过 15 分钟"是两回事。** 外援规格写的是后者，但那件事发生时
 * 他人不在，闸门根本不会放行 —— 那条触发永远只会堆积到过期。正确的触发点是他
 * **回来**的那一刻。
 *
 * **FATIGUE 用 60 分钟而不是 45 分钟**：45 分钟那档已经由注视服务发了一条系统胶囊
 * （事实播报），如果盘问也在同一刻发出，用户会同时收到一句话和一条通知。错开之后
 * 形成递进：20/30/45 分钟是「告诉事实」，60 分钟是「AI 真的开口」。
 *
 * ===========================================================================
 * 线程
 * ===========================================================================
 * 引擎本身只做判断（同步、无 IO）；模型推理与落盘全部在注入的协程作用域里跑，
 * 与无障碍主线程完全无关。
 */
class ProactiveSupervisor(
    private val policy: AppPolicyRepository,
    private val gaze: GazeRepository,
    private val timeline: TimelineRepository,
    private val engine: ConversationEngine,
    private val notifier: ProactiveNotifier,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一条待问的问题。触发源只登记，不开口。 */
    private data class PendingQuestion(
        val kind: TriggerKind,
        val reason: String,
        val queuedAtMillis: Long,
        /** 这一条在这个时刻之后就没意义了，由各触发源自己给。 */
        val expiresAtMillis: Long,
    )

    private enum class TriggerKind { RAMPAGE, FATIGUE, RETURNED, TODO_DUE }

    private val pending = mutableListOf<PendingQuestion>()
    private val deliverMutex = Mutex()

    private var lastAskAtMillis = 0L
    private var currentDayKey = ""
    private var delivering = false

    /** 上一次「人不在」是从什么时候开始的。用于识别「离开又回来」。 */
    private var awaySinceMillis = 0L

    /** 已经为哪一次连续注视问过疲劳了，避免同一轮里反复入队。 */
    private var fatigueAskedForSessionStart = 0L

    /** 已经为哪个待办问过临期了。 */
    private val askedTodoIds = mutableSetOf<String>()

    /**
     * 启动。由 [com.focussupervisor.app.core.AppContainer] 在进程启动时调用一次。
     */
    fun start() {
        currentDayKey = TimeNarrator.dayKey(clock())

        observeGaze()
        observeTimeline()
        startTicker()
    }

    // -----------------------------------------------------------------------
    // 触发源
    // -----------------------------------------------------------------------

    /**
     * 视线相关的触发，同时也是**闸门**本身。
     *
     * 每一次注视状态变化都会走到这里：既用来登记疲劳/归来两类触发，
     * 也用来在「他正在看」的那些时刻尝试投递。
     */
    private fun observeGaze() {
        scope.launch {
            gaze.status.collect { status ->
                val now = clock()
                when (status.state) {
                    GazeState.LOOKING -> {
                        registerReturnedIfNeeded(now)
                        registerFatigueIfNeeded(status.continuousFocusMillis, now)
                        tryDeliver()
                    }

                    GazeState.AWAY, GazeState.NO_FACE -> {
                        // 只在「刚开始不在」的时候记一次起点，否则每帧都会覆盖它。
                        if (awaySinceMillis == 0L) awaySinceMillis = now
                    }

                    GazeState.OFF -> {
                        // 功能被关掉：队列里那些问题失去了判断依据，清掉。
                        pending.clear()
                        awaySinceMillis = 0L
                    }

                    GazeState.STARTING, GazeState.ERROR -> Unit
                }
            }
        }
    }

    /**
     * 拦截狂暴：滑动窗口里同一个应用被拦太多次。
     *
     * 用「时间线里最近 10 分钟的 APP_BLOCKED」而不是自己维护计数器：时间线本来
     * 就是这件事的权威记录，再维护一份必然会出现两边对不上的情况。
     */
    private fun observeTimeline() {
        scope.launch {
            timeline.events.collect { events ->
                val now = clock()
                val since = now - RAMPAGE_WINDOW_MILLIS

                val worst = events.asSequence()
                    .filter { it.kind == TimelineKind.APP_BLOCKED && it.atMillis >= since }
                    .groupingBy { it.title.ifBlank { it.detail } }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?: return@collect

                if (worst.value < RAMPAGE_THRESHOLD) return@collect

                val oldest = events.firstOrNull {
                    it.kind == TimelineKind.APP_BLOCKED && it.atMillis >= since &&
                        (it.title.ifBlank { it.detail }) == worst.key
                }?.atMillis ?: now
                val minutes = ((now - oldest) / 60_000L).coerceAtLeast(1L)

                enqueue(
                    kind = TriggerKind.RAMPAGE,
                    reason = "他刚才在 $minutes 分钟里连续 ${worst.value} 次试图打开「${worst.key}」，" +
                        "每一次都被系统拦下了。他现在还在硬扛。",
                    ttlMillis = RAMPAGE_TTL_MILLIS,
                )
            }
        }
    }

    /**
     * 定时轮询：只负责待办临期。
     *
     * 每 5 分钟一次而不是 15 分钟：判据是「距截止不足 30 分钟」，5 分钟的粒度
     * 已经足够粗，而代价只是一次内存里的列表扫描（没有 IO）。
     */
    private fun startTicker() {
        scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                registerTodoDueIfNeeded()
                // 定时轮询也顺便试一次投递：人可能一直盯着屏幕，而状态没有变化过，
                // 那种情况下 gaze 的 collect 不会再触发。
                tryDeliver()
            }
        }
    }

    // -----------------------------------------------------------------------
    // 登记
    // -----------------------------------------------------------------------

    private fun registerReturnedIfNeeded(now: Long) {
        val awaySince = awaySinceMillis
        awaySinceMillis = 0L
        if (awaySince == 0L) return

        val awayMillis = now - awaySince
        if (awayMillis < RETURN_AFTER_AWAY_MILLIS) return

        enqueue(
            kind = TriggerKind.RETURNED,
            reason = "他离开了大约 ${awayMillis / 60_000L} 分钟，刚刚回到屏幕前。",
            ttlMillis = RETURNED_TTL_MILLIS,
        )
    }

    private fun registerFatigueIfNeeded(continuousFocusMillis: Long, now: Long) {
        if (continuousFocusMillis < FATIGUE_THRESHOLD_MILLIS) return

        // 同一轮连续注视只问一次。用「本轮开始时间」做标记：
        // 连续时长每次归零再涨上来，说明是新一轮。
        val sessionStart = now - continuousFocusMillis
        if (fatigueAskedForSessionStart == sessionStart) return
        fatigueAskedForSessionStart = sessionStart

        enqueue(
            kind = TriggerKind.FATIGUE,
            reason = "他已经连续注视屏幕 ${continuousFocusMillis / 60_000L} 分钟，中间没有停过。",
            ttlMillis = FATIGUE_TTL_MILLIS,
        )
    }

    private fun registerTodoDueIfNeeded() {
        val now = clock()
        policy.todos.value
            .filter { !it.isDone }
            .filter { it.id !in askedTodoIds }
            .forEach { todo ->
                val remaining = todo.plannedAtMillis - now
                if (remaining > TODO_DUE_WINDOW_MILLIS) return@forEach

                // 已经过期超过宽限期的就不再问了：那时候该说的是「已经逾期」，
                // 而那句话由提示词里的待办段落负责，不需要专门盘问一次。
                if (remaining < -TODO_OVERDUE_GRACE_MILLIS) {
                    askedTodoIds += todo.id
                    return@forEach
                }

                askedTodoIds += todo.id
                val minutes = (remaining / 60_000L).coerceAtLeast(0L)
                enqueue(
                    kind = TriggerKind.TODO_DUE,
                    reason = "待办「${todo.title}」还有 $minutes 分钟到期，现在还没完成。",
                    // 过了截止时间再加一点宽限就没意义了，所以有效期跟着截止时间走。
                    ttlMillis = (remaining + TODO_OVERDUE_GRACE_MILLIS).coerceAtLeast(0L),
                )
            }
    }

    /**
     * 入队。同一类只保留最新的一条，队列也有上限。
     *
     * 「同一类只留最新的」是有意的：十分钟内被拦三次会连续触发多轮，
     * 但用户需要听到的只是「你连着开了三次」，不是三条一样的盘问。
     */
    private fun enqueue(kind: TriggerKind, reason: String, ttlMillis: Long) {
        val now = clock()
        pending.removeAll { it.kind == kind }
        pending += PendingQuestion(
            kind = kind,
            reason = reason,
            queuedAtMillis = now,
            expiresAtMillis = now + ttlMillis,
        )

        // 队列上限：正常情况最多两三条，超出说明判断出了问题，
        // 保留最新的几条比无限增长好。
        while (pending.size > MAX_PENDING) pending.removeAt(0)
    }

    // -----------------------------------------------------------------------
    // 投递
    // -----------------------------------------------------------------------

    /**
     * 尝试投递一条待问。**这里是唯一的开口点。**
     *
     * 四道闸门依次是：正在注视 → 队列非空且未过期 → 冷却时间已过 → 同一时刻只有
     * 一次投递在进行。任何一道不通过就原样留在队列里等下一次机会（或过期消失）。
     */
    private fun tryDeliver() {
        if (delivering) return
        delivering = true

        scope.launch {
            try {
                deliverMutex.withLock { deliverLocked() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "主动盘问投递失败", t)
            } finally {
                delivering = false
            }
        }
    }

    private suspend fun deliverLocked() {
        rolloverDayIfNeeded()

        val now = clock()
        pending.removeAll { it.expiresAtMillis <= now }
        if (pending.isEmpty()) return

        // 闸门一：他现在必须正在看屏幕。
        if (gaze.status.value.state != GazeState.LOOKING) return

        // 闸门二：冷却。
        if (now - lastAskAtMillis < COOLDOWN_MILLIS) return

        val question = pending.removeAt(0)
        lastAskAtMillis = now

        Log.i(TAG, "主动盘问（${question.kind}）：${question.reason}")

        timeline.record(
            kind = TimelineKind.PROACTIVE_ASK,
            title = question.reason.take(80),
            detail = question.kind.name.lowercase(),
            atMillis = now,
        )

        when (engine.askProactively(question.reason)) {
            is SendOutcome.Sent -> notifier.onProactiveMessage()
            SendOutcome.NotConfigured -> Unit   // 没配端点，静默：界面里已经到处在提示了
            is SendOutcome.Failed -> Unit       // 失败已经由引擎写成系统胶囊了
        }
    }

    /**
     * 跨天处理。
     *
     * ===========================================================================
     * 第二天哪些东西该重置、哪些不该
     * ===========================================================================
     * **要清空：待问队列。** 这是跨天最重要的一条 —— 昨天攒下的「该问一次」今天
     * 问出来只会让人莫名其妙（「你昨天为什么……」）。队列本来就有 TTL，但跨天是
     * 一个更硬、更明确的边界。
     *
     * **不重置：冷却时间。** 它是防刷机制，不是每日配额。零点刚过不该额外多一次盘问。
     *
     * **不重置：待办。** 没完成就是没完成，跨天不会让它消失（面板与提示词里会显示
     * 「已逾期 N 天」）。这里只清掉「已经问过临期」的标记，否则那个待办改天再临期
     * （用户改了截止时间）就再也不会被问。
     *
     * **不重置：连续注视时长。** 连续盯了 45 分钟就是 45 分钟，跟有没有跨过零点无关
     * —— 那件事由注视服务持有，它自己也只在「人离开」时归零。
     *
     * 「今天被拦了几次」「今天累计注视多久」这类**按天统计**不在这里处理：
     * 它们本来就是按日历天现算的（见 `TimeNarrator.summarizeDay`）与由注视服务
     * 按 dayKey 归零，不需要第二处逻辑。
     */
    private fun rolloverDayIfNeeded() {
        val key = TimeNarrator.dayKey(clock())
        if (key == currentDayKey) return

        Log.i(TAG, "跨天：$currentDayKey → $key，清空待问队列")
        currentDayKey = key
        pending.clear()
        askedTodoIds.clear()
        fatigueAskedForSessionStart = 0L
    }

    private companion object {
        const val TAG = "ProactiveSupervisor"

        /** 拦截狂暴：窗口与阈值。 */
        const val RAMPAGE_WINDOW_MILLIS = 10 * 60_000L
        const val RAMPAGE_THRESHOLD = 3
        const val RAMPAGE_TTL_MILLIS = 20 * 60_000L

        /** 疲劳：门槛比注视服务的胶囊（20/30/45）高一档，形成递进而不同时响。 */
        const val FATIGUE_THRESHOLD_MILLIS = 60 * 60_000L
        const val FATIGUE_TTL_MILLIS = 15 * 60_000L

        /** 归来：离开多久之后再回来才值得问一句。 */
        const val RETURN_AFTER_AWAY_MILLIS = 15 * 60_000L
        const val RETURNED_TTL_MILLIS = 10 * 60_000L

        /** 待办临期：窗口、宽限与轮询周期。 */
        const val TODO_DUE_WINDOW_MILLIS = 30 * 60_000L
        const val TODO_OVERDUE_GRACE_MILLIS = 15 * 60_000L
        const val TICK_INTERVAL_MILLIS = 5 * 60_000L

        /** 两次盘问之间的最小间隔。比这更密就不是监督，是骚扰。 */
        const val COOLDOWN_MILLIS = 15 * 60_000L

        const val MAX_PENDING = 4
    }
}
