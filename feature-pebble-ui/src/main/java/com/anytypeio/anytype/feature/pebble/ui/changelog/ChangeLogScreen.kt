package com.anytypeio.anytype.feature.pebble.ui.changelog

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeLogScreen(
    viewModel: ChangeLogViewModel,
    onNavigateToChangeSetDetail: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmRollbackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it) }
        viewModel.dismissMessage()
    }

    if (confirmRollbackId != null) {
        AlertDialog(
            onDismissRequest = { confirmRollbackId = null },
            title = { Text("Rollback changes?") },
            text = { Text("This will undo the changes from this change set using compensating operations. Conflicts will be skipped.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRollbackId?.let { viewModel.rollback(it) }
                    confirmRollbackId = null
                }) { Text("Rollback") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRollbackId = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change Log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StatusFilterRow(
                selected = state.filterStatus,
                onSelect = viewModel::setFilter
            )
            HorizontalDivider()
            PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.isLoading && state.changeSets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.changeSets.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No change sets yet.")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.changeSets, key = { it.id }) { cs ->
                            ChangeSetRow(
                                cs = cs,
                                onTap = { onNavigateToChangeSetDetail(cs.id) },
                                onRollback = { confirmRollbackId = cs.id }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private val filterOptions = listOf(
    null,
    ChangeSetStatus.PENDING,
    ChangeSetStatus.APPLIED,
    ChangeSetStatus.ROLLED_BACK,
    ChangeSetStatus.APPLY_FAILED
)

@Composable
private fun StatusFilterRow(selected: ChangeSetStatus?, onSelect: (ChangeSetStatus?) -> Unit) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(filterOptions) { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All") }
            )
        }
    }
}

@Composable
private fun ChangeSetRow(cs: ChangeSet, onTap: () -> Unit, onRollback: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cs.summary.ifBlank { cs.metadata.sourceText }.take(70),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatTimestamp(cs.createdAt) + " · ${cs.operations.size} ops",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            ChangeSetStatusBadge(cs.status)
            if (cs.status == ChangeSetStatus.APPLIED) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRollback,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Rollback", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ChangeSetStatusBadge(status: ChangeSetStatus) {
    val (text, color) = when (status) {
        ChangeSetStatus.PENDING -> "Pending" to Color(0xFFFF9800)
        ChangeSetStatus.APPROVED -> "Approved" to Color(0xFF2196F3)
        ChangeSetStatus.APPLYING -> "Applying" to Color(0xFF2196F3)
        ChangeSetStatus.APPLIED -> "Applied" to Color(0xFF4CAF50)
        ChangeSetStatus.APPLY_FAILED -> "Failed" to Color(0xFFF44336)
        ChangeSetStatus.REJECTED -> "Rejected" to Color(0xFF9E9E9E)
        ChangeSetStatus.ROLLING_BACK -> "Rolling back" to Color(0xFFFF9800)
        ChangeSetStatus.ROLLED_BACK -> "Rolled back" to Color(0xFF607D8B)
        ChangeSetStatus.PARTIALLY_ROLLED_BACK -> "Partial rollback" to Color(0xFFFF9800)
    }
    Badge(containerColor = color) { Text(text) }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
