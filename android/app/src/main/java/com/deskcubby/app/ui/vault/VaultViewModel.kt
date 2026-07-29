package com.deskcubby.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.repository.VaultContentState
import com.deskcubby.app.data.repository.VaultLockState
import com.deskcubby.app.data.repository.VaultPasswordChangeResult
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
enum class VaultUiError {
    WRONG_PASSWORD,
    CORRUPTED_ITEMS,
    OPERATION_FAILED,
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
) : ViewModel() {
    val lockState: StateFlow<VaultLockState> = repository.lockState

    val contentState: StateFlow<VaultContentState> = repository.contentState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VaultContentState(),
    )

    private val mutableError = MutableStateFlow<VaultUiError?>(null)
    val error: StateFlow<VaultUiError?> = mutableError.asStateFlow()

    fun setupPassword(password: String) {
        viewModelScope.launch {
            val ok = runGuarded { repository.setupPassword(password) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.OPERATION_FAILED
            }
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
            val result = runGuarded {
                repository.changePassword(oldPassword, newPassword)
            }
            val ok = result == VaultPasswordChangeResult.SUCCESS
            if (mutableError.value == null) {
                mutableError.value = when (result) {
                    VaultPasswordChangeResult.SUCCESS -> null
                    VaultPasswordChangeResult.WRONG_PASSWORD -> VaultUiError.WRONG_PASSWORD
                    VaultPasswordChangeResult.CORRUPTED_ITEMS -> VaultUiError.CORRUPTED_ITEMS
                    VaultPasswordChangeResult.INVALID_NEW_PASSWORD ->
                        VaultUiError.OPERATION_FAILED
                    null -> VaultUiError.OPERATION_FAILED
                }
            }
            onDone(ok)
        }
    }

    fun addItem(content: String, note: String?, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runGuarded { repository.addItem(content, note) } ?: false
            if (!ok && mutableError.value == null) {
                mutableError.value = VaultUiError.OPERATION_FAILED
            }
            onDone(ok)
        }
    }

    fun updateItem(id: Long, content: String, note: String?, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runGuarded { repository.updateItem(id, content, note) } ?: false
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

    fun reorderItems(orderedIds: List<Long>) {
        viewModelScope.launch {
            val ok = runGuarded { repository.reorderItems(orderedIds) } ?: false
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
