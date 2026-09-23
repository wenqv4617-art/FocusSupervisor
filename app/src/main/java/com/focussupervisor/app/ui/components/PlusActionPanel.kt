package com.focussupervisor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.domain.model.ActionItem
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 底部「+」展开面板。
 *
 * 产品约束：主界面不允许出现任何配置类按钮。权限检查、强制锁定、注视监控、AI 配置
 * 这些入口全部收纳在这里，因此这个面板是「应用的全部控制面」。
 *
 * 视觉上完全照搬微信的加号面板语言：
 * - 顶部一条发丝线与输入栏分隔；
 * - 每行 4 格，格与格等宽；
 * - 每格 = 一枚圆角白色方块（内嵌矢量图标）+ 下方一行功能名。
 *
 * 之所以不用 LazyVerticalGrid：面板里最多 8 个格子，且需要随展开动画一起做高度
 * 动画。惰性布局在动画中会因为「可见项才测量」而产生高度跳变，用普通的
 * chunked + Column/Row 反而更稳、更省。
 */

/** 每行格子数。微信是 4 列，沿用。 */
private const val COLUMNS_PER_ROW = 4

/** 功能图标外框尺寸。 */
private val ActionTileSize = 54.dp

/** 功能图标本身尺寸。 */
private val ActionIconSize = 26.dp

/** 面板内边距。 */
private val PanelHorizontalPadding = 16.dp
private val PanelVerticalPadding = 18.dp

/**
 * 「+」面板。
 *
 * @param actions 要展示的动作项。按 [COLUMNS_PER_ROW] 自动换行，超过 8 个时
 *                调用方应自行分页（当前产品方案固定为 4 个，不会触发）。
 * @param onActionClick 点击回调。分发键用 [ActionItem.id]，不要用 label。
 */
@Composable
fun PlusActionPanel(
    actions: List<ActionItem>,
    onActionClick: (ActionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = FocusTheme.colors.hairline,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(FocusTheme.colors.chromeBackground)
                .padding(
                    horizontal = PanelHorizontalPadding,
                    vertical = PanelVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            actions.chunked(COLUMNS_PER_ROW).forEach { rowItems ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { action ->
                        PlusActionButton(
                            action = action,
                            onClick = { onActionClick(action) },
                            // weight 而不是固定宽度：格子数变化时自动等分，
                            // 顺带解决窄屏上 4 列放不下的问题。
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行不足 4 个时补透明占位，保证已有的格子仍然是
                    // 「每行 4 等分」的宽度，不会被拉宽变形。
                    repeat(COLUMNS_PER_ROW - rowItems.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 面板里的单个功能格。
 *
 * 用 [clickable] 而不是 Material 的 IconButton/Card：
 * 这是自绘的格子，套 Material 组件反而要反复覆盖它的最小尺寸、水波纹形状和
 * 内部内边距，得不偿失。indication 保留默认水波纹，但裁剪成圆角方块，
 * 让反馈落在方框内部而不是溢出一圈。
 */
@Composable
private fun PlusActionButton(
    action: ActionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(ActionTileSize)
                .clip(tileShape)
                .background(FocusTheme.colors.inputField),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = action.icon,
                // 纯装饰性图标：文字已经说明了功能，读屏再念一遍图标名只会更吵。
                contentDescription = null,
                tint = FocusTheme.colors.iconTint,
                modifier = Modifier.size(ActionIconSize),
            )
        }

        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = FocusTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * 默认动作项。
 *
 * 放在 UI 层而不是 domain 层：图标与文案是展示决策，domain 只定义 [ActionItem]
 * 这个形状。等这些入口真正接上业务时，这里会换成从 ViewModel 注入。
 *
 * 图标只用 material-icons-core（本模块刻意没引 icons-extended），
 * 因此可选形状有限，选择标准是「语义最先命中」：
 * CheckCircle=权限检查、Lock=强制锁定、Face=注视监控、Settings=AI 配置。
 */
fun defaultActionItems(): List<ActionItem> = listOf(
    ActionItem(
        id = "permission_check",
        label = "权限检查",
        icon = Icons.Outlined.CheckCircle,
    ),
    ActionItem(
        id = "force_lock_test",
        label = "强制锁定测试",
        icon = Icons.Outlined.Lock,
    ),
    ActionItem(
        id = "gaze_monitor",
        label = "注视监控开关",
        icon = Icons.Outlined.Face,
    ),
    ActionItem(
        id = "ai_config",
        label = "AI 配置",
        icon = Icons.Outlined.Settings,
    ),
)

@Preview(name = "加号面板", showBackground = true, backgroundColor = 0xFFF7F7F7)
@Composable
private fun PlusActionPanelPreview() {
    FocusSupervisorTheme {
        PlusActionPanel(
            actions = defaultActionItems(),
            onActionClick = {},
        )
    }
}
