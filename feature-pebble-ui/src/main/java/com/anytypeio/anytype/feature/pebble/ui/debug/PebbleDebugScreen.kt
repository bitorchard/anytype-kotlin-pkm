package com.anytypeio.anytype.feature.pebble.ui.debug

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anytypeio.anytype.pebble.core.observability.EventStatus
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val COLOR_GREEN = Color(0xFF4CAF50)
private val COLOR_YELLOW = Color(0xFFFF9800)
private val COLOR_RED = Color(0xFFF44336)

private fun HealthStatus.color(): Color = when (this) {
    HealthStatus.GREEN -> COLOR_GREEN
    HealthStatus.YELLOW -> COLOR_YELLOW
    HealthStatus.RED -> COLOR_RED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PebbleDebugScreen(
    viewModel: PebbleDebugViewModel,
    initialTraceId: String = "",
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.exportJson) {
        val json = state.exportJson ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "Pebble Debug Log")
        }
        context.startActivity(Intent.createChooser(intent, "Share Debug Log"))
        viewModel.clearExport()
    }

    LaunchedEffect(initialTraceId) {
        if (initialTraceId.isNotEmpty()) viewModel.toggleTrace(initialTraceId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Traces") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SystemHealthBar(health = state.health)
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = viewModel::exportLogs) { Text("Share Debug Log") }
            }
            if (state.traces.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No traces yet. Send a voice note to get started.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.traces, key = { it.traceId }) { row ->
                        TraceRowItem(
                            row = row,
                            onToggle = { viewModel.toggleTrace(row.traceId) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemHealthBar(health: SystemHealth) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HealthIndicator("Webhook", health.webhook)
        HealthIndicator("LLM", health.llm)
        HealthIndicator("Queue", health.queue)
        HealthIndicator("Errors", health.lastError)
    }
}

@Composable
private fun HealthIndicator(label: String, status: HealthStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(status.color(), CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TraceRowItem(row: TraceRow, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(row.overallStatus.color(), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.inputPreview.take(60),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${row.relativeTime} · ${row.stageSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (row.expanded) {
            TraceTimeline(events = row.events)
        }
    }
}

@Composable
private fun TraceTimeline(events: List<PipelineEvent>) {
    val firstTs = events.firstOrNull()?.timestampMs ?: 0L
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            events.forEach { event ->
                TimelineEvent(event = event, baseTs = firstTs)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TimelineEvent(event: PipelineEvent, baseTs: Long) {
    val icon = when (event.status) {
        EventStatus.SUCCESS -> "✓"
        EventStatus.FAILURE -> "✗"
        EventStatus.IN_PROGRESS -> "…"
        EventStatus.SKIPPED -> "–"
    }
    val color = when (event.status) {
        EventStatus.SUCCESS -> COLOR_GREEN
        EventStatus.FAILURE -> COLOR_RED
        EventStatus.IN_PROGRESS -> COLOR_YELLOW
        EventStatus.SKIPPED -> Color.Gray
    }
    val offset = event.timestampMs - baseTs
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(icon, color = color, modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    text = event.stage.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(event.timestampMs) + "  +${offset}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.status == EventStatus.FAILURE) {
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = COLOR_RED
                )
            }
            if (event.metadata.isNotEmpty()) {
                Text(
                    text = event.metadata.entries.take(3).joinToString("  ") { "${it.key}: ${it.value}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))
