package com.anytypeio.anytype.pebble.assimilation.model

import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Disambiguation data presented to the user when the entity resolver cannot confidently
 * choose a single candidate.
 *
 * The UI renders this as a radio-button list of [candidates] plus a "Create new" option.
 */
@Serializable
data class DisambiguationChoice(
    /** The extracted entity that needs disambiguation. */
    val entity: ExtractedEntity,
    /** Candidates ranked by score descending; each has score ≥ [minAmbiguousScore]. */
    val candidates: List<ScoredCandidate>,
    /** Whether "Create new" is shown as an option. */
    val allowCreateNew: Boolean = true
)

/**
 * The user's resolution decision for a single extracted entity.
 */
@Serializable
sealed class ResolutionDecision {
    /** Resolved to an existing AnyType object. */
    @Serializable
    @SerialName("resolved")
    data class Resolved(val objectId: PebbleId, val typeKey: String) : ResolutionDecision()

    /** No suitable candidate found (or user chose "Create new"). */
    @Serializable
    @SerialName("create_new")
    data class CreateNew(val typeKey: String) : ResolutionDecision()

    /** Entity was skipped / rejected by the user. */
    @Serializable
    @SerialName("skipped")
    object Skipped : ResolutionDecision()
}

/**
 * Pairs an [ExtractedEntity] with its [ResolutionDecision] after the resolution phase.
 */
@Serializable
data class ResolvedEntity(
    val entity: ExtractedEntity,
    val decision: ResolutionDecision
)
