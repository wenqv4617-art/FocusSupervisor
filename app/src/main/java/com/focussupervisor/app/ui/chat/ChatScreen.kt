package com.focussupervisor.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.data.mock.MockChatData
import com.focussupervisor.app.domain.model.ActionItem
import com.focussupervisor.app.domain.model.ChatDialog
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.ChatUiState
import com.focussupervisor.app.domain.model.PermissionTarget
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.ui.components.MessageBubble
import com.focussupervisor.app.ui.components.PermissionCheckDialog
import com.focussupervisor.app.ui.components.PlusActionPanel
import com.focussupervisor.app.ui.components.PolicyStatusDialog
import com.focussupervisor.app.ui.components.defaultActionItems
import com.focussupervisor.app.ui.settings.AiConfigSheet
import com.focussupervisor.app.ui.settings.MemorySheet
import com.focussupervisor.app.ui.settings.PersonaSheet
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 聊天主界面（仿微信）。
 *
 * 布局自下而上只有三层：
 * ```
 *   ┌──────────────────────────┐
 *   │ TopAppBar  名称 + 状态    │  ← 居中，无任何按钮
 *   ├──────────────────────────┤
 *   │ LazyColumn  消息流        │  ← 自动滚到最新一条
 *   ├──────────────────────────┤
 *   │ 输入栏  [输入][发送][+]    │  ← 唯一的功能入口
 *   │ 「+」面板（可展开）        │
 *   └──────────────────────────┘
 * ```
 *
 * 本文件分两个入口，这是 Compose 的标准做法：
 * - [ChatRoute] 有状态：负责取 ViewModel、订阅 StateFlow，只做「接线」；
 * - [ChatScreen] 无状态：只接收一个 [ChatUiState] 和一串回调。
 * 无状态版本可以被 @Preview 和 UI 测试直接调用，不需要真实 ViewModel。
 */

/**
 * 有状态入口。`MainActivity` 只需要调用它。
 *
 * `collectAsStateWithLifecycle` 而不是 `collectAsState`：前者会在页面进入后台时
 * 自动停止收集，避免不可见时仍做无谓的重组和耗电。这是当前官方推荐写法。
 */
@Composable
fun ChatRoute(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 每次回到前台重查一次权限：用户很可能是去设置页开完权限再回来的，
    // 不重查的话界面会一直显示「未开启」，而他刚刚明明开好了。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    ChatScreen(
        uiState = uiState,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onToggleActionPanel = viewModel::onToggleActionPanel,
        onActionSelected = viewModel::onActionSelected,
        onDialogDismiss = viewModel::onDialogDismiss,
        onOpenPermissionSettings = viewModel::onOpenPermissionSettings,
        onAiConfigDismiss = viewModel::onAiConfigSheetDismiss,
        onPersonaDismiss = viewModel::onPersonaSheetDismiss,
        onMemoryDismiss = viewModel::onMemorySheetDismiss,
        modifier = modifier,
    )
}

