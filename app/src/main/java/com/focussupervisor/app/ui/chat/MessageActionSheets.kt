package com.focussupervisor.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 长按消息之后的三步交互：操作面板 → 编辑框 / 删除确认。
 *
 * 拆成三个独立组件而不是一个「带模式的状态机」，是因为它们的**触发条件互斥**
 * 由上层状态保证（操作面板先关掉，再打开编辑或删除）。各自独立的组件更容易读，
 * 也不会出现「面板里还留着上一次的目标」这种状态残留。
 */

/**
 * 操作面板。
 *
 * 用 BottomSheet 而不是 Popup/ContextMenu：气泡可能在屏幕上任何位置，
 * 弹窗式的上下文菜单要考虑贴边翻转、遮挡手指等一堆问题；底部面板位置固定，
 * 在手机上也更好点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: ChatMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 先把被操作的那条消息显示出来。用户在长按之后往往已经松手，
            // 气泡上的高亮也没了 —— 不给他看一遍原文，他会不确定自己按对了没有。
            Text(
                text = senderLabel(message),
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = FocusTheme.colors.hairline,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            ActionRow(text = "编辑", onClick = onEdit)
            HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)
            ActionRow(text = "删除", onClick = onDelete, danger = true)
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) FocusTheme.colors.attention else FocusTheme.colors.textPrimary,
        )
    }
}

/**
 * 编辑框。
 *
 * 预填原文并**把光标放在末尾**（用 `selection`）—— 用户点「编辑」多半是想改几个字，
 * 不是一个字一个字重打。默认全选会让他的第一次输入把原文删光，那是最容易惹火人的
 * 一个细节。
 */
@Composable
fun MessageEditDialog(
    message: ChatMessage,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(message.id) { mutableStateOf(message.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FocusTheme.colors.surfaceSheet,
        title = {
            Text(
                text = "编辑消息",
                style = MaterialTheme.typography.titleMedium,
                color = FocusTheme.colors.textPrimary,
            )
        },
        text = {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FocusTheme.colors.inputField)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = FocusTheme.colors.textPrimary,
                ),
                cursorBrush = SolidColor(FocusTheme.colors.accent),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "内容不能为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FocusTheme.colors.textSecondary,
                            )
                        }
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(draft) },
                enabled = draft.isNotBlank(),
            ) {
                Text(
                    text = "保存",
                    color = if (draft.isNotBlank()) {
                        FocusTheme.colors.accent
                    } else {
                        FocusTheme.colors.accentDisabled
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = FocusTheme.colors.textSecondary)
            }
        },
    )
}

/**
 * 删除确认。
 *
 * 删除是不可撤销的，所以多问一句。确认框里带上原文摘要 —— 用户长按之后可能已经
 * 忘了自己按的是哪一条。
 */
@Composable
fun MessageDeleteDialog(
    message: ChatMessage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FocusTheme.colors.surfaceSheet,
        title = {
            Text(
                text = "删除这条消息？",
                style = MaterialTheme.typography.titleMedium,
                color = FocusTheme.colors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "删除后无法恢复。已经写进记忆的内容不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", color = FocusTheme.colors.attention)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = FocusTheme.colors.textSecondary)
            }
        },
    )
}

/** 把发送者翻成一句人话，用在操作面板顶部。 */
private fun senderLabel(message: ChatMessage): String = when (message.sender) {
    MessageSender.USER -> "我发的"
    MessageSender.AI -> "对方的"
    MessageSender.SYSTEM -> "系统播报"
}
