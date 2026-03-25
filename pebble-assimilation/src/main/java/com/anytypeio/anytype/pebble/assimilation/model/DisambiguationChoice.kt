package com.anytypeio.anytype.pebble.assimilation.model

import com.anytypeio.anytype.pebble.core.PebbleId

/**
 * Disambiguation data presented to the user when the entity resolver cannot confidently
 * choose a single candidate.
 *
 * The UI renders this as a radio-button list of [candidates] plus a "Create new" option.
 */
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
sealed class ResolutionDecision {
    /** Resolved to an existing AnyType object. */
    data class Resolved(val objectId: PebbleId, val typeKey: String) : ResolutionDecision()

    /** No suitable candidate found (or user chose "Create new"). */
    data class CreateNew(val typeKey: String) : ResolutionDecision()

    /** Entity was skipped / rejected by the user. */
    object Skipped : ResolutionDecision()
}

/**
 * Pairs an [ExtractedEntity] with its [ResolutionDecision] after the resolution phase.
 */
data class ResolvedEntity(
    val entity: ExtractedEntity,
    val decision: ResolutionDecision
)
