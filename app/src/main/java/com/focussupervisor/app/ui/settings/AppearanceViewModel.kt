package com.focussupervisor.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.style.StyleInjector
import com.focussupervisor.app.data.repository.BackgroundImageLoader
import com.focussupervisor.app.domain.model.BackgroundMode
import com.focussupervisor.app.domain.model.ChatAppearance
import com.focussupervisor.app.domain.model.ChatPresetIds
import com.focussupervisor.app.domain.model.StyleParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 外观面板的状态。 */
data class AppearanceUiState(
    val draft: ChatAppearance = ChatAppearance(),
    /** 当前样式文本的解析结果。没保存过的文本不参与解析，见 [AppearanceViewModel.onStyleSheetChange]。 */
    val parse: StyleParseResult = StyleParseResult(),
    /** 背景图是否真的在磁盘上。 */
    val hasBackgroundImage: Boolean = false,
    val backgroundSizeBytes: Long = 0,
    /** 待裁剪的源图。非 null 时界面弹全屏裁剪页。 */
    val cropSource: Bitmap? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
    val isBusy: Boolean = false,
) {
    /** 是否处于「用户动过手」的状态，用来在标题旁显示「已自定义」。 */
    val isCustomized: Boolean get() = draft.isCustomized
}

/**
 * 聊天外观面板的状态持有者。
 *
 * ===========================================================================
 * 一、改动即生效，没有「保存外观」按钮
 * ===========================================================================
 * 主题、背景、透明度都是**所见即所得**的：点一下主题，聊天页立刻变；拖一下透明度、
 * 松手就落盘。用户不需要理解「草稿」和「已保存」的区别，也不必担心退出去没保存。
 *
 * 唯一的例外是**样式注入文本框**：那是一段要反复改的代码，逐字符落盘既没有意义
 * （写一半的 CSS 必然解析失败），也会让下面那列「哪句生效了」在打字过程中疯狂闪。
 * 所以它有独立的「保存样式」动作。
 *
 * ===========================================================================
 * 二、透明度用 onValueChangeFinished 落盘
 * ===========================================================================
 * 滑块拖动过程中会给几十次回调。每次都写 DataStore 不只是浪费，还会让
 * 「当前值」与「磁盘值」在拖动中反复互相追赶。做法是拖动时只更新草稿，
 * 松手（`onValueChangeFinished`）时写一次。
 */
class AppearanceViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.appearance

    private val _uiState = MutableStateFlow(AppearanceUiState())
    val uiState: StateFlow<AppearanceUiState> = _uiState.asStateFlow()

    /** 每次打开面板都重新对齐磁盘内容。 */
    fun onSheetOpened() {
        val current = repository.appearance.value
        _uiState.value = AppearanceUiState(
            draft = current,
            parse = StyleInjector.parse(current.styleSheet),
            hasBackgroundImage = repository.hasBackgroundImage(),
            backgroundSizeBytes = repository.backgroundFile()?.length() ?: 0L,
        )
    }

    // -----------------------------------------------------------------------
    // 主题
    // -----------------------------------------------------------------------

    fun selectPreset(presetId: String) {
        val next = _uiState.value.draft.copy(presetId = ChatPresetIds.sanitize(presetId))
        persist(next, successMessage = null)
    }

    // -----------------------------------------------------------------------
    // 背景
    // -----------------------------------------------------------------------

    /** 用户从系统相册选了一张图。这里只负责读进内存，裁剪在下一步。 */
    fun onBackgroundPicked(uri: Uri) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(isBusy = true, message = null)

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BackgroundImageLoader.load(
                    context = getApplication<Application>(),
                    uri = uri,
                    maxEdge = ChatAppearance.BACKGROUND_MAX_EDGE,
                )
            }

            _uiState.value = if (bitmap == null) {
                _uiState.value.copy(
                    isBusy = false,
                    message = "这张图读不出来，换一张试试",
                    isMessageError = true,
                )
            } else {
                _uiState.value.copy(isBusy = false, cropSource = bitmap, message = null)
            }
        }
    }

    fun onCropCancelled() {
        // 取消时把源图回收掉。一张 2048 边的 ARGB 位图是 16MB，
        // 用户连点几次「换一张图又取消」就能把它变成一次 GC 抖动。
        _uiState.value.cropSource?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value = _uiState.value.copy(cropSource = null)
    }

    /** 裁剪完成，落盘。 */
    fun onCropConfirmed(bitmap: Bitmap) {
        val opacity = _uiState.value.draft.backgroundImageOpacity
        _uiState.value = _uiState.value.copy(cropSource = null, isBusy = true, message = null)

        viewModelScope.launch {
            val updated = repository.applyBackgroundImage(bitmap, opacity)
            bitmap.recycle()

            _uiState.value = if (updated == null) {
                _uiState.value.copy(
                    isBusy = false,
                    message = "背景图保存失败，可能是存储空间不够",
                    isMessageError = true,
                )
            } else {
                _uiState.value.copy(
                    draft = updated,
                    isBusy = false,
                    hasBackgroundImage = true,
                    backgroundSizeBytes = repository.backgroundFile()?.length() ?: 0L,
                    message = "背景图已更新",
                    isMessageError = false,
                )
            }
        }
    }

    fun clearBackground() {
        viewModelScope.launch {
            val updated = repository.clearBackgroundImage()
            _uiState.value = if (updated == null) {
                _uiState.value.copy(message = "移除失败，请重试", isMessageError = true)
            } else {
                _uiState.value.copy(
                    draft = updated,
                    hasBackgroundImage = false,
                    backgroundSizeBytes = 0L,
                    message = "已恢复主题自带背景",
                    isMessageError = false,
                )
            }
        }
    }

    /** 拖动中：只改草稿，不落盘。 */
    fun onOpacityDraftChange(value: Float) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(
                backgroundImageOpacity = value.coerceIn(
                    ChatAppearance.MIN_IMAGE_OPACITY,
                    1f,
                ),
            ),
        )
    }

    /** 松手：落盘。 */
    fun onOpacityCommit() {
        val next = _uiState.value.draft
        viewModelScope.launch {
            if (!repository.update(next)) {
                _uiState.value = _uiState.value.copy(
                    message = "透明度保存失败",
                    isMessageError = true,
                )
            }
        }
    }

    /** 选择背景来源（纯色 / 跟随主题）。 */
    fun selectBackgroundMode(mode: BackgroundMode) {
        val next = when (mode) {
            BackgroundMode.PRESET -> _uiState.value.draft.copy(backgroundMode = mode)
            BackgroundMode.IMAGE -> {
                if (!repository.hasBackgroundImage()) {
                    _uiState.value = _uiState.value.copy(
                        message = "还没有自定义背景图，先选一张",
                        isMessageError = true,
                    )
                    return
                }
                _uiState.value.draft.copy(backgroundMode = mode)
            }

            BackgroundMode.SOLID -> _uiState.value.draft.copy(
                backgroundMode = mode,
                backgroundSolidColor = _uiState.value.draft.backgroundSolidColor
                    ?: DEFAULT_SOLID_BACKGROUND,
            )
        }
        persist(next, successMessage = null)
    }

    fun selectSolidColor(color: Long) {
        persist(
            _uiState.value.draft.copy(
                backgroundMode = BackgroundMode.SOLID,
                backgroundSolidColor = color,
            ),
            successMessage = null,
        )
    }

    // -----------------------------------------------------------------------
    // 样式注入
    // -----------------------------------------------------------------------

    /** 打字中：只更新文本，不解析。 */
    fun onStyleSheetChange(text: String) {
        _uiState.value = _uiState.value.copy(
            draft = _uiState.value.draft.copy(
                styleSheet = text.take(ChatAppearance.MAX_STYLE_SHEET_LENGTH),
            ),
            message = null,
        )
    }

    /**
     * 保存并解析样式。
     *
     * 解析放在「保存」这一步而不是打字过程中：用户还没写完时必然会解析失败，
     * 下面那列「忽略的规则」会在打字时不断跳动，看起来像界面在自己抽搐。
     */
    fun saveStyleSheet() {
        val text = _uiState.value.draft.styleSheet
        val result = StyleInjector.parse(text)

        viewModelScope.launch {
            val ok = repository.update(_uiState.value.draft)
            _uiState.value = _uiState.value.copy(
                parse = result,
                message = when {
                    !ok -> "样式保存失败，请重试"
                    result.applied.isEmpty() && text.isBlank() -> "样式已清空，聊天页恢复主题原样"
                    result.applied.isEmpty() -> "一条都没生效，下面写了原因"
                    else -> "已生效 ${result.applied.size} 条"
                },
                isMessageError = !ok || (result.applied.isEmpty() && text.isNotBlank()),
            )
        }
    }

    /** 恢复出厂外观。 */
    fun resetAll() {
        val next = ChatAppearance()
        viewModelScope.launch {
            repository.update(next)
            val cleared = repository.clearBackgroundImage()
            _uiState.value = _uiState.value.copy(
                draft = next,
                parse = StyleParseResult(),
                hasBackgroundImage = false,
                backgroundSizeBytes = 0L,
                message = if (cleared == null) "背景图移除失败，其余已恢复" else "已恢复默认外观",
                isMessageError = cleared == null,
            )
        }
    }

    // -----------------------------------------------------------------------

    private fun persist(next: ChatAppearance, successMessage: String?) {
        _uiState.value = _uiState.value.copy(draft = next, message = successMessage)
        viewModelScope.launch {
            if (!repository.update(next)) {
                _uiState.value = _uiState.value.copy(
                    message = "保存失败，请重试",
                    isMessageError = true,
                )
            }
        }
    }

    private companion object {
        /** 默认纯色背景：就用微信那支中性灰，跟主题最不打架。 */
        const val DEFAULT_SOLID_BACKGROUND = 0xFFEDEDEDL
    }
}
