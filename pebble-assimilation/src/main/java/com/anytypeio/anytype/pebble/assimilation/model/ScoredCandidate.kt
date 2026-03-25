package com.anytypeio.anytype.pebble.assimilation.model

import com.anytypeio.anytype.pebble.core.PebbleObject
import kotlinx.serialization.Serializable

/**
 * An existing AnyType object considered as a resolution candidate for an [ExtractedEntity],
 * together with its composite score and per-signal breakdown.
 */
@Serializable
data class ScoredCandidate(
    /** The existing AnyType object. */
    val object_: PebbleObject,
    /** Composite score (0.0–1.0); weighted sum of all signals. */
    val score: Float,
    /** Signal breakdown for debugging/UI. */
    val signals: SignalBreakdown
)

@Serializable
data class SignalBreakdown(
    val nameSimilarity: Float = 0f,
    val typeMatch: Float = 0f,
    val proximityScore: Float = 0f,
    val recencyScore: Float = 0f,
    val frequencyScore: Float = 0f,
    val attributeScore: Float = 0f
)
