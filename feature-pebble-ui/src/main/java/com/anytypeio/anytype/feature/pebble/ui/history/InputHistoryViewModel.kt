package com.anytypeio.anytype.feature.pebble.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InputHistoryUiState(
    val entries: List<InputQueueEntry> = emptyList(),
    val isLoading: Boolean = false
)

class InputHistoryViewModel @Inject constructor(
    private val inputQueue: InputQueue
) : ViewModel() {

    private val _state = MutableStateFlow(InputHistoryUiState(isLoading = true))
    val state: StateFlow<InputHistoryUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() { load() }

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val entries = inputQueue.getRecent(limit = 100)
            _state.value = InputHistoryUiState(entries = entries, isLoading = false)
        }
    }
}
