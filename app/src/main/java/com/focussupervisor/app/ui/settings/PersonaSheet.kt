package com.focussupervisor.app.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import android.net.Uri
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
 * 从底部「+」面板的「人设管理」自底向上滑出。两段结构：
 * ```
 *   AI 人设    头像 / 姓名 / 性别 / 详细设定
 *   用户人设   头像 / 姓名
 * ```
 *
 * 为什么用户人设没有「详细设定」：那段文字最终是要注入提示词的。给用户一个
 * 「设定你自己」的输入框，实质上是在让他替模型写提示词 —— 那既不是他想要的，
 * 也会让「AI 人设」和「用户设定」的职责边界变得含混。用户在对话里是什么样，
 * 由他的名字和头像表达就够了。
 *
 * 头像选择用系统的照片选择器（`PickVisualMedia`）：它**不需要任何存储权限**，
 * Android 13+ 是系统级选择器，更低版本由 Activity 库回退到兼容实现。
 * 自己写一个「读取相册」的权限流程既多写代码，又要向用户索要一个本来不必要的权限。
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
        containerColor = FocusTheme.colors.chromeBackground,
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
) {
    // 两个选择器分开注册：launcher 的回调是固定的，用一个共享的 launcher 就得再维护
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetTitle(text = "人设管理")

        // ---------------- AI 人设 ----------------
        SheetSectionLabel(text = "AI 人设")
        AvatarPickerRow(
            name = uiState.draftAi.name,
            avatarPath = uiState.draftAi.avatarPath,
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

        GenderRow(
            selected = uiState.draftAi.gender,
            onSelect = onAiGenderChange,
        )

        SheetTextField(
            label = "详细设定",
            value = uiState.draftAi.description,
            onValueChange = onAiDescriptionChange,
            placeholder = "性格、说话方式、它在意什么、它不允许你做什么……\n这段文字会原样注入每轮对话。",
            singleLine = false,
            minHeight = 120,
        )

        HorizontalDivider(
            thickness = 0.5.dp,
            color = FocusTheme.colors.hairline,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // ---------------- 用户人设 ----------------
        SheetSectionLabel(text = "用户人设")
        AvatarPickerRow(
            name = uiState.draftUser.name,
            avatarPath = uiState.draftUser.avatarPath,
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

        SheetMessageLine(message = uiState.message, isError = uiState.message == "保存失败，请稍后重试")

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetPrimaryButton(
                text = if (uiState.isSaving) "保存中…" else "保存",
                enabled = !uiState.isSaving,
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRevert, enabled = !uiState.isSaving) {
                Text(
                    text = "放弃改动",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * 头像 + 「更换头像」。
 *
 * 头像本身可点，右边的文字按钮也可点 —— 两个入口做同一件事。
 * 这是刻意的手感冗余：用户想换头像时，第一反应可能是去点那张图。
 */
@Composable
private fun AvatarPickerRow(
    name: String,
    avatarPath: String?,
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
            size = 60.dp,
            // 头像是小圆角方块，Material 的水波纹会溢出圆角，所以显式关掉它。
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPick,
            ),
        )
        TextButton(onClick = onPick) {
            Text(
                text = if (avatarPath.isNullOrBlank()) "选择头像" else "更换头像",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.accent,
            )
        }
    }
}

/** 性别选择：一排胶囊。 */
@Composable
private fun GenderRow(
    selected: PersonaGender,
    onSelect: (PersonaGender) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetSectionLabel(text = "性别")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonaGender.entries.forEach { gender ->
                SheetChip(
                    text = gender.label,
                    selected = gender == selected,
                    onClick = { onSelect(gender) },
                )
            }
        }
    }
}
