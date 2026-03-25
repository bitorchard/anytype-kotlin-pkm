package com.anytypeio.anytype.feature.pebble.ui.changelog

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeSetDetailScreen(
    changeSetId: String,
    viewModel: ChangeSetDetailViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(changeSetId) { viewModel.load(changeSetId) }
    val cs by viewModel.changeSet.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change Set") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (cs == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                ChangeSetDetailContent(cs = cs!!)
            }
        }
    }
}

@Composable
private fun ChangeSetDetailContent(cs: ChangeSet) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(cs.summary.ifBlank { "Change set" }, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Source: ${cs.metadata.sourceText.take(80)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Status: ${cs.status}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Created: ${formatTs(cs.createdAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    cs.appliedAt?.let {
                        Text("Applied: ${formatTs(it)}", style = MaterialTheme.typography.bodySmall)
                    }
                    cs.rolledBackAt?.let {
                        Text("Rolled back: ${formatTs(it)}", style = MaterialTheme.typography.bodySmall)
                    }
                    cs.errorMessage?.let {
                        Text("Error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Operations (${cs.operations.size})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
        }
        items(cs.operations.sortedBy { it.ordinal }) { op ->
            OperationDetailRow(op)
            HorizontalDivider()
        }
    }
}

@Composable
private fun OperationDetailRow(op: ChangeOperation) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${op.ordinal + 1} ${op.type.name.replace('_', ' ')}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            val (label, color) = when (op.status) {
                OperationStatus.PENDING -> "Pending" to Color(0xFFFF9800)
                OperationStatus.APPLIED -> "Applied" to Color(0xFF4CAF50)
                OperationStatus.FAILED -> "Failed" to Color(0xFFF44336)
                OperationStatus.ROLLED_BACK -> "Rolled back" to Color(0xFF607D8B)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
        if (op.beforeState != null || op.afterState != null) {
            Spacer(Modifier.height(4.dp))
            if (op.beforeState != null) {
                Text(
                    "Before: ${op.beforeState.entries.take(3).joinToString { "${it.key}=${it.value}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (op.afterState != null) {
                Text(
                    "After: ${op.afterState.entries.take(3).joinToString { "${it.key}=${it.value}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(ms))