/**
 * 无状态聊天界面。
 *
 * @param uiState 当前会话状态快照（不可变）。
 * @param onInputChange 输入框内容变化。
 * @param onSend 点击发送。
 * @param onToggleActionPanel 点击「+」展开/收起面板。
 * @param onActionSelected 点击面板里的某个功能。
 * @param onDialogDismiss 关闭模态面板。
 * @param onOpenPermissionSettings 请求跳转到某项权限的系统设置页。
 * @param onAiConfigDismiss 关闭「AI 配置中心」。
 * @param onPersonaDismiss 关闭「人设管理」。
 * @param onMemoryDismiss 关闭「记忆管理」。
 */
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleActionPanel: () -> Unit,
    onActionSelected: (ActionItem) -> Unit,
    onDialogDismiss: () -> Unit,
    onOpenPermissionSettings: (PermissionTarget) -> Unit,
    onAiConfigDismiss: () -> Unit,
    onPersonaDismiss: () -> Unit,
    onMemoryDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = FocusTheme.colors.chatBackground,
        topBar = {
            ChatTopBar(
                title = uiState.agentName,
                subtitle = uiState.statusText,
            )
        },
        bottomBar = {
            ChatBottomArea(
                uiState = uiState,
                onInputChange = onInputChange,
                onSend = onSend,
                onToggleActionPanel = onToggleActionPanel,
                onActionSelected = onActionSelected,
            )
        },
    ) { scaffoldPadding ->
        MessageList(
            messages = uiState.messages,
            personas = uiState.personas,
            contentPadding = scaffoldPadding,
        )
    }

    // 模态面板画在 Scaffold 之外：它们要盖住顶栏与输入栏，而不是被挤在内容区里。
    // 同一时刻最多只会有一个（ChatDialog 是枚举，从类型上排除了「两个都开」）。
    when (uiState.dialog) {
        ChatDialog.PERMISSION_CHECK -> PermissionCheckDialog(
            permissions = uiState.permissions,
            onOpenSettings = onOpenPermissionSettings,
            onDismiss = onDialogDismiss,
        )

        ChatDialog.POLICY_STATUS -> {
            // 以「面板打开的那一刻」为基准计算剩余豁免时长。用 remember 固定住，
            // 否则每次重组都会取新时间，列表会在用户眼皮底下自己变化。
            val nowMillis = remember(uiState.dialog) { System.currentTimeMillis() }
            PolicyStatusDialog(
                todos = uiState.todos,
                whitelist = uiState.whitelist,
                nowMillis = nowMillis,
                onDismiss = onDialogDismiss,
            )
        }

        null -> Unit
    }

    // 「AI 配置中心」自底向上滑出。它自带 ViewModel：配置编辑态和主会话状态是
    // 两件互不相干的事，塞进 ChatViewModel 只会让那个类继续膨胀。
    if (uiState.isAiConfigSheetVisible) {
        AiConfigSheet(onDismiss = onAiConfigDismiss)
    }

    if (uiState.isPersonaSheetVisible) {
        PersonaSheet(onDismiss = onPersonaDismiss)
    }

    if (uiState.isMemorySheetVisible) {
        MemorySheet(onDismiss = onMemoryDismiss)
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/**
 * 顶栏：居中显示 AI 监管者名称，下方一行极简状态副标题。
 *
 * 刻意不放任何 action 图标：所有控制能力都在底部「+」面板里，顶栏出现按钮会让
 * 界面立刻从「聊天」变成「控制台」。
 *
 * 底部的发丝线是划分层次的唯一手段，不用阴影也不用 elevation。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
) {
    Column {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = FocusTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = FocusTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = FocusTheme.colors.chromeBackground,
                titleContentColor = FocusTheme.colors.textPrimary,
            ),
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = FocusTheme.colors.hairline,
        )
    }
}

// ---------------------------------------------------------------------------
// 消息列表
// ---------------------------------------------------------------------------

/**
 * 消息列表。
 *
 * 自动滚动的实现要点：`LaunchedEffect` 的 key 用 `messages.size` 而不是整个
 * messages 列表 —— 列表里任意一条内容变化（例如未来支持编辑）都不应该触发滚动，
 * 只有「条数变了」才是新消息到达的信号。用 size 做 key 还能避免每次重组都重启协程。
 */
@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    personas: PersonaPair,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (messages.isEmpty()) {
            Text(
                text = "还没有任何记录。\n从这里开始今天的专注。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = messages,
                    // key 用稳定 id：消息增删时 Compose 能精确保留每一条的
                    // 内部状态与滚动位置，不会整列表重画。
                    key = { message -> message.id },
                ) { message ->
                    MessageBubble(message = message, personas = personas)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 底部输入区
// ---------------------------------------------------------------------------

/**
 * 底部区域 = 输入栏 + 可展开的「+」面板。
 *
 * 边距处理：`WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)` 一次性覆盖
 * 两种情况 —— 没有键盘时它是导航栏高度，键盘弹起时它是键盘高度。比分别写
 * `navigationBarsPadding()` 和 `imePadding()` 再手动避免叠加要可靠得多。
 *
 * 注意只取 Bottom 一侧：如果直接用 safeDrawing，顶部的状态栏高度也会被加进来，
 * 把整个输入栏往下推一截。
 */
@Composable
private fun ChatBottomArea(
    uiState: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleActionPanel: () -> Unit,
    onActionSelected: (ActionItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FocusTheme.colors.chromeBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        ChatInputBar(
            inputText = uiState.inputText,
            canSend = uiState.canSend,
            isActionPanelVisible = uiState.isActionPanelVisible,
            onInputChange = onInputChange,
            onSend = onSend,
            onToggleActionPanel = onToggleActionPanel,
        )

        AnimatedVisibility(visible = uiState.isActionPanelVisible) {
            PlusActionPanel(
                actions = uiState.actions,
                onActionClick = onActionSelected,
            )
        }
    }
}

/** 输入栏高度。 */
private val InputMinHeight = 38.dp

/** 输入框最大高度：约 4 行，超出后内部滚动，避免长文本把输入栏顶到半屏。 */
private val InputMaxHeight = 104.dp

/** 输入栏内的圆形按钮尺寸。比 Material 默认的 48dp 略小，让输入框拿到更多宽度。 */
private val BarIconButtonSize = 40.dp

/**
 * 输入栏：输入框 + 发送 + 「+」。
 *
 * 输入框用 [BasicTextField] 自绘而不是用 Material 的 `OutlinedTextField`：
 * 后者自带 label、下划线/外框、最小高度和一堆内边距，为了得到微信那种「一个干净
 * 的白色圆角条」需要覆盖十几项参数，还不如直接画。`BasicTextField` 本身没有
 * 被弃用，它是 Compose 里做自定义输入框的正确起点。
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    canSend: Boolean,
    isActionPanelVisible: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleActionPanel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        // 底部对齐：输入框长高时，两侧按钮仍然贴着底边，不会浮在中间。
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = InputMinHeight, max = InputMaxHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(FocusTheme.colors.inputField)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = FocusTheme.colors.textPrimary,
            ),
            cursorBrush = SolidColor(FocusTheme.colors.accent),
            maxLines = 4,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "说点什么…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusTheme.colors.textSecondary,
                        )
                    }
                    innerTextField()
                }
            },
        )

        // 发送：草稿为空时保持可见但置灰，位置不跳动，用户一眼知道这里能发。
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.size(BarIconButtonSize),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = if (canSend) {
                    FocusTheme.colors.accent
                } else {
                    FocusTheme.colors.accentDisabled
                },
                modifier = Modifier.size(22.dp),
            )
        }

        // 「+」：展开时旋转 45° 变成「×」，用同一个图标表达两种状态，
        // 比换图标更连贯，也就省掉了一个 Close 图标的引入。
        val addRotation by animateFloatAsState(
            targetValue = if (isActionPanelVisible) 45f else 0f,
            label = "plusRotation",
        )
        IconButton(
            onClick = onToggleActionPanel,
            modifier = Modifier.size(BarIconButtonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = if (isActionPanelVisible) "收起功能面板" else "展开功能面板",
                tint = FocusTheme.colors.iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(addRotation),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

@Preview(name = "聊天主界面", showBackground = true, backgroundColor = 0xFFEDEDED)
@Composable
private fun ChatScreenPreview() {
    FocusSupervisorTheme {
        ChatScreen(
            uiState = ChatUiState(
                messages = MockChatData.initialMessages(),
                actions = defaultActionItems(),
                inputText = "",
                isActionPanelVisible = false,
            ),
            onInputChange = {},
            onSend = {},
            onToggleActionPanel = {},
            onActionSelected = {},
            onDialogDismiss = {},
            onOpenPermissionSettings = {},
            onAiConfigDismiss = {},
            onPersonaDismiss = {},
            onMemoryDismiss = {},
        )
    }
}

@Preview(name = "聊天主界面 · 面板展开", showBackground = true, backgroundColor = 0xFFEDEDED)
@Composable
private fun ChatScreenPanelExpandedPreview() {
    FocusSupervisorTheme {
        ChatScreen(
            uiState = ChatUiState(
                messages = MockChatData.initialMessages(),
                actions = defaultActionItems(needsPermissionAttention = true),
                inputText = "我把手机放下了",
                isActionPanelVisible = true,
            ),
            onInputChange = {},
            onSend = {},
            onToggleActionPanel = {},
            onActionSelected = {},
            onDialogDismiss = {},
            onOpenPermissionSettings = {},
            onAiConfigDismiss = {},
            onPersonaDismiss = {},
            onMemoryDismiss = {},
        )
    }
}
