package com.focussupervisor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.focussupervisor.app.ui.chat.ChatRoute
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme

/**
 * 应用唯一入口。
 *
 * 这一层刻意保持「薄」：只做三件事 —— 开启边到边显示、套上主题、把 [ChatRoute]
 * 挂上去。所有界面状态都在 `ChatViewModel` 里，所有布局都在 `ChatScreen` 里，
 * Activity 不参与任何一个。
 *
 * 初始数据不由这里直接塞给界面：`ChatViewModel` 在构造时会用
 * `data/mock/MockChatData` 里的假数据（两条对话 + 两条系统事件）初始化
 * `ChatUiState`。这样做的好处是换成真实数据源时只动 ViewModel 一行，
 * Activity 完全不受影响。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边：内容延伸到状态栏与导航栏之下，具体留白由 Compose 侧的
        // WindowInsets 决定（见 ChatScreen 里的 ChatBottomArea）。
        // 必须在 super.onCreate 之前调用，且在 setContent 之前生效。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            FocusSupervisorTheme {
                ChatRoute()
            }
        }
    }
}
