package com.focussupervisor.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.data.repository.AvatarTarget
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.PersonaGender
import com.focussupervisor.app.domain.model.UserPersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 人设编辑面板的状态。
 *
 * 与 AI 配置面板一样，采用**草稿 / 已保存分离**：用户改了名字但没点保存，
 * 输入框里是草稿，磁盘上还是旧值。区别是这里的保存是显式的（有一个「保存」按钮），
 * 因为人设是一组相互关联的字段，逐字段实时落盘会产生一串半成品状态。
 */
data class PersonaUiState(
    val draftAi: AiPersona = AiPersona(),
    val draftUser: UserPersona = UserPersona(),
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
) {
    /** 草稿是否与磁盘一致。界面据此决定「保存」按钮是否可用。 */
    fun isDirty(saved: Pair<AiPersona, UserPersona>): Boolean =
        draftAi != saved.first || draftUser != saved.second
}

/**
 * 人设面板的状态持有者。
 *
 * 头像的处理值得说明：选择器给回来的是一个 `content://` URI，它的读取授权是**临时的**。
 * 所以这里立刻把它复制进应用私有目录（`PersonaRepository.saveAvatar`），
 * 草稿里存的是复制之后的本地路径。这样即使保存之前进程被回收，
 * 也不会出现「头像文件还在、但引用的是一个已经失效的 URI」这种半截状态。
 */
class PersonaViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = application.appContainer.personas

    private val _uiState = MutableStateFlow(PersonaUiState())
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    /** 最近一次从磁盘读到的人设，用于判断草稿是否被改动。 */
    private var saved: Pair<AiPersona, UserPersona> = AiPersona() to UserPersona()

    init {
        viewModelScope.launch {
            repository.personas.collect { pair ->
                saved = pair.ai to pair.user
                // 只加载一次：之后用户的编辑不能被磁盘回环冲掉。
                // （保存成功之后草稿本来就等于磁盘值，也不需要重新加载。）
                if (!_uiState.value.isLoaded) {
                    _uiState.update {
                        it.copy(draftAi = pair.ai, draftUser = pair.user, isLoaded = true)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // AI 人设
    // -----------------------------------------------------------------------

    fun onAiNameChange(value: String) = editAi { it.copy(name = value.take(MAX_NAME_LENGTH)) }

    fun onAiGenderChange(gender: PersonaGender) = editAi { it.copy(gender = gender) }

    fun onAiDescriptionChange(value: String) =
        editAi { it.copy(description = value.take(MAX_DESCRIPTION_LENGTH)) }

    fun onAiAvatarPicked(uri: Uri) = pickAvatar(uri, AvatarTarget.AI)

    // -----------------------------------------------------------------------
    // 用户人设
    // -----------------------------------------------------------------------

    fun onUserNameChange(value: String) = editUser { it.copy(name = value.take(MAX_NAME_LENGTH)) }

    fun onUserAvatarPicked(uri: Uri) = pickAvatar(uri, AvatarTarget.USER)

    // -----------------------------------------------------------------------
    // 保存
    // -----------------------------------------------------------------------

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        _uiState.update { it.copy(isSaving = true, message = null) }

        viewModelScope.launch {
            val aiOk = repository.updateAi(state.draftAi)
            val userOk = repository.updateUser(state.draftUser)

            _uiState.update {
                it.copy(
                    isSaving = false,
                    message = if (aiOk && userOk) "已保存" else "保存失败，请稍后重试",
                )
            }
        }
    }

    /** 把草稿恢复成磁盘上的值。 */
    fun revert() {
        _uiState.update {
            it.copy(draftAi = saved.first, draftUser = saved.second, message = null)
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun pickAvatar(uri: Uri, target: AvatarTarget) {
        viewModelScope.launch {
            val path = repository.saveAvatar(uri, target)
            if (path == null) {
                _uiState.update { it.copy(message = "这张图片读取失败，换一张试试") }
                return@launch
            }

            _uiState.update { state ->
                when (target) {
                    AvatarTarget.AI -> state.copy(
                        draftAi = state.draftAi.copy(avatarPath = path),
                        message = null,
                    )

                    AvatarTarget.USER -> state.copy(
                        draftUser = state.draftUser.copy(avatarPath = path),
                        message = null,
                    )
                }
            }
        }
    }

    private fun editAi(transform: (AiPersona) -> AiPersona) {
        _uiState.update { it.copy(draftAi = transform(it.draftAi), message = null) }
    }

    private fun editUser(transform: (UserPersona) -> UserPersona) {
        _uiState.update { it.copy(draftUser = transform(it.draftUser), message = null) }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 24
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}
