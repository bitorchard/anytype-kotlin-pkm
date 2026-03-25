package com.anytypeio.anytype.feature.pebble.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import com.anytypeio.anytype.pebble.webhook.server.WebhookServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val webhookRunning: Boolean = false,
    val webhookPort: Int = 8391,
    val pendingApprovalCount: Int = 0,
    val recentInputs: List<InputQueueEntry> = emptyList()
)

class PebbleDashboardViewModel @Inject constructor(
    private val webhookServer: WebhookServer,
    private val changeStore: ChangeStore,
    private val inputQueue: InputQueue
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5_000L)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val pending = changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 100)
            val recent = inputQueue.getRecent(limit = 5)
            _state.value = DashboardUiState(
                webhookRunning = webhookServer.isRunning,
                webhookPort = webhookServer.port ?: 8391,
                pendingApprovalCount = pending.size,
                recentInputs = recent
            )
        }
    }
}
