package com.anytypeio.anytype.pebble.assimilation.model

import kotlinx.serialization.Serializable

/**
 * A directed relationship between two extracted entities.
 *
 * @param fromLocalRef [ExtractedEntity.localRef] of the source entity.
 * @param toLocalRef   [ExtractedEntity.localRef] of the target entity.
 * @param relationKey  PKM relation key (e.g. "pkm-attendees", "pkm-locatedAt").
 * @param confidence   LLM confidence in this relationship (0.0–1.0).
 */
@Serializable
data class ExtractedRelationship(
    val fromLocalRef: String,
    val toLocalRef: String,
    val relationKey: String,
    val confidence: Float = 1.0f
)
