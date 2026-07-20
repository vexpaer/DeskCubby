package com.deskcubby.app.ui.date

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.repository.DateRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DateRecordViewModel @Inject constructor(
    private val repository: DateRecordRepository,
) : ViewModel() {
    val records: StateFlow<List<DateRecordEntity>> = repository.records.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun create(name: String, icon: String, dateIso: String) {
        launchOperation { repository.create(name.trim(), icon.trim().ifBlank { DEFAULT_DATE_ICON }, dateIso) }
    }

    fun update(id: Long, name: String, icon: String, dateIso: String) {
        launchOperation { repository.update(id, name.trim(), icon.trim().ifBlank { DEFAULT_DATE_ICON }, dateIso) }
    }

    fun delete(id: Long) {
        launchOperation { repository.delete(id) }
    }

    fun consumeError() {
        mutableError.value = null
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Unknown error"
            }
        }
    }
}

const val DEFAULT_DATE_ICON = "🎯"
