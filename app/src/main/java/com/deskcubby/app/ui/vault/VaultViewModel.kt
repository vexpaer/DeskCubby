package com.deskcubby.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.repository.VaultItem
import com.deskcubby.app.data.repository.VaultLockState
import com.deskcubby.app.data.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI-facing error codes. Kept as an enum (instead of strings) so the screen can render
 * them bilingually via tr(), and so no secret material can ever leak into a message.
 */
enum class VaultUiError { WRONG_PASSWORD, OPERATION_FAILED }

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
) : ViewModel() {
    val lockState: StateFlow<VaultLockState> = repository.lockState

    val items: StateFlow<List<VaultItem>> = repository.items.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val mutableError = MutableStateFlow<VaultUiError?>(null)
    val error: StateFlow<VaultUiError?> = mutableError.asStateFlow()

    fun setupPassword(password: String) {
        viewModelScope.launch {
            runGuarded { repository.setupPassword(password) }
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            val ok = runGuarded { repository.unlock(password) } ?: return@launch
            if (!ok) mutableError.value = VaultUiError.WRONG_PASSWORD
        }
    }

    fun lock() {
        repository.lock()
    }

    fun changePassword(oldPassword: String, newPassword: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runGuarded { repository.changePassword(oldPassword, newPassword) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.WRONG_PASSWORD
            }
            onDone(ok)
        }
    }

    fun addItem(title: String, content: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runGuarded { repository.addItem(title, content) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.OPERATION_FAILED
            }
            onDone(ok)
        }
    }

    fun updateItem(id: Long, title: String, content: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runGuarded { repository.updateItem(id, title, content) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.OPERATION_FAILED
            }
            onDone(ok)
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch {
            val ok = runGuarded { repository.deleteItem(id) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.OPERATION_FAILED
            }
        }
    }

    fun consumeError() {
        mutableError.value = null
    }

    /**
     * Runs [block], mapping unexpected failures to [VaultUiError.OPERATION_FAILED].
     * Exception messages are intentionally discarded: they must never reach the UI or logs
     * because vault code paths handle passwords and plaintext.
     */
    private suspend fun <T> runGuarded(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        mutableError.value = VaultUiError.OPERATION_FAILED
        null
    }
}
