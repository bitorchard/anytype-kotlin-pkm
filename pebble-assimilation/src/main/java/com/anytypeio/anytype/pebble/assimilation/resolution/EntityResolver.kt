package com.anytypeio.anytype.pebble.assimilation.resolution

import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.core.PebbleConstants
import com.anytypeio.anytype.pebble.core.PebbleSpaceId
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Assimilation"

/** Minimum score for a candidate to appear in the disambiguation list. */
private const val MIN_AMBIGUOUS_SCORE = 0.50f

/** Score threshold above which multiple candidates trigger forced disambiguation. */
private const val FORCE_DISAMBIGUATION_SCORE = 0.70f

/**
 * Resolves each extracted entity to an existing AnyType object or decides to create a new one.
 *
 * Decision logic (thresholds from [PebbleConstants]):
 * - Score ≥ [AUTO_RESOLVE_THRESHOLD] (0.85) → auto-resolve to top candidate.
 * - Score in [CREATE_NEW_THRESHOLD, AUTO_RESOLVE_THRESHOLD) → suggest with disambiguation.
 * - Multiple candidates ≥ [FORCE_DISAMBIGUATION_SCORE] → force disambiguation.
 * - Score < [CREATE_NEW_THRESHOLD] (0.50) → create new object.
 */
class EntityResolver @Inject constructor(
    private val scoringEngine: ScoringEngine,
    private val entityCache: EntityCache
) {

    data class ResolutionResult(
        val resolved: List<ResolvedEntity>,
        /** Entities that require user input before a decision can be made. */
        val pendingDisambiguation: List<DisambiguationChoice>
    )

    /**
     * Resolve all entities in [extractionResult] within [space].
     *
     * Auto-resolvable entities are immediately decided; ambiguous ones are returned
     * in [ResolutionResult.pendingDisambiguation] for UI presentation.
     */
    suspend fun resolve(
        extractionResult: ExtractionResult,
        space: PebbleSpaceId
    ): ResolutionResult {
        val resolvedEntities = mutableListOf<ResolvedEntity>()
        val pendingChoices = mutableListOf<DisambiguationChoice>()

        // Collect IDs of already-resolved objects (for proximity scoring)
        val resolvedIds = mutableSetOf<String>()

        for (entity in extractionResult.entities) {
            val candidates = entityCache.getCandidates(space, entity.typeKey)
            val scored = scoringEngine.score(entity, candidates, resolvedIds)

            Timber.tag(TAG).d(
                "[EntityResolver] '${entity.name}' (${entity.typeKey}): " +
                    "${scored.size} candidates, top=${scored.firstOrNull()?.score}"
            )

            when {
                scored.isEmpty() || scored.first().score < PebbleConstants.CREATE_NEW_THRESHOLD -> {
                    // No good match — create new
                    resolvedEntities.add(
                        ResolvedEntity(entity, ResolutionDecision.CreateNew(entity.typeKey))
                    )
                }

                scored.first().score >= PebbleConstants.AUTO_RESOLVE_THRESHOLD &&
                    !hasMultipleHighScorers(scored, FORCE_DISAMBIGUATION_SCORE) -> {
                    // Clear winner — auto-resolve
                    val top = scored.first()
                    resolvedEntities.add(
                        ResolvedEntity(
                            entity,
                            ResolutionDecision.Resolved(top.object_.id, top.object_.typeKey)
                        )
                    )
                    resolvedIds.add(top.object_.id)
                }

                else -> {
                    // Ambiguous — queue for user disambiguation
                    val ambiguousCandidates = scored.filter { it.score >= MIN_AMBIGUOUS_SCORE }
                    pendingChoices.add(
                        DisambiguationChoice(
                            entity = entity,
                            candidates = ambiguousCandidates.take(5),
                            allowCreateNew = true
                        )
                    )
                }
            }
        }

        Timber.tag(TAG).d(
            "[EntityResolver] resolved=${resolvedEntities.size} " +
                "disambiguationNeeded=${pendingChoices.size}"
        )

        return ResolutionResult(resolvedEntities, pendingChoices)
    }

    /**
     * Apply user disambiguation choices and append them to existing resolved entities.
     */
    fun applyDisambiguationChoices(
        pendingChoices: List<DisambiguationChoice>,
        userDecisions: Map<String, ResolutionDecision>
    ): List<ResolvedEntity> {
        return pendingChoices.map { choice ->
            val decision = userDecisions[choice.entity.localRef]
                ?: ResolutionDecision.CreateNew(choice.entity.typeKey)
            ResolvedEntity(choice.entity, decision)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun hasMultipleHighScorers(
        scored: List<com.anytypeio.anytype.pebble.assimilation.model.ScoredCandidate>,
        threshold: Float
    ): Boolean = scored.count { it.score >= threshold } > 1
}
