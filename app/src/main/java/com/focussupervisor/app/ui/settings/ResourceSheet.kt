package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.data.repository.BenchmarkResult
import com.focussupervisor.app.data.repository.LocalModelState
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone
import java.util.Locale

/**
 * 资源管理：这个应用里全部「要下载的东西」的唯一的家。
 *
 * ===========================================================================
 * 为什么需要一个专门的页面
 * ===========================================================================
 * 端侧模型是这个应用里唯一会吃掉几个 GB 的功能，而在这一页出现之前，
 * 三类模型分居三处：
 *
 * ```
 *   向量模型  →  「向量模型」面板
 *   对话模型  →  「AI 配置」
 *   识图引擎  →  「注视监控」
 * ```
 *
 * 单看每一处都合理，合起来却有一个说不通的问题：**没有任何一页能回答
 * 「这些加起来占了我多少空间」**。用户在下第三个模型之前最需要知道的恰恰是这个。
 *
 * ===========================================================================
 * 卡片为什么长一个样
 * ===========================================================================
 * 三类模型的元数据完全不同（维度 / 参数量 / 引擎类型），但用户要做的决定是同一个：
 * 下不下、删不删、用不用、占多少。所以这一页把它们压成同一种卡片，
 * 只在「档位」那一行显示各自的差异。三个长得不一样的列表，回答不了那个问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResourceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SheetHorizontalPadding)
                .padding(bottom = SheetBottomPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetTitle(text = "资源管理", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }

            SheetNote(
                text = "这里管的是「要下载到手机上的东西」。下载完的模型都可以随时删掉，" +
                    "不删也不会影响别的功能 —— 它们只是各管一摊。",
                tone = PastelTone.SKY,
            )

            SpaceOverview(uiState = uiState)

            ResourceGroup(
                title = "向量模型",
                subtitle = "把文本变成向量，供记忆检索用。体积小、常驻内存也小。",
                tone = PastelTone.MINT,
                items = uiState.embeddingItems,
                uiState = uiState,
                viewModel = viewModel,
            )

            ResourceGroup(
                title = "对话模型",
                subtitle = "端侧跑的自律管家本体。下哪一个取决于这台手机的运存，" +
                    "以及你要「快」还是要「聪明」。",
                tone = PastelTone.LILAC,
                items = uiState.llmItems,
                uiState = uiState,
                viewModel = viewModel,
            )

            ResourceGroup(
                title = "识图引擎",
                subtitle = "把屏幕截图变成一句事实，让纯文本模型也能知道屏幕上发生了什么。",
                tone = PastelTone.SKY,
                items = uiState.visionItems,
                uiState = uiState,
                viewModel = viewModel,
            )

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)
        }
    }
}

/** 空间概览。 */
@Composable
private fun SpaceOverview(uiState: ResourceUiState) {
    SheetSection(
        title = "空间占用",
        subtitle = "已下载的部分是实际占用的空间。",
        tone = PastelTone.MINT,
    ) {
        SheetStatRow(
            label = "已占用",
            value = formatBytes(uiState.usedBytes),
            emphasized = true,
        )
        SheetStatRow(
            label = "全部下载需要",
            value = formatBytes(uiState.totalBytes),
        )
        // 进度条在这里表达的仍然是「已占 / 全部」这个比例，
        // 而不是任何一次下载的进度 —— 下载各自的进度在各自的卡片上。
        SheetProgressBar(
            fraction = if (uiState.totalBytes > 0L) {
                uiState.usedBytes.toFloat() / uiState.totalBytes.toFloat()
            } else {
                0f
            },
            tone = PastelTone.MINT,
        )
        SheetNote(
            text = "识图引擎里的 ML Kit 是内置组件，不占这一栏的体积；" +
                "本地向量模型与对话模型都是可选的，不下载也能用（走在线端点）。",
            tone = PastelTone.SKY,
        )
    }
}

/** 一组资源卡片。 */
@Composable
private fun ResourceGroup(
    title: String,
    subtitle: String,
    tone: PastelTone,
    items: List<ResourceItem>,
    uiState: ResourceUiState,
    viewModel: ResourceViewModel,
) {
    SheetSection(title = title, subtitle = subtitle, tone = tone) {
        items.forEach { item ->
            ResourceCard(
                item = item,
                isBenchmarking = uiState.benchmarkTargetId == item.id,
                viewModel = viewModel,
            )
        }
    }
}

/**
 * 一张资源卡片。
 *
 * 按 [LocalModelState] 分支渲染，五种状态各有各的界面 —— 这也是为什么状态用一个
 * 密封接口而不是「布尔 + 进度 + 错误串」：后者允许出现「正在下载 60% 同时错误
 * 信息还在」这种自相矛盾的组合，界面就得写一堆优先级判断来决定显示什么。
 */
