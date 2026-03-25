package com.anytypeio.anytype.feature.pebble.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun InputHistoryScreen(
    viewModel: InputHistoryViewModel,
    onNavigateToChangeSet: (changeSetId: String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Input History") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoading && state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No inputs yet.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.id }) { entry ->
                        InputHistoryItem(
                            entry = entry,
                            onTapChangeSet = { csId -> onNavigateToChangeSet(csId) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun InputHistoryItem(
    entry: InputQueueEntry,
    onTapChangeSet: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.input.text.take(80) + if (entry.input.text.length > 80) "…" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = formatTimestamp(entry.enqueuedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            EntryStatusBadge(entry.status)
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.input.text,
                style = MaterialTheme.typography.bodySmall
            )
            entry.lastError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Error: $err",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            entry.resultChangeSetId?.let { csId ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onTapChangeSet(csId) }) {
                    Text("View change set →")
                }
            }
        }
    }
}

@Composable
private fun EntryStatusBadge(status: QueueEntryStatus) {
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
