package com.focussupervisor.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.backup.BackupDirectoryReader
import com.focussupervisor.app.core.backup.BackupExport
import com.focussupervisor.app.core.backup.RestoreCandidate
import com.focussupervisor.app.data.repository.DataStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 数据管理面板的状态。 */
data class DataManageUiState(
    val stats: DataStats = DataStats(),
    val isExporting: Boolean = false,
    /** 刚导出完的结果。非 null 时界面显示「导出到哪儿、叫什么名字」。 */
    val export: BackupExport? = null,
    val isReading: Boolean = false,
    /** 已经从磁盘读出来、等着用户确认的备份。 */
    val candidate: RestoreCandidate? = null,
    val isRestoring: Boolean = false,
    val message: String? = null,
    val isMessageError: Boolean = false,
) {
    val isBusy: Boolean get() = isExporting || isReading || isRestoring
}

/**
 * 数据管理面板的状态持有者。
 *
 * ===========================================================================
 * 恢复为什么是两步
 * ===========================================================================
 * ```
 *   选文件夹  →  读出来（只读）  →  给用户看清单  →  确认  →  才真正写入
 * ```
 *
 * 恢复会**覆盖掉当前全部数据**，而且不可撤销。中间那一步「给你看清楚这是一份
 * 什么时候的备份、里面有多少条对话和记忆」，是这个功能里唯一能防止误操作的地方。
 * 所以哪怕多一次点击也不能省：直接把选中的文件盖上去，等于让用户在看不见内容
 * 的情况下赌一把。
 */
class DataManageViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.dataMaintenance

    private val _uiState = MutableStateFlow(DataManageUiState())
    val uiState: StateFlow<DataManageUiState> = _uiState.asStateFlow()

    /** 每次打开面板都重新读一遍体量。 */
    fun onSheetOpened() {
        _uiState.value = DataManageUiState()
        refreshStats()
    }

    private fun refreshStats() {
        viewModelScope.launch {
            val stats = repository.readStats()
            _uiState.value = _uiState.value.copy(stats = stats)
        }
    }

    // -----------------------------------------------------------------------
    // 导出
    // -----------------------------------------------------------------------

    fun export() {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(
            isExporting = true,
            export = null,
            message = null,
        )

        viewModelScope.launch {
            val result = repository.export()
            _uiState.value = result.fold(
                onSuccess = { export ->
                    _uiState.value.copy(
                        isExporting = false,
                        export = export,
                        message = if (export.isSplit) {
                            "已分成 ${export.fileNames.size - 1} 片导出"
                        } else {
                            "已导出"
                        },
                        isMessageError = false,
                    )
                },
                onFailure = { throwable ->
                    _uiState.value.copy(
                        isExporting = false,
                        message = throwable.message?.takeIf { it.isNotBlank() }
                            ?: "导出失败，请重试",
                        isMessageError = true,
                    )
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // 恢复
    // -----------------------------------------------------------------------

    /** 用户在系统文件选择器里选了一个目录。 */
    fun onBackupFolderPicked(treeUri: Uri) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(
            isReading = true,
            candidate = null,
            export = null,
            message = null,
        )

        viewModelScope.launch {
            // 枚举目录是 ContentResolver 查询，必须离开主线程。
            val children = withContext(Dispatchers.IO) {
                BackupDirectoryReader.listChildren(getApplication<Application>(), treeUri)
            }

            if (children.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isReading = false,
                    message = "这个文件夹是空的，或者系统不允许读取它",
                    isMessageError = true,
                )
                return@launch
            }

            val result = repository.readBackup(children)
            _uiState.value = result.fold(
                onSuccess = { candidate ->
                    _uiState.value.copy(
                        isReading = false,
                        candidate = candidate,
                        message = null,
                    )
                },
                onFailure = { throwable ->
                    _uiState.value.copy(
                        isReading = false,
                        message = throwable.message?.takeIf { it.isNotBlank() }
                            ?: "这份备份读不出来",
                        isMessageError = true,
                    )
                },
            )
        }
    }

    fun dismissCandidate() {
        _uiState.value = _uiState.value.copy(candidate = null, message = null)
    }

    fun confirmRestore() {
        val candidate = _uiState.value.candidate ?: return
        if (_uiState.value.isBusy) return

        _uiState.value = _uiState.value.copy(isRestoring = true, message = null)
        viewModelScope.launch {
            val ok = repository.restore(candidate)
            _uiState.value = if (ok) {
                _uiState.value.copy(
                    isRestoring = false,
                    candidate = null,
                    message = "恢复完成。聊天页的数据已经换过来了。",
                    isMessageError = false,
                )
            } else {
                _uiState.value.copy(
                    isRestoring = false,
                    message = "恢复失败。当前数据没有被改动。",
                    isMessageError = true,
                )
            }
            refreshStats()
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
