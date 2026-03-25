package com.anytypeio.anytype.feature.pebble.ui.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeRollback
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ConflictResolution
import com.anytypeio.anytype.pebble.changecontrol.model.RollbackResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangeLogUiState(
    val changeSets: List<ChangeSet> = emptyList(),
    val filterStatus: ChangeSetStatus? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val rollbackConflictChangeSetId: String? = null
)

class ChangeLogViewModel @Inject constructor(
    private val changeStore: ChangeStore,
    private val changeRollback: ChangeRollback
) : ViewModel() {

    private val _state = MutableStateFlow(ChangeLogUiState(isLoading = true))
    val state: StateFlow<ChangeLogUiState> = _state.asStateFlow()

    init { load() }

    fun setFilter(status: ChangeSetStatus?) {
        _state.value = _state.value.copy(filterStatus = status)
        load()
    }

    fun refresh() { load() }

    fun rollback(changeSetId: String) {
        viewModelScope.launch {
            val cs = changeStore.getChangeSet(changeSetId) ?: return@launch
            val result = changeRollback.rollback(cs, conflictResolution = ConflictResolution.SKIP)
            val msg = when (result) {
                is RollbackResult.FullRollback -> "Rolled back successfully"
                is RollbackResult.PartialRollback ->
                    "Partial rollback — ${result.conflicts.size} conflict(s) skipped"
                is RollbackResult.Aborted ->
                    "Rollback aborted: conflict on object ${result.conflict.objectId}"
            }
            val hasConflicts = result is RollbackResult.PartialRollback || result is RollbackResult.Aborted
            _state.value = _state.value.copy(
                message = msg,
                rollbackConflictChangeSetId = if (hasConflicts) changeSetId else null
            )
            load()
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, rollbackConflictChangeSetId = null)
    }

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val results = changeStore.getChangeSets(
                status = _state.value.filterStatus,
                limit = 100
            )
            _state.value = _state.value.copy(changeSets = results, isLoading = false)
        }
    }
}
