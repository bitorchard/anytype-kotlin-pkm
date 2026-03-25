package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.extraction.EntityExtractionService
import com.anytypeio.anytype.pebble.assimilation.llm.LlmException
import com.anytypeio.anytype.pebble.assimilation.plan.PlanGenerator
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolutionService
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolver
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.AssimilationPipeline
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.core.RawVoiceInput
import com.anytypeio.anytype.pebble.core.PebbleSpaceId
import com.anytypeio.anytype.pebble.core.observability.EventStatus
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineStage
import com.anytypeio.anytype.pebble.assimilation.model.withDisambiguationChoices
import com.anytypeio.anytype.core_models.primitives.SpaceId
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Pebble:Assimilation"

/**
 * Full assimilation pipeline orchestrator.
 *
 * Implements [AssimilationPipeline] so it can be injected into
 * [com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor].
 *
 * Pipeline stages:
 * 1. [EntityExtractor] — LLM extraction + taxonomy validation.
 * 2. [EntityResolver]  — candidate search + scoring + auto-resolve/disambiguate.
 * 3. [PlanGenerator]   — build ordered [ChangeSet] of operations.
 * 4. [ChangeStore]     — persist the change set (status = PENDING).
 * 5. [ContextWindow]   — record resolved entities for subsequent inputs.
 */