@Composable
private fun ResourceCard(
    item: ResourceItem,
    isBenchmarking: Boolean,
    viewModel: ResourceViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 标题行 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusTheme.colors.textPrimary,
                )
                Text(
                    text = item.tier,
                    style = MaterialTheme.typography.labelSmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
            StatusPill(item = item)
        }

        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )

        // ---- 不可用：直接说清楚原因，并且不给任何可点的按钮 ----
        //
        // 一个点下去没反应、也不解释原因的按钮，比一个写着「暂不可用」的卡片
        // 糟糕得多。这里连禁用按钮都不画 —— 画一个灰按钮会让人以为
        // 「条件满足了就能点」，而这一项缺的不是条件，是运行时。
        if (item.unavailableReason != null) {
            SheetNote(text = item.unavailableReason, tone = PastelTone.BLUSH)
            return@Column
        }

        // ---- 状态相关的按钮与进度 ----
        when (val state = item.state) {
            LocalModelState.NotInstalled -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SheetStatRow(
                        label = "体积",
                        value = item.sizeLabel,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.canDownload) {
                        SheetPrimaryButton(
                            text = "下载",
                            enabled = true,
                            onClick = { viewModel.download(item) },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (item.kind == ResourceKind.VISION) {
                        SheetPill(text = "无需下载", tone = PastelTone.MINT)
                    }
                }
            }

            is LocalModelState.Partial -> {
                val fraction = if (item.sizeBytes > 0L) {
                    state.downloadedBytes.toFloat() / item.sizeBytes.toFloat()
                } else {
                    null
                }
                SheetProgressBar(fraction = fraction, tone = PastelTone.LILAC)
                SheetStatRow(
                    label = "已下载",
                    value = "${formatBytes(state.downloadedBytes)} / ${item.sizeLabel}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetPrimaryButton(
                        text = "继续下载",
                        enabled = true,
                        onClick = { viewModel.download(item) },
                        modifier = Modifier.weight(1f),
                    )
                    SheetSecondaryButton(
                        text = "删除",
                        enabled = true,
                        onClick = { viewModel.delete(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is LocalModelState.Downloading -> {
                SheetProgressBar(
                    fraction = if (state.progress.isTotalKnown) state.progress.fraction else null,
                    tone = PastelTone.MINT,
                )
                SheetStatRow(
                    label = "进度",
                    value = if (state.progress.isTotalKnown) {
                        "${formatBytes(state.progress.downloadedBytes)} / ${item.sizeLabel}" +
                            " · ${(state.progress.fraction * 100).toInt()}%"
                    } else {
                        formatBytes(state.progress.downloadedBytes)
                    },
                    emphasized = true,
                )
                SheetStatRow(
                    label = "速度",
                    value = if (state.progress.bytesPerSecond > 0) {
                        "${formatBytes(state.progress.bytesPerSecond)}/s"
                    } else {
                        "测量中…"
                    },
                )
                SheetStatRow(
                    label = "剩余",
                    value = formatRemaining(state.progress.remainingMillis),
                )
                SheetSecondaryButton(
                    text = "取消",
                    enabled = true,
                    onClick = { viewModel.cancel(item) },
                )
            }

            LocalModelState.Verifying -> {
                SheetProgressBar(fraction = null, tone = PastelTone.SKY)
                Text(
                    text = "正在校验与加载…",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }

            is LocalModelState.Ready -> ReadyActions(
                item = item,
                isBenchmarking = isBenchmarking,
                viewModel = viewModel,
            )

            is LocalModelState.Failed -> {
                SheetNote(title = "出错了", text = state.message, tone = PastelTone.BLUSH)
                SheetPrimaryButton(
                    text = "重试",
                    enabled = true,
                    onClick = { viewModel.download(item) },
                )
            }
        }
    }
}

/** 已就绪时的按钮组合。 */
@Composable
private fun ReadyActions(
    item: ResourceItem,
    isBenchmarking: Boolean,
    viewModel: ResourceViewModel,
) {
    when (item.kind) {
        // 向量模型与识图引擎的「启用」在各自的功能面板里，这里只提供删除。
        ResourceKind.EMBEDDING -> {
            SheetStatRow(label = "体积", value = item.sizeLabel)
            SheetNote(
                text = "已经在用本机算向量的话，这里的删除会让记忆退回关键词匹配。" +
                    "启用开关在「向量模型」面板里。",
                tone = PastelTone.MINT,
            )
            SheetSecondaryButton(
                text = "删除模型（释放 ${item.sizeLabel}）",
                enabled = true,
                onClick = { viewModel.delete(item) },
            )
        }

        ResourceKind.VISION -> {
            SheetNote(
                text = "这一项是内置组件，随应用一起安装，不需要下载也不能单独删除。" +
                    "启用开关在「注视监控」面板里。",
                tone = PastelTone.MINT,
            )
        }

        ResourceKind.LLM -> {
            SheetStatRow(label = "体积", value = item.sizeLabel)
            SheetStatRow(
                label = "建议运存",
                value = "${item.recommendedRamGb} GB 以上",
            )

            item.benchmark?.let { result ->
                BenchmarkCard(result = result)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (item.isActive) {
                    SheetPill(text = "当前使用中", tone = PastelTone.MINT)
                } else {
                    SheetPrimaryButton(
                        text = "用它",
                        enabled = true,
                        onClick = { viewModel.activateLlm(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                SheetSecondaryButton(
                    text = if (isBenchmarking) "跑分中…" else "测速跑分",
                    enabled = !isBenchmarking,
                    onClick = { viewModel.benchmark(item) },
                    modifier = Modifier.weight(1f),
                )
            }

            SheetSecondaryButton(
                text = "删除模型（释放 ${item.sizeLabel}）",
                enabled = !isBenchmarking,
                onClick = { viewModel.delete(item) },
            )
        }
    }
}

/**
 * 跑分结果。
 *
 * 三个数并排给出来，而不是合成一个「得分」：0.5B 与 3.8B 的对比可能是
 * 「300ms / 18 tok/s / 900MB」对「900ms / 9 tok/s / 3.6GB」——
 * 它们没有优劣之分，是两种取舍。给一个「综合得分 82」等于把选择权从用户手里拿走。
 */
@Composable
private fun BenchmarkCard(result: BenchmarkResult) {
    // 刻意**不**用 SheetSection：那会在这张资源卡片内部再套一层带描边和投影的卡片，
    // 两层阴影叠在一起会显脏。跑分结果是这张卡片的一部分，用一块淡色底就够了。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = FocusTheme.colors.pastelLilac,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "跑分结果",
            style = MaterialTheme.typography.labelMedium,
            color = FocusTheme.colors.pastelLilacInk,
        )
        SheetStatRow(label = "首字延迟 (TTFT)", value = result.ttftLabel, emphasized = true)
        SheetStatRow(
            label = "生成吞吐",
            value = String.format(Locale.US, "%.1f tok/s", result.tokensPerSecond),
            emphasized = true,
        )
        SheetStatRow(label = "本次生成", value = "${result.outputTokens} token")
        SheetStatRow(label = "进程内存 (PSS)", value = "${result.memoryPssMb} MB", emphasized = true)
        SheetStatRow(label = "其中 Java 堆", value = "${result.memoryHeapMb} MB")
        Text(
            text = "内存看的是 PSS（整个进程的物理占用，含 native）。只看 Java 堆会得出" +
                "「才用了十几 MB」这种误导结论 —— 模型权重根本不在 Java 堆上。",
            style = MaterialTheme.typography.labelSmall,
            color = FocusTheme.colors.pastelLilacInk.copy(alpha = 0.8f),
        )
    }
}

/** 卡片右上角的状态标记。 */
@Composable
private fun StatusPill(item: ResourceItem) {
    val tone = when {
        item.unavailableReason != null -> PastelTone.BLUSH
        item.isActive -> PastelTone.MINT
        item.state is LocalModelState.Ready -> PastelTone.LILAC
        item.state is LocalModelState.Downloading -> PastelTone.SKY
        item.state is LocalModelState.Failed -> PastelTone.BLUSH
        item.state is LocalModelState.Partial -> PastelTone.LILAC
        else -> PastelTone.SKY
    }
    val label = when {
        item.unavailableReason != null -> "暂不可用"
        item.isActive -> "使用中"
        item.state is LocalModelState.Ready -> "已就绪"
        item.state is LocalModelState.Downloading -> "下载中"
        item.state is LocalModelState.Verifying -> "校验中"
        item.state is LocalModelState.Failed -> "失败"
        item.state is LocalModelState.Partial -> "未下完"
        item.kind == ResourceKind.VISION -> "内置"
        else -> "未下载"
    }
    SheetPill(text = label, tone = tone)
}

/** 字节数转人话。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 剩余时间转人话。 */
private fun formatRemaining(millis: Long): String = when {
    millis < 0L -> "计算中…"
    millis < 1000L -> "不到 1 秒"
    millis < 60_000L -> "${millis / 1000} 秒"
    millis < 3_600_000L -> "${millis / 60_000} 分 ${(millis % 60_000) / 1000} 秒"
    else -> "${millis / 3_600_000} 小时 ${(millis % 3_600_000) / 60_000} 分"
}
