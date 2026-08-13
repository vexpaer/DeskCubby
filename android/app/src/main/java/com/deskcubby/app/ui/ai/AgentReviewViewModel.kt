package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.agent.AgentReviewMutation
import com.deskcubby.app.agent.AgentReviewRepository
import com.deskcubby.app.agent.AgentReviewRun
import com.deskcubby.app.agent.AgentReviewToolEvent
import com.deskcubby.app.agent.AgentRuntimeException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentReviewUiState(
    val runs: List<AgentReviewRun> = emptyList(),
    val selectedRunId: String? = null,
    val mutations: List<AgentReviewMutation> = emptyList(),
    val events: List<AgentReviewToolEvent> = emptyList(),
    val undoingMutationId: Long? = null,
    val message: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentReviewViewModel @Inject constructor(
    private val repository: AgentReviewRepository,
) : ViewModel() {
    private val selectedRunId = MutableStateFlow<String?>(null)
    private val actionState = MutableStateFlow(AgentReviewUiState())

    private val runs = repository.observeRuns()
    private val mutations = selectedRunId.flatMapLatest { runId ->
        if (runId == null) flowOf(emptyList()) else repository.observeMutations(runId)
    }
    private val events = selectedRunId.flatMapLatest { runId ->
        if (runId == null) flowOf(emptyList()) else repository.observeToolEvents(runId)
    }

    val uiState: StateFlow<AgentReviewUiState> = combine(
        runs,
        selectedRunId,
        mutations,
        events,
        actionState,
    ) { runItems, selected, mutationItems, eventItems, action ->
        val resolved = selected?.takeIf { id -> runItems.any { it.runId == id } }
            ?: runItems.firstOrNull()?.runId
        if (resolved != selected) selectedRunId.value = resolved
        action.copy(
            runs = runItems,
            selectedRunId = resolved,
            mutations = if (resolved == selected) mutationItems else emptyList(),
            events = if (resolved == selected) eventItems else emptyList(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgentReviewUiState(),
    )

    fun selectRun(runId: String) {
        selectedRunId.value = runId
    }

    fun undo(mutationId: Long) {
        if (actionState.value.undoingMutationId != null) return
        actionState.update { it.copy(undoingMutationId = mutationId, errorMessage = null, message = null) }
        viewModelScope.launch {
            try {
                repository.undo(mutationId)
                actionState.update { it.copy(message = "Undo 完成 / Undo completed") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentRuntimeException) {
                actionState.update { it.copy(errorMessage = error.message ?: "Undo 失败 / Undo failed") }
            } catch (_: Exception) {
                actionState.update { it.copy(errorMessage = "无法安全撤回此修改。 / This change could not be safely undone.") }
            } finally {
                actionState.update { it.copy(undoingMutationId = null) }
            }
        }
    }

    fun consumeMessage() {
        actionState.update { it.copy(message = null, errorMessage = null) }
    }
}
