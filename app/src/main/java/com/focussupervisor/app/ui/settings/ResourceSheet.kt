package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * 本地模型是这个应用里唯一会占掉几十上百 MB 的东西，而在这一页出现之前，
 * 它们分居两处：
 *
 * ```
 *   向量模型  →  「向量模型」面板
 *   识图引擎  →  「注视监控」
 * ```
 *
 * 单看每一处都合理，合起来却有一个说不通的问题：**没有任何一页能回答
 * 「这些加起来占了我多少空间」**。用户在下第二个模型之前最需要知道的恰恰是这个。
 *
 * ===========================================================================
 * 这里曾经还有第三类：端侧对话模型
 * ===========================================================================
 * 0.5B~3.8B 的 `.task` 模型、下载进度、跑分卡片、切换按钮，整整一类已经移除。
 * 原因不是实现太难，而是**它不划算**：手机上跑得动的那些量级，回答质量明显不如
 * 云端；而跑得动的上限（3.8B）要占 3.8GB 和一大截运存，还得等它一个字一个字往外吐。
 * 一个自律监督应用最不能接受的就是「问它一句要等五秒，答得还不一定对」。
 *
 * ===========================================================================
 * 卡片为什么长一个样
 * ===========================================================================
 * 两类模型的元数据不同（维度 / 引擎类型），但用户要做的决定是同一个：
 * 下不下、删不删、用不用、占多少。所以这一页把它们压成同一种卡片，
 * 只在「档位」那一行显示各自的差异。两个长得不一样的列表，回答不了那个问题。
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
                viewModel = viewModel,
            )

            ResourceGroup(
                title = "识图引擎",
                subtitle = "把屏幕截图变成一句事实，让对话模型也能知道屏幕上发生了什么。",
                tone = PastelTone.SKY,
                items = uiState.visionItems,
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
    viewModel: ResourceViewModel,
) {
    SheetSection(title = title, subtitle = subtitle, tone = tone) {
        // 带 key 的 forEach：key 让 Compose 按「这一项是哪张卡」而不是按位置
        // 去复用节点。卡片会整块在「未下载 / 下载中 / 已就绪」之间换形状，
        // 没有 key 时位置一变就可能出现「点下去的按钮其实已经是另一张卡」。
        items.forEach { item ->
            key(item.id) {
                ResourceCard(item = item, viewModel = viewModel)
            }
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

            is LocalModelState.Ready -> ReadyActions(item = item, viewModel = viewModel)

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
    viewModel: ResourceViewModel,
) {
    when (item.kind) {
        // 向量模型的「启用」在它自己的功能面板里，这里只提供删除。
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
