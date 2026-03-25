package com.anytypeio.anytype.feature.pebble.ui.approval

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(
    viewModel: ApprovalViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val total = state.pendingChangeSets.size
                    Text(
                        if (total > 0) "Approval (${state.currentIndex + 1}/$total)"
                        else "Approval"
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.current == null -> {
                    Text(
                        text = "No pending approvals.",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.step is ApprovalStep.Disambiguating -> {
                    DisambiguationStepContent(
                        choices = (state.step as ApprovalStep.Disambiguating).choices,
                        onChoicesConfirmed = viewModel::resolveAndProceed
                    )
                }
                else -> ApprovalContent(
                    changeSet = state.current!!,
                    hasNext = state.hasMore,
                    hasPrevious = state.currentIndex > 0,
                    onApprove = viewModel::approve,
                    onReject = viewModel::reject,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous
                )
            }
        }
    }
}

@Composable
private fun DisambiguationStepContent(
    choices: List<DisambiguationChoice>,
    onChoicesConfirmed: (Map<String, ResolutionDecision>) -> Unit
) {
    val selections = remember(choices) { mutableStateMapOf<String, ResolutionDecision>() }
    val allPicked = choices.all { it.entity.localRef in selections }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Resolve ambiguous entities", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Select the best match for each entity, or choose \"Create new\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(choices.size) { idx ->
                val choice = choices[idx]
                EntityDisambiguationCard(
                    choice = choice,
                    selected = selections[choice.entity.localRef],
                    onSelect = { decision -> selections[choice.entity.localRef] = decision }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onChoicesConfirmed(selections.toMap()) },
            enabled = allPicked,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Apply choices") }
    }
}

@Composable
private fun EntityDisambiguationCard(
    choice: DisambiguationChoice,
    selected: ResolutionDecision?,
    onSelect: (ResolutionDecision) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "\"${choice.entity.name}\" (${choice.entity.typeKey})",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(8.dp))

            choice.candidates.forEach { candidate ->
                val decision = ResolutionDecision.Resolved(
                    objectId = candidate.object_.id,
                    typeKey = candidate.object_.typeKey
                )
                CandidateRow(
                    label = candidate.object_.name,
                    sublabel = candidate.object_.typeKey,
                    score = candidate.score,
                    isSelected = selected == decision,
                    onSelect = { onSelect(decision) }
                )
            }

            if (choice.allowCreateNew) {
                val createNew = ResolutionDecision.CreateNew(choice.entity.typeKey)
                CandidateRow(
                    label = "Create new \"${choice.entity.name}\"",
                    sublabel = "",
                    score = null,
                    isSelected = selected == createNew,
                    onSelect = { onSelect(createNew) }
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    label: String,
    sublabel: String,
    score: Float?,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (sublabel.isNotBlank()) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (score != null) ScoreDots(score)
    }
}

/** Renders score as 5 filled/empty dots (e.g. 0.80 → ●●●●○). */
@Composable
private fun ScoreDots(score: Float) {
    val filled = (score * 5).roundToInt().coerceIn(0, 5)
    Row {
        repeat(5) { i ->
            Text(
                text = if (i < filled) "●" else "○",
                style = MaterialTheme.typography.labelMedium,
                color = if (i < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun ApprovalContent(
    changeSet: ChangeSet,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    var showDetails by remember(changeSet.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = changeSet.metadata.sourceText, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OperationCountSummary(changeSet.operations)
                Spacer(Modifier.height(4.dp))
                ConfidenceIndicator(changeSet.metadata.extractionConfidence)
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details ▲" else "Review details ▼")
        }

        if (showDetails) {
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(changeSet.operations) { _, op ->
                    OperationRow(op)
                    HorizontalDivider()
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onReject,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Reject") }
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Approve") }
        }

        if (hasPrevious || hasNext) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPrevious, enabled = hasPrevious) { Text("← Prev") }
                TextButton(onClick = onNext, enabled = hasNext) { Text("Next →") }
            }
        }
    }
}

@Composable
private fun OperationCountSummary(operations: List<ChangeOperation>) {
    val creates = operations.count { it.type == OperationType.CREATE_OBJECT }
    val updates = operations.count { it.type == OperationType.SET_DETAILS }
    val links = operations.count { it.type == OperationType.ADD_RELATION }
    val deletes = operations.count { it.type == OperationType.DELETE_OBJECT }
    val parts = buildList {
        if (creates > 0) add("create $creates object${if (creates > 1) "s" else ""}")
        if (updates > 0) add("update $updates")
        if (links > 0) add("add $links link${if (links > 1) "s" else ""}")
        if (deletes > 0) add("delete $deletes")
    }
    Text(
        text = "Will " + (parts.joinToString(" and ").ifEmpty { "no operations" }),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val (label, color) = when {
        confidence >= 0.85f -> "High confidence" to MaterialTheme.colorScheme.primary
        confidence >= 0.50f -> "Medium confidence" to MaterialTheme.colorScheme.tertiary
        else -> "Low confidence — review carefully" to MaterialTheme.colorScheme.error
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun OperationRow(op: ChangeOperation) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = op.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "#${op.ordinal + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
