package com.anytypeio.anytype.feature.pebble.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.model.QueueEntryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PebbleDashboardScreen(
    viewModel: PebbleDashboardViewModel,
    onNavigateToInputHistory: () -> Unit,
    onNavigateToApproval: () -> Unit,
    onNavigateToChangeLog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToManualInput: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Pebble") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WebhookStatusCard(
                running = state.webhookRunning,
                port = state.webhookPort,
                onDebugClick = onNavigateToDebug
            )

            if (state.pendingApprovalCount > 0) {
                PendingApprovalsCard(
                    count = state.pendingApprovalCount,
                    onReviewClick = onNavigateToApproval
                )
            }

            RecentInputsCard(
                inputs = state.recentInputs,
                onViewAllClick = onNavigateToInputHistory
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToChangeLog,
                    modifier = Modifier.weight(1f)
                ) { Text("Change Log") }
                OutlinedButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.weight(1f)
                ) { Text("Settings") }
            }

            OutlinedButton(
                onClick = onNavigateToManualInput,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test input (no Pebble ring required)") }
        }
    }
}

@Composable
private fun WebhookStatusCard(running: Boolean, port: Int, onDebugClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDebugClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) "Webhook running" else "Webhook stopped",
                    style = MaterialTheme.typography.titleMedium
                )
                if (running) {
                    Text(
                        text = "Port $port",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Badge(
                containerColor = if (running) Color(0xFF4CAF50) else Color(0xFFF44336)
            ) {
                Text(if (running) "ON" else "OFF")
            }
        }
    }
}

@Composable
private fun PendingApprovalsCard(count: Int, onReviewClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$count pending approval${if (count > 1) "s" else ""}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Tap Review to approve or reject",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = onReviewClick) { Text("Review") }
        }
    }
}

@Composable
private fun RecentInputsCard(inputs: List<InputQueueEntry>, onViewAllClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Inputs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onViewAllClick) { Text("All") }
            }
            Spacer(Modifier.height(8.dp))
            if (inputs.isEmpty()) {
                Text(
                    text = "No inputs yet. Send a voice note to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                inputs.forEach { entry ->
                    InputSummaryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun InputSummaryRow(entry: InputQueueEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.input.text.take(60) + if (entry.input.text.length > 60) "…" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = formatTimestamp(entry.enqueuedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusBadge(entry.status)
    }
}

@Composable
private fun StatusBadge(status: QueueEntryStatus) {
    val (text, color) = when (status) {
        QueueEntryStatus.PENDING -> "Queued" to Color(0xFFFF9800)
        QueueEntryStatus.PROCESSING -> "Processing" to Color(0xFF2196F3)
        QueueEntryStatus.PROCESSED -> "Done" to Color(0xFF4CAF50)
        QueueEntryStatus.FAILED -> "Failed" to Color(0xFFF44336)
        QueueEntryStatus.DEAD_LETTER -> "Dead" to Color(0xFF9E9E9E)
    }
    Badge(containerColor = color) { Text(text) }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("HH:mm, MMM d", Locale.getDefault()).format(Date(ms))
