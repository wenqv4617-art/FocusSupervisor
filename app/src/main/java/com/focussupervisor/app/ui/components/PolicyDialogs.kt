package com.focussupervisor.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.core.time.TimeNarrator
import com.focussupervisor.app.domain.model.PermissionStatus
import com.focussupervisor.app.domain.model.PermissionTarget
import com.focussupervisor.app.domain.model.SystemWhitelist
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.WhitelistApp
import com.focussupervisor.app.ui.settings.SheetChip
import com.focussupervisor.app.ui.settings.SheetPrimaryButton
import com.focussupervisor.app.ui.settings.SheetTextField
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.util.formatPlannedTime

/**
 * 「+」面板引出的两个模态面板。
 *
 * 为什么用对话框而不是新开页面：这两个都是「看一眼就关」的检查型信息，跳走一整屏
 * 会把用户从会话里拽出来，回来还要重新找位置。模态弹出、点一下关掉，动线最短。
 *
 * 两个面板共用同一套排版约定：
 *  - 标题用 titleMedium；
 *  - 分组之间用一条发丝线隔开，而不是加卡片背景（这个应用没有卡片）；
 *  - 状态一律用文字表达，只有「需要处理」才用提醒色，其余用次级灰。
 */

/** 对话框内文字区的最大高度，超出后内部滚动，避免长列表把弹窗顶出屏幕。 */
private val DialogBodyMaxHeight = 420.dp

// ===========================================================================
// 权限检查
// ===========================================================================

/**
 * 权限检查面板。
 *
 * 只做两件事：如实列出每一项的当前状态、把用户送到对应的系统设置页。它**不申请**
 * 任何权限 —— 这些都是特殊权限，系统不允许应用弹窗申请。
 *
 * @param permissions 最近一次采集的快照。为空时说明还没查过，会显示一行提示。
 * @param onOpenSettings 点击「去授权」时回调，参数是需要跳转的目标。
 * @param onDismiss 关闭面板。
 */
@Composable
fun PermissionCheckDialog(
    permissions: List<PermissionStatus>,
    onOpenSettings: (PermissionTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val grantedCount = permissions.count { it.granted }
    val allGranted = permissions.isNotEmpty() && grantedCount == permissions.size

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FocusTheme.colors.surfaceSheet,
        title = {
            Text(
                text = "权限检查",
                style = MaterialTheme.typography.titleMedium,
                color = FocusTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DialogBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (permissions.isEmpty()) {
                        "正在读取权限状态…"
                    } else {
                        "$grantedCount / ${permissions.size} 项已就绪"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                permissions.forEachIndexed { index, status ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = FocusTheme.colors.hairline,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    PermissionRow(
                        status = status,
                        onOpenSettings = { onOpenSettings(status.target) },
                    )
                }

                if (!allGranted && permissions.isNotEmpty()) {
                    Text(
                        text = "无障碍服务是监督生效的前提；悬浮窗用于拉起全屏遮罩。" +
                            "两者缺一，拦截都不会真正生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭", color = FocusTheme.colors.accent)
            }
        },
    )
}

/**
 * 一行权限状态。
 *
 * 「去授权」只在**未开启**时出现：已经开好的项再给一个按钮，等于在鼓励用户点进去
 * 把权限关掉。
 */
@Composable
private fun PermissionRow(
    status: PermissionStatus,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.target.label,
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            Text(
                text = if (status.granted) "已开启" else "未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (status.granted) {
                    FocusTheme.colors.textSecondary
                } else {
                    FocusTheme.colors.attention
                },
            )
        }

        if (!status.granted) {
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = "去授权",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusTheme.colors.accent,
                )
            }
        }
    }
}

// ===========================================================================
// 待办与白名单
// ===========================================================================

