package com.anytypeio.anytype.feature.pebble.ui.approval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApprovalUiState(
    val pendingChangeSets: List<ChangeSet> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val message: String? = null
) {
    val current: ChangeSet? get() = pendingChangeSets.getOrNull(currentIndex)
    val hasMore: Boolean get() = currentIndex < pendingChangeSets.lastIndex
}

class ApprovalViewModel @Inject constructor(
    private val changeStore: ChangeStore,
    private val changeExecutor: ChangeExecutor
) : ViewModel() {

    private val _state = MutableStateFlow(ApprovalUiState(isLoading = true))
    val state: StateFlow<ApprovalUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val pending = changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50)
            _state.value = ApprovalUiState(
                pendingChangeSets = pending,
                currentIndex = 0,
                isLoading = false
            )
        }
    }

    fun approve() {
        val cs = _state.value.current ?: return
        viewModelScope.launch {
            changeStore.updateStatus(cs.id, ChangeSetStatus.APPROVED)
            val result = changeExecutor.execute(cs)
            val msg = when (result) {
                is ExecutionResult.Success -> "Changes applied"
                is ExecutionResult.PartialFailure -> "Apply failed: ${result.error.message}"
            }
            advance(msg)
        }
    }

    fun reject() {
        val cs = _state.value.current ?: return
        viewModelScope.launch {
            changeStore.updateStatus(cs.id, ChangeSetStatus.REJECTED)
            advance("Rejected")
        }
    }

    fun next() {
        val s = _state.value
        if (s.hasMore) {
            _state.value = s.copy(currentIndex = s.currentIndex + 1, message = null)
        }
    }

    fun previous() {
        val s = _state.value
        if (s.currentIndex > 0) {
            _state.value = s.copy(currentIndex = s.currentIndex - 1, message = null)
        }
    }

    private fun advance(message: String) {
        viewModelScope.launch {
            val remaining = changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50)
            _state.value = ApprovalUiState(
                pendingChangeSets = remaining,
                currentIndex = 0,
                message = message
            )
        }
    }
}
