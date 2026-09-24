package com.focussupervisor.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone

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
 * 默认用次级灰而不是主文字色：它是**标签**不是内容，抢了输入框的注意力就本末倒置了。
 * 只有带语义色的分组（[SheetSection] 传了 tone）才覆盖成同色墨色。
 */
@Composable
internal fun SheetSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        color = color ?: FocusTheme.colors.textSecondary,
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
                    .background(FocusTheme.colors.surfaceCardMuted)
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
 *
 * 选中态用**淡彩薄荷底 + 深墨字**，而不是实心强调色。原因是这个应用的面板里
 * 会同时出现好几排胶囊，全部用实心绿会让每一排都抢同一份注意力；
 * 淡彩底同样能读出「这个被选中了」，但不会把旁边的说明文字压下去。
 */
@Composable
internal fun SheetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    val background by animateColorAsState(
        targetValue = if (selected) colors.pastelMint else colors.surfaceCardMuted,
        animationSpec = tween(durationMillis = 160),
        label = "chipBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.pastelMintInk else colors.textPrimary,
        animationSpec = tween(durationMillis = 160),
        label = "chipContent",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 提示行。
 *
 * 用一条淡彩条而不是一枚灰字：这里出现的往往正是「刚才那一下成没成」，
 * 而一枚灰色小字在长面板里很容易被划过去。错误走藕粉，普通提示走天青 ——
 * 全应用只有藕粉这一支带情绪，所以「出错」这件事在界面上一眼就能定位。
 */
@Composable
internal fun SheetMessageLine(message: String?, isError: Boolean) {
    val text = message ?: return
    SheetNote(
        text = text,
        tone = if (isError) PastelTone.BLUSH else PastelTone.SKY,
    )
}

/**
 * 面板底部的主操作按钮。
 *
 * 整个应用里只有两个地方允许出现强调色实心按钮（聊天页的发送键、以及这里），
 * 它们都是各自界面里唯一「下一步做什么」的答案。所以它的形状也跟别的控件不同：
 * 14dp 圆角（比卡片内的小控件更圆），按下时缩到 0.97 —— 触感上的「这一下按到了」
 * 比变色更直接。
 */
@Composable
internal fun SheetPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "primaryPressScale",
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(14.dp),
        interactionSource = interactionSource,
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "secondaryPressScale",
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, FocusTheme.colors.cardBorder),
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = FocusTheme.colors.surfaceCardMuted,
            contentColor = FocusTheme.colors.textPrimary,
            disabledContainerColor = FocusTheme.colors.surfaceCardMuted,
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
 * ===========================================================================
 * 这是整个面板层「看不看得清」的关键
 * ===========================================================================
 * 上一版把所有字段平铺在一列里，靠 16dp 间距区分分组 —— 结果是一屏十几个输入框，
 * 眼睛找不到边界，用户不知道哪几个是一伙的。
 *
 * 现在每个分组是一张**卡片**：白色底、16dp 圆角、1dp 极浅描边、一点几乎看不见的
 * 投影。卡片之外的面板底色（`surfaceSheet`）比卡片暗一档，于是层次不用画线就出来了。
 *
 * @param tone 给这张卡片一个语义色。传 null 是中性卡片（多数情况）；
 *        传 [PastelTone] 会在卡片左上角点一条 3dp 的竖色条，同时标题也用同色墨色 ——
 *        用来标「这一块是危险的」或者「这一块是默认可用的」，而不必额外加图标。
 *        这只是**一层很淡的提示**，不影响卡片本身的底色，界面不会变成一堆积木。
 */
@Composable
internal fun SheetSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: PastelTone? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(16.dp)

    // 入场动效：每张卡片第一次出现时从下方 12dp 淡入。
    //
    // 用 remember 固定「已经出现过」这个事实，所以滚动回来、或者因为输入而重组时
    // 都不会再播一遍 —— 动效只服务于「这块内容出现了」，重复播放就变成噪音。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "sectionAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared) 0f else 12f,
        animationSpec = tween(durationMillis = 220),
        label = "sectionOffset",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = offsetY * density
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SheetSectionLabel(
                text = title,
                // 带语义色的分组标题用同色墨色，指向性更强；中性分组仍然是次级灰。
                color = tone?.ink(colors),
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 3.dp,
                    shape = shape,
                    clip = false,
                    // 带一点点蓝的投影：纯黑投影落在冷白底上会发灰，
                    // 而这个界面的底色全是冷白。
                    ambientColor = colors.cardBorder,
                    spotColor = colors.cardBorder,
                )
                .clip(shape)
                .background(colors.surfaceCard)
                .border(width = 1.dp, color = colors.cardBorder, shape = shape),
        ) {
            if (tone != null) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(tone.ink(colors).copy(alpha = 0.55f)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * 面板顶部那条「毛玻璃」标题栏。
 *
 * ===========================================================================
 * 说清楚一件事：Compose 没有真正的背景模糊
 * ===========================================================================
 * 浏览器里的 `backdrop-filter: blur()` 能模糊「元素背后的内容」。Compose 的
 * `Modifier.blur` **做不到**这件事 —— 它模糊的是这个元素自己的绘制内容，
 * 对一个只有底色的容器调用它等于什么都没发生。
 *
 * 所以这里用的是标准的替代做法：**半透明冷白 + 一条极浅的下边界 + 内容从下面
 * 滚过**。人眼读到的就是「内容在毛玻璃下面」，而这正好也是 Material 的
 * large top app bar 在滚动时的表现。与其说做不到，不如说这是移动端通行的做法。
 */
@Composable
internal fun SheetFrostedHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FocusTheme.colors.frostTint)
                .padding(horizontal = SheetHorizontalPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        HorizontalDivider(thickness = 1.dp, color = FocusTheme.colors.cardBorder)
    }
}