@Singleton
class AssimilationEngine @Inject constructor(
    private val entityExtractor: EntityExtractionService,
    private val entityResolver: EntityResolutionService,
    private val planGenerator: PlanGenerator,
    private val changeStore: ChangeStore,
    private val contextWindow: ContextWindow,
    private val eventStore: PipelineEventStore? = null,
    /**
     * When set, changes with overall confidence >= [autoApproveThreshold] and no
     * disambiguation required are automatically applied without user review.
     */
    private val autoApproveThreshold: Float? = null,
    private val changeExecutor: ChangeExecutor? = null
) : AssimilationPipeline {

    override suspend fun process(input: RawVoiceInput, space: SpaceId): AssimilationResult {
        Timber.tag(TAG).d("[trace=${input.traceId}] AssimilationEngine.process start")

        // ── Stage 1: LLM extraction ─────────────────────────────────────────
        val extraction = try {
            entityExtractor.extract(input.text, traceId = input.traceId)
        } catch (e: LlmException.NetworkException) {
            Timber.tag(TAG).w("[trace=${input.traceId}] Offline — queuing for retry")
            return AssimilationResult.Offline
        } catch (e: LlmException) {
            Timber.tag(TAG).e(e, "[trace=${input.traceId}] LLM extraction failed")
            return AssimilationResult.Failure(
                error = "${e::class.simpleName}: ${e.message}",
                retryable = e is LlmException.RateLimitException || e is LlmException.TimeoutException
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[trace=${input.traceId}] Unexpected extraction error")
            return AssimilationResult.Failure(error = e.message ?: "Unknown error", retryable = true)
        }

        if (extraction.entities.isEmpty()) {
            Timber.tag(TAG).w("[trace=${input.traceId}] Extraction returned no entities — skipping")
            return AssimilationResult.Failure(
                error = "No entities extracted from input",
                retryable = false
            )
        }

        // ── Stage 2: Entity resolution ──────────────────────────────────────
        eventStore?.record(
            PipelineEvent(
                traceId = input.traceId,
                stage = PipelineStage.ENTITY_RESOLVING,
                status = EventStatus.IN_PROGRESS,
                message = "Resolving ${extraction.entities.size} entities",
                metadata = mapOf("entityCount" to extraction.entities.size.toString())
            )
        )
        val resolutionResult = try {
            entityResolver.resolve(extraction, space)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[trace=${input.traceId}] Entity resolution failed")
            eventStore?.record(
                PipelineEvent(
                    traceId = input.traceId,
                    stage = PipelineStage.ERROR,
                    status = EventStatus.FAILURE,
                    message = "Entity resolution failed: ${e.message?.take(100)}",
                    metadata = mapOf("errorClass" to e::class.simpleName.orEmpty())
                )
            )
            return AssimilationResult.Failure(error = e.message ?: "Resolution error", retryable = true)
        }
        eventStore?.record(
            PipelineEvent(
                traceId = input.traceId,
                stage = PipelineStage.ENTITY_RESOLVED,
                status = EventStatus.SUCCESS,
                message = "Entities resolved",
                metadata = mapOf(
                    "matched" to resolutionResult.resolved.count {
                        it.decision is com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision.Resolved
                    }.toString(),
                    "new" to resolutionResult.resolved.count {
                        it.decision is com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision.CreateNew
                    }.toString(),
                    "disambiguationNeeded" to resolutionResult.pendingDisambiguation.size.toString()
                )
            )
        )

        // ── Stage 3: Plan generation ────────────────────────────────────────
        val plan = try {
            planGenerator.generate(
                resolved = resolutionResult.resolved,
                extraction = extraction,
                pendingDisambiguation = resolutionResult.pendingDisambiguation,
                spaceId = space.id,
                sourceText = input.text,
                modelVersion = extraction.modelVersion,
                extractionConfidence = extraction.overallConfidence
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[trace=${input.traceId}] Plan generation failed")
            return AssimilationResult.Failure(error = e.message ?: "Plan error", retryable = false)
        }
        val creates = plan.operations.count { it.type == com.anytypeio.anytype.pebble.changecontrol.model.OperationType.CREATE_OBJECT }
        val updates = plan.operations.count { it.type == com.anytypeio.anytype.pebble.changecontrol.model.OperationType.SET_DETAILS }
        eventStore?.record(
            PipelineEvent(
                traceId = input.traceId,
                stage = PipelineStage.PLAN_GENERATED,
                status = EventStatus.SUCCESS,
                message = "Plan generated",
                metadata = mapOf(
                    "operationCount" to plan.operations.size.toString(),
                    "createCount" to creates.toString(),
                    "updateCount" to updates.toString()
                )
            )
        )

        // ── Stage 4: Persist change set ─────────────────────────────────────
        val changeSetId = UUID.randomUUID().toString()
        val changeSet = ChangeSet(
            id = changeSetId,
            inputId = input.id,
            traceId = input.traceId,
            status = ChangeSetStatus.PENDING,
            summary = buildSummary(plan),
            operations = plan.operations.map { op -> op.copy(changeSetId = changeSetId) },
            metadata = plan.metadata,
            createdAt = System.currentTimeMillis()
        ).withDisambiguationChoices(plan.disambiguationChoices)

        return try {
            val persistedId = changeStore.save(changeSet)

            // ── Stage 5: Update context window ──────────────────────────────
            contextWindow.record(resolutionResult.resolved)

            Timber.tag(TAG).d(
                "[trace=${input.traceId}] AssimilationEngine.process complete | " +
                    "changeSetId=$persistedId | " +
                    "ops=${plan.operations.size} | " +
                    "disambiguation=${plan.disambiguationChoices.size}"
            )

            // ── Stage 6: Auto-approve if confidence is above threshold ───────
            val autoApprove = autoApproveThreshold != null &&
                plan.metadata.extractionConfidence >= autoApproveThreshold &&
                plan.disambiguationChoices.isEmpty()

            if (autoApprove && changeExecutor != null) {
                val execResult = changeExecutor.execute(changeSet.copy(id = persistedId))
                if (execResult is ExecutionResult.Success || execResult is ExecutionResult.PartialFailure) {
                    eventStore?.record(
                        PipelineEvent(
                            traceId = input.traceId,
                            stage = PipelineStage.CHANGE_APPLIED,
                            status = EventStatus.SUCCESS,
                            message = "Auto-applied (confidence=${plan.metadata.extractionConfidence})",
                            metadata = mapOf("changeSetId" to persistedId)
                        )
                    )
                    return AssimilationResult.AutoApplied(
                        changeSetId = persistedId,
                        traceId = input.traceId,
                        summary = buildSummary(plan)
                    )
                }
            }

            eventStore?.record(
                PipelineEvent(
                    traceId = input.traceId,
                    stage = PipelineStage.APPROVAL_PENDING,
                    status = EventStatus.IN_PROGRESS,
                    message = "Awaiting approval",
                    metadata = mapOf("changeSetId" to persistedId)
                )
            )

            AssimilationResult.Success(changeSetId = persistedId, traceId = input.traceId)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[trace=${input.traceId}] Failed to persist change set")
            AssimilationResult.Failure(error = e.message ?: "Persistence error", retryable = true)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun buildSummary(plan: com.anytypeio.anytype.pebble.assimilation.model.AssimilationPlan): String {
        val creates = plan.operations.count {
            it.type == com.anytypeio.anytype.pebble.changecontrol.model.OperationType.CREATE_OBJECT
        }
        val updates = plan.operations.count {
            it.type == com.anytypeio.anytype.pebble.changecontrol.model.OperationType.SET_DETAILS
        }
        val links = plan.operations.count {
            it.type == com.anytypeio.anytype.pebble.changecontrol.model.OperationType.ADD_RELATION
        }
        return buildString {
            if (creates > 0) append("Create $creates object${if (creates != 1) "s" else ""}. ")
            if (updates > 0) append("Update $updates object${if (updates != 1) "s" else ""}. ")
            if (links > 0) append("Add $links link${if (links != 1) "s" else ""}.")
        }.trim()
    }
}
