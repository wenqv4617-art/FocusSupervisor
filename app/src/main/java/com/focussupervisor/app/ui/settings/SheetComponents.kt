package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 「+」面板引出的各个 BottomSheet 共用的排版零件。
 *
 * 抽出来的理由很实际：AI 配置、人设、记忆三个面板加起来有二十多个输入框和标签。
 * 每个面板各写一份 `BasicTextField` 的 decorationBox，就会有三份略有差异的实现
 * —— 而「略有差异」正是界面开始显得不整齐的起点。统一到这一份之后，
 * 改一次内边距，三个面板一起变。
 *
 * 全部是 `internal`：它们只在 settings 包内部使用，不应该被别处顺手拿去拼页面。
 */

/** 面板内容区的左右内边距。 */
internal val SheetHorizontalPadding = 20.dp

/** 面板内容区的底部内边距。 */
internal val SheetBottomPadding = 24.dp

/**
 * 面板主标题。
 */
@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = FocusTheme.colors.textPrimary,
        modifier = modifier,
    )
}

/**
 * 分组小标题。
 *
 * 刻意用次级灰而不是主文字色：它是**标签**不是内容，抢了输入框的注意力就本末倒置了。
 */
@Composable
internal fun SheetSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = FocusTheme.colors.textSecondary,
        modifier = modifier,
    )
}

/**
 * 一个带标签的输入框。
 *
 * 自绘而不是用 Material 的 `OutlinedTextField`：后者自带 label 浮动动画、外框、
 * 最小高度和一组内边距，为了得到这里这种「一行标签 + 一个白底圆角条」需要覆盖
 * 十几项参数。`BasicTextField` 没有被弃用，它是做自定义输入框的正确起点。
 *
 * @param trailing 右侧附加内容（显示/隐藏 Key、拉取模型……）
 * @param footer 输入框下方的灰色说明文字
 * @param minHeight 最小高度；多行输入（人设详述）需要把它调大
 */
@Composable
internal fun SheetTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 0,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    footer: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SheetSectionLabel(text = label)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = minHeight.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FocusTheme.colors.inputField)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = FocusTheme.colors.textPrimary,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(FocusTheme.colors.accent),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 8,
                visualTransformation = visualTransformation,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.TopStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FocusTheme.colors.textSecondary,
                                maxLines = if (singleLine) 1 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 4.dp)) { trailing() }
            }
        }

        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * 一枚可选中的胶囊。用于性别、预设这类「从少数几个里选一个」的场合。
 */
@Composable
internal fun SheetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) FocusTheme.colors.accent else FocusTheme.colors.inputField,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Color.White else FocusTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 提示行。
 *
 * 错误用低饱和红，普通提示用次级灰 —— 刻意不给成功配一个绿色，
 * 那样界面会变成红绿灯，而这个应用的全部基调是「安静」。
 */
@Composable
internal fun SheetMessageLine(message: String?, isError: Boolean) {
    val text = message ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) FocusTheme.colors.attention else FocusTheme.colors.textSecondary,
    )
}

/**
 * 面板底部的主操作按钮。
 *
 * 整个应用里只有两个地方允许出现强调色实心按钮（聊天页的发送键、以及这里），
 * 它们都是各自界面里唯一「下一步做什么」的答案。
 */
@Composable
internal fun SheetPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = FocusTheme.colors.accent,
            contentColor = Color.White,
            disabledContainerColor = FocusTheme.colors.accentDisabled,
            disabledContentColor = Color.White,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * 次级操作按钮：有边框、无填充。
 *
 * 和 [SheetPrimaryButton] 并排时用它，让「哪个是主操作」一眼可辨 ——
 * 两个实心按钮并排，用户每次都得读一遍文字才知道该点哪个。
 */
@Composable
internal fun SheetSecondaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, FocusTheme.colors.hairline),
        colors = ButtonDefaults.buttonColors(
            containerColor = FocusTheme.colors.inputField,
            contentColor = FocusTheme.colors.textPrimary,
            disabledContainerColor = FocusTheme.colors.inputField,
            disabledContentColor = FocusTheme.colors.textSecondary,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/**
 * 分组卡片。
 *
 * 这是面板「看不看得清」的关键。上一版把所有字段平铺在一列里，靠 16dp 间距区分
 * 分组 —— 结果是一屏十几个输入框，眼睛找不到边界，用户不知道哪几个是一伙的。
 *
 * 现在每个分组是一块白底圆角块 + 0.5dp 发丝边框，标题压在块外左上角。
 * 白底与 chromeBackground 的明度差足够划出边界，又不需要投影或第二强调色。
 */
@Composable
internal fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetSectionLabel(text = title)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(FocusTheme.colors.inputField)
                .border(
                    width = 0.5.dp,
                    color = FocusTheme.colors.hairline,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * 一枚层级徽章。用于记忆列表，让人一眼看出这条在哪一层。
 *
 * 三种层级只用**明度**区分，不用三种颜色：这个应用的调色板里只有绿和红两个有含义
 * 的颜色，拿它们去标「核心/长久/短期」会把「需要处理」这个信号稀释掉。
 */
@Composable
internal fun TierBadge(text: String, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (emphasized) {
                    FocusTheme.colors.accent.copy(alpha = 0.14f)
                } else {
                    FocusTheme.colors.chatBackground
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) FocusTheme.colors.accent else FocusTheme.colors.textSecondary,
        )
    }
}