/**
 * 一条细进度条。
 *
 * 这是本地模型下载的核心反馈，所以它必须能表达两件事：
 *  - **确定进度**：传 [fraction]，走一条会做补间动画的实心条；
 *  - **不确定进度**：传 null，显示一条来回滑动的条 —— 总大小还没拿到的时候
 *    显示一个停在 0% 的确定进度条，用户会以为卡死了。
 */
@Composable
internal fun SheetProgressBar(
    fraction: Float?,
    tone: PastelTone,
    modifier: Modifier = Modifier,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    val track = colors.surfaceCardMuted
    val bar = tone.ink(colors)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(track),
    ) {
        if (fraction != null) {
            val animated by animateFloatAsState(
                targetValue = fraction.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = 180),
                label = "progressFraction",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(bar),
            )
        } else {
            // 不确定进度：一条 30% 宽的块来回走。
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val position by transition.animateFloat(
                initialValue = -0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "indeterminatePosition",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = position * size.width / 0.3f }
                    .clip(shape)
                    .background(bar),
            )
        }
    }
}

/**
 * 一行「标签 —— 值」的统计条。
 *
 * 用于备份清单这类「一次性给你看一串数字」的场合。标签在左、值在右，
 * 值用等宽字体，多行之间数字能对齐。
 */
@Composable
internal fun SheetStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (emphasized) {
                FocusTheme.colors.textPrimary
            } else {
                FocusTheme.colors.textSecondary
            },
        )
    }
}

/**
 * 一枚语义色小标签。
 *
 * 与 [SheetChip] 的区别：那个是**可选中的**（有交互），这个是**纯展示**的
 * （一块淡彩底 + 同色深字）。把两者分成两个组件，是因为「能点」和「不能点」
 * 是用户第一眼就要判断的事，长一样会让人反复去点一个点不动的东西。
 */
@Composable
internal fun SheetPill(
    text: String,
    tone: PastelTone,
    modifier: Modifier = Modifier,
) {
    val colors = FocusTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(tone.surface(colors))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone.ink(colors),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一条带淡彩底的说明。
 *
 * 用它代替一段灰色的正文说明：纯灰字在卡片里太容易被略过，而这里写下的往往是
 * 「不这么做会怎样」这类必须被读到的话。
 */
@Composable
internal fun SheetNote(
    text: String,
    tone: PastelTone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val colors = FocusTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.surface(colors))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = tone.ink(colors),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone.ink(colors).copy(alpha = 0.85f),
        )
    }
}

/**
 * 分段选择器：一排等宽按钮，选中项浮起来。
 *
 * 比一排 [SheetChip] 更适合「二选一 / 三选一」的主干分支（在线 vs 本地），
 * 因为等宽 + 连成一条，视觉上明确表示「这些是同一件事的不同取值」，
 * 而散落的胶囊会被读成「可以多选」。
 */
@Composable
internal fun SheetSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCardMuted)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            // 颜色做补间而不是硬切：分段切换是用户会反复来回点的操作，
            // 硬切会让界面显得「咔」一下。
            val background by animateColorAsState(
                targetValue = if (selected) colors.surfaceCard else Color.Transparent,
                animationSpec = tween(durationMillis = 160),
                label = "segmentBackground",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) colors.accent else colors.textSecondary,
                animationSpec = tween(durationMillis = 160),
                label = "segmentText",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 一枚层级徽章。用于记忆列表，让人一眼看出这条在哪一层。
 *
 * 三档层级走**淡彩色的深浅**：核心是藕荷（最需要被注意到）、长久是薄荷、
 * 短期是中性。不用红绿去标层级 —— 那两支在这个应用里有明确含义
 * （需要处理 / 正常），拿来标「这条记忆有多重要」会把信号稀释掉。
 */
@Composable
internal fun TierBadge(text: String, emphasized: Boolean) {
    val colors = FocusTheme.colors
    val tone = if (emphasized) PastelTone.LILAC else PastelTone.MINT
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (emphasized) tone.surface(colors) else colors.surfaceCardMuted)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) tone.ink(colors) else colors.textSecondary,
        )
    }
}
