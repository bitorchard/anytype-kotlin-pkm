package com.anytypeio.anytype.pebble.assimilation.extraction

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.llm.LlmClient
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
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
    private val contextWindow: ContextWindow
) {
    /** Entities with confidence below this threshold are flagged (but not discarded). */
    private val lowConfidenceThreshold = 0.60f

    /**
     * Extract entities from [inputText].
     *
     * @param inputText Raw voice-input text.
     * @param currentDate Injected for testability; defaults to now.
     * @throws [com.anytypeio.anytype.pebble.assimilation.llm.LlmException] on API failure.
     */
    suspend fun extract(inputText: String, currentDate: Date = Date()): ExtractionResult {
        val systemPrompt = buildSystemPrompt(currentDate)
        Timber.tag(TAG).d("[EntityExtractor] extracting from input (${inputText.length} chars)")

        val raw = llmClient.extractEntities(systemPrompt, inputText)

        val validated = validateAndNormalize(raw)
        Timber.tag(TAG).d(
            "[EntityExtractor] extracted ${validated.entities.size} entities, " +
                "${validated.relationships.size} relationships; " +
                "confidence=${validated.overallConfidence}"
        )

        flagLowConfidence(validated)
        return validated
    }

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
