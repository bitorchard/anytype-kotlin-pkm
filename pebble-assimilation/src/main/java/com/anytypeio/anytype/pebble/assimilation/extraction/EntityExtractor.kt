package com.anytypeio.anytype.pebble.assimilation.extraction

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClient
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.core.observability.EventStatus
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineStage
import com.anytypeio.anytype.pebble.core.taxonomy.PkmObjectType
import com.anytypeio.anytype.pebble.core.taxonomy.TaxonomyPromptGenerator
import timber.log.Timber
import java.util.Date
import javax.inject.Inject

private const val TAG = "Pebble:Assimilation"

/**
 * Calls the LLM to extract structured entities and relationships from a voice input,
 * then validates the output against the PKM taxonomy.
 *
 * Pipeline:
 *  1. Build system prompt (taxonomy + context window).
 *  2. Call [LlmClient.extractEntities].
 *  3. Validate extracted types against [PkmObjectType] — unknown types fall back to Note.
 *  4. Flag low-confidence entities.
 *  5. Return validated [ExtractionResult].
 */
class EntityExtractor @Inject constructor(
    private val llmClient: LlmClient,
    private val contextWindow: ContextWindow,
    private val eventStore: PipelineEventStore? = null
) : EntityExtractionService {
    /** Entities with confidence below this threshold are flagged (but not discarded). */
    private val lowConfidenceThreshold = 0.60f

    /**
     * Extract entities from [inputText].
     *
     * @param inputText Raw voice-input text.
     * @param traceId Propagation token for observability.
     * @param currentDate Injected for testability; defaults to now.
     * @throws [com.anytypeio.anytype.pebble.assimilation.llm.LlmException] on API failure.
     */
    override suspend fun extract(
        inputText: String,
        traceId: String,
        currentDate: Date
    ): ExtractionResult {
        val systemPrompt = buildSystemPrompt(currentDate)
        val startMs = System.currentTimeMillis()
        Timber.tag(TAG).d("[trace=$traceId] LLM_EXTRACTING | chars=${inputText.length}")
        eventStore?.record(
            PipelineEvent(
                traceId = traceId,
                stage = PipelineStage.LLM_EXTRACTING,
                status = EventStatus.IN_PROGRESS,
                message = "LLM extraction started",
                metadata = mapOf("model" to llmClient.modelName)
            )
        )

        val raw = try {
            llmClient.extractEntities(systemPrompt, inputText)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            val meta = mapOf(
                "errorClass" to e::class.simpleName.orEmpty(),
                "errorMessage" to (e.message?.take(200) ?: ""),
                "httpStatus" to extractHttpStatus(e)
            )
            Timber.tag(TAG).e("[trace=$traceId] ERROR at LLM_EXTRACTING | ${e.message}")
            eventStore?.record(
                PipelineEvent(
                    traceId = traceId,
                    stage = PipelineStage.ERROR,
                    status = EventStatus.FAILURE,
                    message = "LLM extraction failed: ${e.message?.take(100)}",
                    metadata = meta,
                    durationMs = durationMs
                )
            )
            throw e
        }

        val validated = validateAndNormalize(raw)
        val durationMs = System.currentTimeMillis() - startMs
        Timber.tag(TAG).d(
            "[trace=$traceId] LLM_EXTRACTED | entities=${validated.entities.size} " +
                "relationships=${validated.relationships.size} confidence=${validated.overallConfidence} " +
                "durationMs=$durationMs"
        )
        eventStore?.record(
            PipelineEvent(
                traceId = traceId,
                stage = PipelineStage.LLM_EXTRACTED,
                status = EventStatus.SUCCESS,
                message = "LLM extraction complete",
                metadata = mapOf(
                    "entityCount" to validated.entities.size.toString(),
                    "model" to llmClient.modelName
                ),
                durationMs = durationMs
            )
        )

        flagLowConfidence(validated)
        return validated
    }

    private fun extractHttpStatus(e: Exception): String =
        e.message?.let { msg ->
            Regex("\\b(4\\d{2}|5\\d{2})\\b").find(msg)?.value ?: ""
        } ?: ""

    // ── Prompt building ─────────────────────────────────────────────────────

    private fun buildSystemPrompt(currentDate: Date): String {
        val base = TaxonomyPromptGenerator.generateTaxonomyPrompt(currentDate)
        val recentContext = contextWindow.recentEntitySummary()
        return if (recentContext.isNotBlank()) {
            "$base\n\n$recentContext"
        } else {
            base
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private fun validateAndNormalize(result: ExtractionResult): ExtractionResult {
        val validatedEntities = result.entities.map { entity ->
            val knownType = PkmObjectType.byKey(entity.typeKey)
            if (knownType == null) {
                Timber.tag(TAG).w(
                    "[EntityExtractor] Unknown type key '${entity.typeKey}' for entity '${entity.name}' — falling back to Note"
                )
                entity.copy(typeKey = PkmObjectType.NoteType.uniqueKey)
            } else {
                entity
            }
        }
        // Filter relationships whose entities are all present
        val validRefs = validatedEntities.map { it.localRef }.toSet()
        val validatedRelationships = result.relationships.filter { rel ->
            rel.fromLocalRef in validRefs && rel.toLocalRef in validRefs
        }
        return result.copy(entities = validatedEntities, relationships = validatedRelationships)
    }

    private fun flagLowConfidence(result: ExtractionResult) {
        result.entities.forEach { entity ->
            if (entity.confidence < lowConfidenceThreshold) {
                Timber.tag(TAG).w(
                    "[EntityExtractor] Low-confidence entity: '${entity.name}' (${entity.confidence})"
                )
            }
        }
    }
}
