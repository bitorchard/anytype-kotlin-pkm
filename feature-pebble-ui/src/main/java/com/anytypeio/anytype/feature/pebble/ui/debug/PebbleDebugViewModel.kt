package com.anytypeio.anytype.feature.pebble.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.observability.EventStatus
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineStage
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import com.anytypeio.anytype.pebble.webhook.server.WebhookServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class HealthStatus { GREEN, YELLOW, RED }

data class SystemHealth(
    val webhook: HealthStatus = HealthStatus.RED,
    val llm: HealthStatus = HealthStatus.YELLOW,
    val queue: HealthStatus = HealthStatus.GREEN,
    val lastError: HealthStatus = HealthStatus.GREEN
)

data class TraceRow(
    val traceId: String,
    val inputPreview: String,
    val relativeTime: String,
    val overallStatus: HealthStatus,
    val stageSummary: String,
    val events: List<PipelineEvent> = emptyList(),
    val expanded: Boolean = false
)

data class DebugUiState(
    val health: SystemHealth = SystemHealth(),
    val traces: List<TraceRow> = emptyList(),
    val isLoading: Boolean = false,
    val exportJson: String? = null
)

class PebbleDebugViewModel @Inject constructor(
    private val eventStore: PipelineEventStore,
    private val webhookServer: WebhookServer,
    private val inputQueue: InputQueue
) : ViewModel() {

    private val _expandedTraces = MutableStateFlow<Set<String>>(emptySet())
    private val _exportJson = MutableStateFlow<String?>(null)

    val state: StateFlow<DebugUiState> = combine(
        eventStore.getRecentTraces(50),
        _expandedTraces
    ) { traceIds, expanded -> traceIds to expanded }
        .flatMapLatest { (traceIds, expanded) ->
            if (traceIds.isEmpty()) {
                flowOf(DebugUiState())
            } else {
                combine(
                    traceIds.map { id -> eventStore.getEventsForTrace(id).map { id to it } }
                ) { pairs -> pairs.toList() }
                    .map { pairs ->
                        val rows = pairs.map { (traceId, events) ->
                            buildTraceRow(traceId, events, expanded.contains(traceId))
                        }
                        DebugUiState(
                            health = buildHealth(rows),
                            traces = rows,
                            isLoading = false
                        )
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DebugUiState(isLoading = true))

    fun toggleTrace(traceId: String) {
        _expandedTraces.value = _expandedTraces.value.let { current ->
            if (current.contains(traceId)) current - traceId else current + traceId
        }
    }

    fun exportLogs() {
        viewModelScope.launch {
            val events = eventStore.getFailures(sinceMs = 0)
                .plus(
                    state.value.traces
                        .flatMap { it.events }
                        .take(100)
                )
                .distinctBy { it.id }
                .sortedBy { it.timestampMs }
                .take(100)
                .map { e ->
                    mapOf(
                        "traceId" to e.traceId,
                        "stage" to e.stage.name,
                        "status" to e.status.name,
                        "message" to e.message,
                        "timestamp" to formatTimestamp(e.timestampMs),
                        "durationMs" to (e.durationMs?.toString() ?: ""),
                        "metadata" to e.metadata.toString()
                    )
                }
            _exportJson.value = Json.encodeToString(events)
        }
    }

    fun clearExport() { _exportJson.value = null }

    private fun buildTraceRow(
        traceId: String,
        events: List<PipelineEvent>,
        expanded: Boolean
    ): TraceRow {
        val firstEvent = events.minByOrNull { it.timestampMs }
        val lastEvent = events.maxByOrNull { it.timestampMs }
        val hasError = events.any { it.status == EventStatus.FAILURE }
        val isApplied = events.any { it.stage == PipelineStage.CHANGE_APPLIED && it.status == EventStatus.SUCCESS }
        val isPending = events.any { it.stage == PipelineStage.APPROVAL_PENDING && it.status == EventStatus.IN_PROGRESS }

        val overallStatus = when {
            hasError -> HealthStatus.RED
            isPending -> HealthStatus.YELLOW
            isApplied -> HealthStatus.GREEN
            else -> HealthStatus.YELLOW
        }

        val lastStage = lastEvent?.stage?.name?.replace('_', ' ')?.lowercase() ?: "unknown"
        val opCount = events.firstOrNull { it.stage == PipelineStage.PLAN_GENERATED }
            ?.metadata?.get("operationCount") ?: "?"

        val stageSummary = when {
            isApplied -> "Applied — $opCount ops"
            hasError -> "Error at $lastStage"
            isPending -> "Awaiting approval"
            else -> lastStage.replaceFirstChar { it.uppercase() }
        }

        val inputPreview = events.firstOrNull { it.stage == PipelineStage.INPUT_RECEIVED }
            ?.metadata?.get("preview") ?: traceId.take(8)

        return TraceRow(
            traceId = traceId,
            inputPreview = inputPreview,
            relativeTime = relativeTime(firstEvent?.timestampMs ?: System.currentTimeMillis()),
            overallStatus = overallStatus,
            stageSummary = stageSummary,
            events = events.sortedBy { it.timestampMs },
            expanded = expanded
        )
    }

    private fun buildHealth(rows: List<TraceRow>): SystemHealth {
        val tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L
        val recentError = rows.any { row ->
            row.overallStatus == HealthStatus.RED &&
                    row.events.any { it.timestampMs > tenMinutesAgo }
        }
        val webhookStatus = if (webhookServer.isRunning) HealthStatus.GREEN else HealthStatus.RED
        val llmOk = rows.any { row ->
            row.events.any { it.stage == PipelineStage.LLM_EXTRACTED && it.status == EventStatus.SUCCESS }
        }
        val llmFailed = rows.any { row ->
            row.events.any { it.stage == PipelineStage.LLM_EXTRACTING && it.status == EventStatus.FAILURE }
        }
        return SystemHealth(
            webhook = webhookStatus,
            llm = when { llmOk -> HealthStatus.GREEN; llmFailed -> HealthStatus.RED; else -> HealthStatus.YELLOW },
            queue = HealthStatus.GREEN,
            lastError = if (recentError) HealthStatus.RED else HealthStatus.GREEN
        )
    }

    private fun relativeTime(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))
}
