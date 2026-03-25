package com.anytypeio.anytype.feature.pebble.ui.manual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ManualInputState {
    object Idle : ManualInputState()
    object Submitting : ManualInputState()
    data class Submitted(val inputId: String) : ManualInputState()
    data class Error(val message: String) : ManualInputState()
}

class ManualInputViewModel @Inject constructor(
    private val inputQueue: InputQueue
) : ViewModel() {

    private val _state = MutableStateFlow<ManualInputState>(ManualInputState.Idle)
    val state: StateFlow<ManualInputState> = _state.asStateFlow()

    fun submit(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.value = ManualInputState.Submitting
            try {
                val input = RawInput(text = text.trim(), source = "manual")
                val id = inputQueue.enqueue(input)
                _state.value = ManualInputState.Submitted(id)
            } catch (e: Exception) {
                _state.value = ManualInputState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() {
        _state.value = ManualInputState.Idle
    }
}
