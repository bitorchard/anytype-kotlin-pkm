package com.anytypeio.anytype.feature.pebble.ui.approval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.disambiguationChoices
import com.anytypeio.anytype.pebble.assimilation.model.withDisambiguationChoices
import com.anytypeio.anytype.pebble.assimilation.resolution.DisambiguationResolver
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackStore
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The step the user is currently on within a single approval review session.
 *
 * When a [ChangeSet] has pending disambiguation choices the user must resolve them before
 * they can see (and approve) the final operation list.
 */
sealed class ApprovalStep {
    /** One or more entities need user input before the plan can execute. */
    data class Disambiguating(val choices: List<DisambiguationChoice>) : ApprovalStep()
    /** All entities are resolved; user reviews and approves/rejects the operation list. */
    object ReviewingPlan : ApprovalStep()
}

data class ApprovalUiState(
    val pendingChangeSets: List<ChangeSet> = emptyList(),
    val currentIndex: Int = 0,
    val step: ApprovalStep = ApprovalStep.ReviewingPlan,
    val isLoading: Boolean = false,
    val message: String? = null
) {
    val current: ChangeSet? get() = pendingChangeSets.getOrNull(currentIndex)
    val hasMore: Boolean get() = currentIndex < pendingChangeSets.lastIndex
}

class ApprovalViewModel @Inject constructor(
    private val changeStore: ChangeStore,
    private val changeExecutor: ChangeExecutor,
    private val disambiguationResolver: DisambiguationResolver,
    private val feedbackStore: ResolutionFeedbackStore
) : ViewModel() {

    private val _state = MutableStateFlow(ApprovalUiState(isLoading = true))
    val state: StateFlow<ApprovalUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val pending = changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50)
            val current = pending.firstOrNull()
            _state.value = ApprovalUiState(
                pendingChangeSets = pending,
                currentIndex = 0,
                step = current.initialStep(),
                isLoading = false
            )
        }
    }

    /**
     * Called when the user has resolved all disambiguation choices.
     * Records feedback for [ResolutionFeedbackStore], applies choices via [DisambiguationResolver],
     * saves the updated [ChangeSet], and transitions to [ApprovalStep.ReviewingPlan].
     */
    fun resolveAndProceed(userChoices: Map<String, ResolutionDecision>) {
        val cs = _state.value.current ?: return
        viewModelScope.launch {
            // Record accepted resolutions to improve future scoring
            cs.disambiguationChoices().forEach { choice ->
                val decision = userChoices[choice.entity.localRef]
                if (decision is ResolutionDecision.Resolved) {
                    feedbackStore.recordResolution(
                        entityName = choice.entity.name,
                        entityTypeKey = choice.entity.typeKey,
                        resolvedObjectId = decision.objectId,
                        wasCorrect = true
                    )
                }
            }

            val updated = disambiguationResolver.resolve(cs, userChoices)
            changeStore.save(updated)

            val updatedList = _state.value.pendingChangeSets.map { if (it.id == cs.id) updated else it }
            _state.value = _state.value.copy(
                pendingChangeSets = updatedList,
                step = ApprovalStep.ReviewingPlan,
                message = null
            )
        }
    }

    fun approve() {
        val cs = _state.value.current ?: return
        viewModelScope.launch {
            changeStore.updateStatus(cs.id, ChangeSetStatus.APPROVED)
            val result = changeExecutor.execute(cs)
            val msg = when (result) {
                is ExecutionResult.Success -> "Changes applied"
                is ExecutionResult.PartialFailure -> "Apply failed: ${result.error.message}"
            }
            advance(msg)
        }
    }

    fun reject() {
        val cs = _state.value.current ?: return
        viewModelScope.launch {
            changeStore.updateStatus(cs.id, ChangeSetStatus.REJECTED)
            advance("Rejected")
        }
    }

    fun next() {
        val s = _state.value
        if (s.hasMore) {
            val nextIndex = s.currentIndex + 1
            val nextCs = s.pendingChangeSets.getOrNull(nextIndex)
            _state.value = s.copy(
                currentIndex = nextIndex,
                step = nextCs.initialStep(),
                message = null
            )
        }
    }

    fun previous() {
        val s = _state.value
        if (s.currentIndex > 0) {
            val prevIndex = s.currentIndex - 1
            val prevCs = s.pendingChangeSets.getOrNull(prevIndex)
            _state.value = s.copy(
                currentIndex = prevIndex,
                step = prevCs.initialStep(),
                message = null
            )
        }
    }

    private fun advance(message: String) {
        viewModelScope.launch {
            val remaining = changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50)
            val first = remaining.firstOrNull()
            _state.value = ApprovalUiState(
                pendingChangeSets = remaining,
                currentIndex = 0,
                step = first.initialStep(),
                message = message
            )
        }
    }

    private fun ChangeSet?.initialStep(): ApprovalStep {
        val choices = this?.disambiguationChoices() ?: emptyList()
        return if (choices.isNotEmpty()) ApprovalStep.Disambiguating(choices)
        else ApprovalStep.ReviewingPlan
    }
}
