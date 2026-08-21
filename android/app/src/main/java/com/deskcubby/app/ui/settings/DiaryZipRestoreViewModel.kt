package com.deskcubby.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.export.DiaryZipRestoreManager
import com.deskcubby.app.data.export.DiaryZipRestoreResult
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class DiaryZipRestoreState(
    val busy: Boolean = false,
    val result: DiaryZipRestoreResult? = null,
    val warning: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DiaryZipRestoreViewModel @Inject constructor(
    private val restoreManager: DiaryZipRestoreManager,
    private val settingsRepository: SettingsRepository,
    private val structuredRecordsRepository: StructuredRecordsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiaryZipRestoreState())
    val state: StateFlow<DiaryZipRestoreState> = _state.asStateFlow()

    fun restore(uri: Uri) {
        if (_state.value.busy) return
        _state.value = DiaryZipRestoreState(busy = true)
        viewModelScope.launch {
            try {
                val result = restoreManager.restore(uri)
                val indexWarning = runCatching {
                    structuredRecordsRepository.rebuildIndex(settingsRepository.settings.first())
                }.exceptionOrNull()?.message
                _state.value = DiaryZipRestoreState(
                    result = result,
                    warning = indexWarning?.let {
                        "文件已恢复，但结构化索引重建失败：$it / Files restored, but structured index rebuild failed"
                    },
                )
            } catch (error: CancellationException) {
                _state.value = DiaryZipRestoreState()
                throw error
            } catch (error: Exception) {
                _state.value = DiaryZipRestoreState(
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "恢复失败 / Restore failed",
                )
            }
        }
    }

    fun consumeResult() {
        if (!_state.value.busy) _state.value = DiaryZipRestoreState()
    }
}
