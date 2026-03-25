package com.anytypeio.anytype.pebble.assimilation.model

import kotlinx.serialization.Serializable

/**
 * The structured output from an LLM extraction call.
 *
 * @param entities      All entities identified in the input text.
 * @param relationships Directed relationships between entities.
 * @param overallConfidence Aggregate confidence across all extractions.
 * @param rawResponse   The raw JSON string returned by the LLM (for debugging).
 * @param modelVersion  The model identifier used (e.g. "claude-sonnet-4-5").
 */
@Serializable
data class ExtractionResult(
    val entities: List<ExtractedEntity>,
    val relationships: List<ExtractedRelationship>,
    val overallConfidence: Float = 1.0f,
    val rawResponse: String = "",
    val modelVersion: String = ""
)
