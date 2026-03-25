package com.anytypeio.anytype.pebble.assimilation.model

import kotlinx.serialization.Serializable

/**
 * A single entity extracted from a voice input by the LLM.
 *
 * @param localRef stable reference within this extraction (e.g. "entity-1"); used to wire
 *                 relationships before real AnyType IDs are known.
 * @param typeKey  PKM type key (e.g. "ot-pkm-event", "ot-human"); unknown types are mapped
 *                 to [com.anytypeio.anytype.pebble.core.taxonomy.PkmObjectType.NoteType].
 * @param name     Human-readable name (the "name" relation value).
 * @param attributes Raw relation key → value pairs extracted by the LLM.
 * @param confidence LLM confidence in this extraction (0.0–1.0).
 */
@Serializable
data class ExtractedEntity(
    val localRef: String,
    val typeKey: String,
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)