/**
 * 待办与白名单面板。
 *
 * 这是阶段三「TodoList 盘问」和「AI 白名单审批」的展示地基：把当前的待办和豁免
 * 状态摊开给用户看，让他知道 AI 手里有哪些牌、以及刚才那些豁免还剩多久。
 *
 * @param todos 待办列表。
 * @param whitelist 当前白名单（含常驻项与临时豁免）。
 * @param nowMillis 计算「还剩多久」的基准时刻。由调用方传入而不是在内部取
 *        `System.currentTimeMillis()`，是为了让 Preview 和测试能固定时间。
 * @param onDismiss 关闭面板。
 */
@Composable
fun PolicyStatusDialog(
    todos: List<TodoItem>,
    whitelist: List<WhitelistApp>,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onAddTodo: (String, Long) -> Unit = { _, _ -> },
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteTodo: (String) -> Unit = {},
) {
    var draftTitle by remember { mutableStateOf("") }
    var dueOption by remember { mutableStateOf(DueOption.ONE_HOUR) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FocusTheme.colors.surfaceSheet,
        title = {
            Text(
                text = "待办与白名单",
                style = MaterialTheme.typography.titleMedium,
                color = FocusTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DialogBodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionTitle(text = "待办")

                // ---- 新增一条 ----
                //
                // 放在列表**上面**而不是下面：用户进这个面板多半就是想加一条，
                // 而把唯一的输入框放在一屏列表之后，他得先滑到底才能做事。
                SheetTextField(
                    label = "新的待办",
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    placeholder = "要做什么（例如：数学卷子第一套）",
                )

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DueOption.entries.forEach { option ->
                        SheetChip(
                            text = option.label,
                            selected = dueOption == option,
                            onClick = { dueOption = option },
                        )
                    }
                }

                SheetPrimaryButton(
                    text = "加入待办",
                    enabled = draftTitle.isNotBlank(),
                    onClick = {
                        onAddTodo(draftTitle.trim(), dueOption.resolve(nowMillis))
                        draftTitle = ""
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (todos.isEmpty()) {
                    EmptyHint(text = "暂无待办。")
                } else {
                    todos.forEach { todo ->
                        TodoRow(
                            todo = todo,
                            nowMillis = nowMillis,
                            onToggle = { done -> onToggleTodo(todo.id, done) },
                            onDelete = { onDeleteTodo(todo.id) },
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = FocusTheme.colors.hairline,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                SectionTitle(text = "白名单")
                if (whitelist.isEmpty()) {
                    EmptyHint(text = "白名单为空。")
                } else {
                    whitelist.forEach { entry ->
                        WhitelistRow(entry = entry, nowMillis = nowMillis)
                    }
                }

                Text(
                    text = "系统桌面、输入法与电话属于设备常驻白名单，不在此列表中逐条" +
                        "展示，也无法被拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭", color = FocusTheme.colors.accent)
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = FocusTheme.colors.textPrimary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FocusTheme.colors.textSecondary,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/**
 * 一行待办。
 *
 * 状态文字由 [TodoItem.isOverdueAt] 统一判定，不在这里另写一套逻辑 ——
 * 「已完成的不算逾期」这条规则只能有一个定义处。
 */
@Composable
private fun TodoRow(
    todo: TodoItem,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val overdue = todo.isOverdueAt(nowMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = todo.isDone,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(
                checkedColor = FocusTheme.colors.accent,
                uncheckedColor = FocusTheme.colors.hairline,
                checkmarkColor = Color.White,
            ),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            Text(
                text = buildString {
                    append("计划 ")
                    append(formatPlannedTime(todo.plannedAtMillis))
                    append(" · ")
                    append(
                        when {
                            todo.isDone -> "已完成"
                            // 逾期要给出**几天**，只说「已逾期」等于没说 ——
                            // 拖了一天和拖了一周，AI 该说的话完全不同。
                            overdue -> "已逾期 " + overdueLabel(todo, nowMillis)
                            else -> "待完成"
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue && !todo.isDone) {
                    FocusTheme.colors.attention
                } else {
                    FocusTheme.colors.textSecondary
                },
            )
        }

        TextButton(onClick = onDelete) {
            Text(
                text = "删除",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }
    }
}

/** 「已逾期」后面跟的那截。当天内逾期说「不到一天」，跨天说天数。 */
private fun overdueLabel(todo: TodoItem, nowMillis: Long): String {
    val days = TimeNarrator.dayOffset(todo.plannedAtMillis, nowMillis)
    return if (days <= 0L) "不到一天" else "$days 天"
}

/**
 * 「加入待办」时可选的截止时间。
 *
 * 只给三个纯算术区间（30 分钟 / 1 小时 / 3 小时），不做「今晚 20:00」这类
 * 需要日历计算与夏令时判断的选项：待办的主体是**标题**，截止时间只要大致合理
 * 就够用，而一个算错的「今晚」比「3 小时后」更让人困惑。要精确时间随时可以
 * 让 AI 用指令改（它接受 `yyyy-MM-dd HH:mm`）。
 */
private enum class DueOption(val label: String) {
    HALF_HOUR("30 分钟"),
    ONE_HOUR("1 小时"),
    THREE_HOURS("3 小时"),
    ;

    fun resolve(nowMillis: Long): Long = when (this) {
        HALF_HOUR -> nowMillis + 30 * 60_000L
        ONE_HOUR -> nowMillis + 60 * 60_000L
        THREE_HOURS -> nowMillis + 180 * 60_000L
    }
}

/**
 * 一行白名单。
 *
 * 展示顺序刻意是「应用名 → 包名 → 豁免情况」：应用名让人一眼认出是谁，包名是判定
 * 的真正依据（出问题时得能核对），豁免情况是用户最关心的「还能用多久」。
 */
@Composable
private fun WhitelistRow(entry: WhitelistApp, nowMillis: Long) {
    val remaining = entry.remainingMinutesAt(nowMillis)

    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = entry.appName,
            style = MaterialTheme.typography.bodyMedium,
            color = FocusTheme.colors.textPrimary,
        )
        Text(
            text = entry.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )
        Text(
            text = buildString {
                if (entry.isPermanent) {
                    append(entry.reason)
                    append(" · 永久")
                } else {
                    append("剩 ")
                    append(remaining ?: 0L)
                    append(" 分钟 · ")
                    append(entry.reason)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (!entry.isPermanent && (remaining ?: 0L) <= 0L) {
                FocusTheme.colors.attention
            } else {
                FocusTheme.colors.textSecondary
            },
        )
    }
}

// ===========================================================================
// 预览
// ===========================================================================

@Preview(name = "权限检查", showBackground = true)
@Composable
private fun PermissionCheckDialogPreview() {
    FocusSupervisorTheme {
        PermissionCheckDialog(
            permissions = listOf(
                PermissionStatus(PermissionTarget.ACCESSIBILITY, granted = true, checkedAtMillis = 0L),
                PermissionStatus(PermissionTarget.OVERLAY, granted = false, checkedAtMillis = 0L),
                PermissionStatus(PermissionTarget.USAGE_ACCESS, granted = false, checkedAtMillis = 0L),
                PermissionStatus(PermissionTarget.NOTIFICATIONS, granted = true, checkedAtMillis = 0L),
                PermissionStatus(PermissionTarget.BATTERY_OPTIMIZATION, granted = false, checkedAtMillis = 0L),
            ),
            onOpenSettings = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "待办与白名单", showBackground = true)
@Composable
private fun PolicyStatusDialogPreview() {
    val now = 1_758_600_000_000L
    FocusSupervisorTheme {
        PolicyStatusDialog(
            todos = listOf(
                TodoItem("t1", "把第二节的代码写完", now + 45 * 60_000L, isDone = false),
                TodoItem("t2", "出门走 20 分钟", now - 10 * 60_000L, isDone = false),
                TodoItem("t3", "回复导师邮件", now - 60 * 60_000L, isDone = true),
            ),
            whitelist = listOf(
                WhitelistApp("com.focussupervisor.app", "FocusSupervisor", null, SystemWhitelist.REASON_BUILT_IN),
                WhitelistApp("com.tencent.mm", "微信", now + 8 * 60_000L, "写完这一节就给你 10 分钟"),
            ),
            nowMillis = now,
            onDismiss = {},
        )
    }
}
