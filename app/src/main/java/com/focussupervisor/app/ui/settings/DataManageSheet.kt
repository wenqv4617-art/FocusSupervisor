package com.focussupervisor.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.core.backup.RestoreCandidate
import com.focussupervisor.app.data.repository.DataStats
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 数据管理面板：备份的导出与恢复。
 *
 * ===========================================================================
 * 页面结构
 * ===========================================================================
 * ```
 *   数据概览   ← 先让人知道「我有什么」，后面两个动作才有参照
 *   导出备份   ← 最常用的动作
 *   从备份恢复 ← 危险动作，排最后，且有一步确认
 * ```
 *
 * 危险动作放最后并单独用藕粉色的分组标出来，不是为了好看：
 * 这一页里「导出」和「恢复」是方向相反的两件事，一个把数据拿出来、
 * 一个把数据盖回去。用户滚动着随手一点就点错，代价是全部数据没了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManageSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DataManageViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    // 选目录而不是选文件：一份超过 8MB 的备份会被切成多个 .part 文件，
    // 让用户一个个多选既麻烦又容易漏。让他选文件夹，剩下的由我们按分片规则拼。
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.onBackupFolderPicked(uri)
    }

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
                SheetTitle(text = "数据管理", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }

            StatsSection(stats = uiState.stats)

            ExportSection(
                uiState = uiState,
                onExport = viewModel::export,
            )

            RestoreSection(
                uiState = uiState,
                onPickFolder = { folderPicker.launch(null) },
                onConfirm = viewModel::confirmRestore,
                onCancel = viewModel::dismissCandidate,
            )

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

            SheetNote(
                text = "本地向量模型（约 22MB）不进备份 —— 它是可以从「向量模型」里" +
                    "重新下回来的东西，打包进去只会让每一份备份都白白大 22MB。",
                tone = PastelTone.SKY,
            )
        }
    }
}

/** 数据概览。 */
@Composable
private fun StatsSection(stats: DataStats) {
    SheetSection(
        title = "数据概览",
        subtitle = "这些就是会被写进备份的内容。",
    ) {
        SheetStatRow(label = "对话", value = "${stats.messageCount} 条")
        SheetStatRow(label = "记忆", value = "${stats.memoryCount} 条")
        SheetStatRow(
            label = "向量索引",
            value = "${stats.embeddedCount} / ${stats.indexedCount}",
        )
        if (stats.indexedCount > 0) {
            SheetProgressBar(
                fraction = stats.embeddingProgress,
                tone = PastelTone.MINT,
            )
        }
        SheetStatRow(label = "时间线事件", value = "${stats.timelineCount} 条")
        SheetStatRow(label = "白名单应用", value = "${stats.whitelistCount} 个")
        SheetStatRow(label = "待办", value = "${stats.todoCount} 条")
        SheetStatRow(label = "端点预设", value = "${stats.presetCount} 个")
        SheetStatRow(
            label = "聊天背景图",
            value = formatBytes(stats.backgroundImageBytes),
        )
    }
}

/** 导出。 */
@Composable
private fun ExportSection(
    uiState: DataManageUiState,
    onExport: () -> Unit,
) {
    SheetSection(
        title = "导出备份",
        subtitle = "数据超过 8MB 会自动切成多个分片文件，放在同一个文件夹里。",
        tone = PastelTone.MINT,
    ) {
        SheetPrimaryButton(
            text = if (uiState.isExporting) "导出中…" else "导出到下载目录",
            enabled = !uiState.isBusy,
            onClick = onExport,
        )

        val export = uiState.export
        if (export != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetStatRow(label = "位置", value = export.location)
                SheetStatRow(label = "总大小", value = formatBytes(export.totalBytes))
                SheetStatRow(
                    label = "分片",
                    value = if (export.isSplit) "${export.fileNames.size - 1} 片" else "单文件",
                )
                export.fileNames.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }

            if (export.isSplit) {
                SheetNote(
                    text = "恢复的时候要选存放这些分片的那个文件夹。" +
                        "它们必须按名字顺序拼起来才是一份完整的备份，单独一片用不了。",
                    tone = PastelTone.LILAC,
                )
            }
        }
    }
}

/** 恢复。 */
@Composable
private fun RestoreSection(
    uiState: DataManageUiState,
    onPickFolder: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SheetSection(
        title = "从备份恢复",
        subtitle = "选存放备份的文件夹，我们会先读出内容让你确认，再覆盖当前数据。",
        tone = PastelTone.BLUSH,
    ) {
        SheetSecondaryButton(
            text = if (uiState.isReading) "正在读取…" else "选择备份文件夹",
            enabled = !uiState.isBusy,
            onClick = onPickFolder,
        )

        val candidate = uiState.candidate
        if (candidate != null) {
            RestoreCandidateCard(candidate = candidate)
        }

        if (candidate != null) {
            SheetNote(
                text = "确认之后，当前的对话、记忆、待办、白名单、人设与外观会全部被" +
                    "这份备份替换。这一步不可撤销。",
                tone = PastelTone.BLUSH,
                title = "请再确认一次",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetSecondaryButton(
                    text = "取消",
                    enabled = !uiState.isRestoring,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                SheetPrimaryButton(
                    text = if (uiState.isRestoring) "恢复中…" else "确认恢复",
                    enabled = !uiState.isRestoring,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 读出来的那份备份长什么样。 */
@Composable
private fun RestoreCandidateCard(candidate: RestoreCandidate) {
    val manifest = candidate.manifest

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (manifest != null) {
            SheetStatRow(
                label = "导出时间",
                value = formatTimestamp(manifest.createdAtMillis),
                emphasized = true,
            )
            SheetStatRow(label = "导出时版本", value = manifest.appVersionName)
            SheetStatRow(
                label = "分片数",
                value = if (manifest.partCount > 1) "${manifest.partCount} 片" else "单文件",
            )
            SheetStatRow(
                label = "含背景图",
                value = if (manifest.hasBackgroundImage) "有" else "无",
            )
        } else {
            SheetNote(
                text = "这份备份里没有清单文件，所以看不到它是什么时候导出的。" +
                    "数据本身仍然可以恢复。",
                tone = PastelTone.LILAC,
            )
        }

        SheetStatRow(
            label = "对话",
            value = "${candidate.preferences.messages.size} 条",
            emphasized = true,
        )
        SheetStatRow(
            label = "记忆",
            value = "${candidate.preferences.memories.size} 条",
            emphasized = true,
        )
        SheetStatRow(
            label = "时间线",
            value = "${candidate.preferences.timeline.size} 条",
        )
        SheetStatRow(
            label = "待办",
            value = "${candidate.preferences.todos.size} 条",
        )
        SheetStatRow(
            label = "文件大小",
            value = formatBytes(candidate.totalBytes.toLong()),
        )
    }
}

/** 字节数转人话。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 时间戳转人话。 */
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "未知"
    return BACKUP_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/**
 * 备份时间的显示格式。
 *
 * 与 `BackupManager` 里生成文件名用的格式分开定义：那一个是**文件名的契约**
 * （改了会让新旧备份的命名不一致），这一个只是给人看的。两者故意不复用同一个常量。
 */
private val BACKUP_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
