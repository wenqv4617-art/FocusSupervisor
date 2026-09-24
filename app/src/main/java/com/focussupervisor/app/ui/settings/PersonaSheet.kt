package com.focussupervisor.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.PersonaGender
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.ui.components.Avatar
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 人设管理面板。
 *
 * 结构是两块**分组卡片**，而不是一列平铺的输入框：
 * ```
 *   ┌ AI 人设 ─────────────────┐
 *   │  头像  姓名  性别  详细设定 │
 *   └──────────────────────────┘
 *   ┌ 用户人设 ────────────────┐
 *   │  头像  姓名               │
 *   └──────────────────────────┘
 * ```
 * 上一版靠间距分组，一屏十几个控件没有边界，用户看不出哪几个是一伙的。
 * 现在每块是白底 + 发丝边框，边界一眼可辨，且没有引入第二种强调色。
 *
 * 为什么用户人设没有「详细设定」：那段文字最终要注入提示词。给用户一个
 * 「设定你自己」的输入框，实质是让他替模型写提示词 —— 既不是他想要的，
 * 也会让「AI 人设」和「用户设定」的职责边界变含混。
 *
 * 头像用系统的照片选择器（`PickVisualMedia`）：**不需要任何存储权限**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonaViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        PersonaContent(
            uiState = uiState,
            onAiNameChange = viewModel::onAiNameChange,
            onAiGenderChange = viewModel::onAiGenderChange,
            onAiDescriptionChange = viewModel::onAiDescriptionChange,
            onAiAvatarPicked = viewModel::onAiAvatarPicked,
            onUserNameChange = viewModel::onUserNameChange,
            onUserAvatarPicked = viewModel::onUserAvatarPicked,
            onSave = viewModel::save,
            onRevert = viewModel::revert,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun PersonaContent(
    uiState: PersonaUiState,
    onAiNameChange: (String) -> Unit,
    onAiGenderChange: (PersonaGender) -> Unit,
    onAiDescriptionChange: (String) -> Unit,
    onAiAvatarPicked: (Uri) -> Unit,
    onUserNameChange: (String) -> Unit,
    onUserAvatarPicked: (Uri) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onClose: () -> Unit,
) {
    // 两个选择器分开注册：launcher 的回调是固定的，共用一个就得再维护
    // 一个「这次选的是谁」的状态变量，那是纯粹的额外复杂度。
    val aiAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAiAvatarPicked) }

    val userAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onUserAvatarPicked) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetHorizontalPadding)
            .padding(bottom = SheetBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTitle(text = "人设管理", modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        // ---------------- AI 人设 ----------------
        SheetSection(
            title = "AI 人设",
            subtitle = "它的名字会出现在顶栏与每条回复上方；详细设定会原样注入每轮对话。",
        ) {
            AvatarPicker(
                name = uiState.draftAi.name,
                avatarPath = uiState.draftAi.avatarPath,
                fallbackHint = "选择头像",
                onPick = {
                    aiAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )

            SheetTextField(
                label = "姓名",
                value = uiState.draftAi.name,
                onValueChange = onAiNameChange,
                placeholder = AiPersona.DEFAULT_NAME,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetSectionLabel(text = "性别")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonaGender.entries.forEach { gender ->
                        SheetChip(
                            text = gender.label,
                            selected = gender == uiState.draftAi.gender,
                            onClick = { onAiGenderChange(gender) },
                        )
                    }
                }
            }

            SheetTextField(
                label = "详细设定",
                value = uiState.draftAi.description,
                onValueChange = onAiDescriptionChange,
                placeholder = "性格、说话方式、它在意什么、它不允许你做什么……",
                singleLine = false,
                minHeight = 120,
            )
        }

        // ---------------- 用户人设 ----------------
        SheetSection(
            title = "用户人设",
            subtitle = "你在对话里呈现出来的样子。",
        ) {
            AvatarPicker(
                name = uiState.draftUser.name,
                avatarPath = uiState.draftUser.avatarPath,
                fallbackHint = "选择头像",
                onPick = {
                    userAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )

            SheetTextField(
                label = "姓名",
                value = uiState.draftUser.name,
                onValueChange = onUserNameChange,
                placeholder = UserPersona.DEFAULT_NAME,
            )
        }

        SheetMessageLine(
            message = uiState.message,
            isError = uiState.message?.contains("失败") == true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPrimaryButton(
                text = if (uiState.isSaving) "保存中…" else "保存",
                enabled = !uiState.isSaving,
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
            SheetSecondaryButton(
                text = "放弃改动",
                enabled = !uiState.isSaving,
                onClick = onRevert,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 头像 + 更换入口。
 *
 * 头像放大到 64dp 并单独占一行：它是这一块里唯一「看得见效果」的东西，
 * 缩在输入框旁边会让人以为它只是个装饰。
 */
@Composable
private fun AvatarPicker(
    name: String,
    avatarPath: String?,
    fallbackHint: String,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(
            path = avatarPath,
            name = name,
            size = 64.dp,
            // 头像是小圆角方块，Material 的水波纹会溢出圆角，所以显式关掉它。
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPick,
            ),
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (avatarPath.isNullOrBlank()) fallbackHint else "已设置",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            TextButton(onClick = onPick) {
                Text(
                    text = if (avatarPath.isNullOrBlank()) "从相册选择" else "更换",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.accent,
                )
            }
        }
    }
}
