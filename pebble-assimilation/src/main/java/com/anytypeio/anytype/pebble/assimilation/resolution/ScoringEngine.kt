package com.anytypeio.anytype.pebble.assimilation.resolution

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ScoredCandidate
import com.anytypeio.anytype.pebble.assimilation.model.SignalBreakdown
import com.anytypeio.anytype.pebble.core.PebbleObject
import kotlin.math.exp

/**
 * Computes a composite match score for a candidate AnyType object against an extracted entity.
 *
 * Signals and weights (tuned so exact name+type+recency = 0.85, the auto-resolve threshold):
 *
 * | Signal              | Weight |
 * |---------------------|--------|
 * | Name similarity     | 0.51   |
 * | Type match          | 0.15   |
 * | Recency             | 0.20   |
 * | Relationship prox.  | 0.09   |
 * | Frequency           | 0.03   |
 * | Context attributes  | 0.02   |
 */
class ScoringEngine(
    private val contextWindow: ContextWindow,
    private val feedbackStore: ResolutionFeedbackStore,
    private val weights: Weights = Weights()
) {

    /**
     * Signal weights (must sum to 1.0).
     *
     * Rationale: name similarity is the strongest identity signal (0.50) and should
     * alone with type + recency exceed the auto-resolve threshold of 0.85:
     *   exact name (1.0) + exact type (1.0) + recent (1.0) = 0.51+0.15+0.20 = 0.86
     * Proximity and frequency are secondary signals; they boost confidence but are
     * insufficient on their own to trigger auto-resolution.
     */
    data class Weights(
        val nameSimilarity: Float = 0.51f,
        val typeMatch: Float = 0.15f,
        val proximity: Float = 0.09f,
        val recency: Float = 0.20f,
        val frequency: Float = 0.03f,
        val attributes: Float = 0.02f
    )

    /**
     * Score all [candidates] against [entity] and return them as [ScoredCandidate] list,
     * sorted by composite score descending.
     *
     * @param neighborIds Object IDs of entities already in this extraction (for proximity).
     * @param nowMs       Current time in ms; injected for testability.
     */
    suspend fun score(
        entity: ExtractedEntity,
        candidates: List<PebbleObject>,
        neighborIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<ScoredCandidate> {
        return candidates.map { candidate ->
            val signals = computeSignals(entity, candidate, neighborIds, nowMs)
            val composite = computeComposite(signals)
            ScoredCandidate(object_ = candidate, score = composite, signals = signals)
        }.sortedByDescending { it.score }
    }

    // ── Signal computation ──────────────────────────────────────────────────

    private suspend fun computeSignals(
        entity: ExtractedEntity,
        candidate: PebbleObject,
        neighborIds: Set<String>,
        nowMs: Long
    ): SignalBreakdown {
        val candidateName = candidate.details["name"]?.toString() ?: ""
        return SignalBreakdown(
            nameSimilarity = nameSignal(entity.name, candidateName),
            typeMatch = typeSignal(entity.typeKey, candidate.typeKey),
            proximityScore = proximitySignal(candidate.id, neighborIds),
            recencyScore = recencySignal(candidate.details["lastModifiedDate"], nowMs),
            frequencyScore = frequencySignal(entity.name, entity.typeKey, candidate.id),
            attributeScore = attributeSignal(entity.attributes, candidate.details)
        )
    }

    private fun computeComposite(s: SignalBreakdown): Float =
        (s.nameSimilarity * weights.nameSimilarity) +
            (s.typeMatch * weights.typeMatch) +
            (s.proximityScore * weights.proximity) +
            (s.recencyScore * weights.recency) +
            (s.frequencyScore * weights.frequency) +
            (s.attributeScore * weights.attributes)

    // ── Individual signals ──────────────────────────────────────────────────

    /** Levenshtein + token overlap hybrid similarity. */
    private fun nameSignal(extracted: String, candidateName: String): Float =
        NameSimilarity.score(extracted, candidateName)

    /**
     * Exact type match = 1.0; related type (same tier/layout) = 0.5; unrelated = 0.0.
     */
    private fun typeSignal(extractedKey: String, candidateKey: String): Float = when {
        extractedKey == candidateKey -> 1.0f
        relatedTypes(extractedKey, candidateKey) -> 0.5f
        else -> 0.0f
    }

    /**
     * Returns true if two type keys are considered "related" for scoring purposes.
     * Currently: both ot-pkm-event and ot-pkm-meeting are event-like.
     */
    private fun relatedTypes(a: String, b: String): Boolean {
        val eventLike = setOf("ot-pkm-event", "ot-pkm-meeting")
        val taskLike = setOf("ot-task", "ot-pkm-reminder")
        val personLike = setOf("ot-human", "ot-pkm-org")
        return (a in eventLike && b in eventLike) ||
            (a in taskLike && b in taskLike) ||
            (a in personLike && b in personLike)
    }

    /**
     * 1.0 if the candidate is already a neighbour (linked to another entity in this extraction),
     * 0.5 if the candidate appears in the context window, 0.0 otherwise.
     */
    private fun proximitySignal(candidateId: String, neighborIds: Set<String>): Float = when {
        candidateId in neighborIds -> 1.0f
        contextWindow.recentEntities().any { it.objectId == candidateId } -> 0.5f
        else -> 0.0f
    }

    /**
     * Exponential decay on last-modified date.  Half-life ≈ 7 days.
     * Objects modified today score ~1.0; objects not modified in 14 days score ~0.25.
     */
    private fun recencySignal(lastModified: Any?, nowMs: Long): Float {
        val modifiedMs = when (lastModified) {
            is Long -> lastModified
            is Double -> lastModified.toLong()
            is String -> lastModified.toLongOrNull() ?: return 0f
            else -> return 0f
        }
        val ageDays = (nowMs - modifiedMs) / (1000.0 * 60 * 60 * 24)
        if (ageDays < 0) return 1f
        return exp(-0.099 * ageDays).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Frequency boost from past user resolutions (stored in [ResolutionFeedbackStore]).
     * Capped at 1.0; 3 past resolutions to same object yields ~1.0.
     */
    private suspend fun frequencySignal(name: String, typeKey: String, candidateId: String): Float {
        val boost = feedbackStore.getFrequencyBoost(name, typeKey, candidateId)
        return boost.coerceIn(0f, 1f)
    }

    /**
     * Jaccard overlap between extracted attribute keys and candidate relation keys.
     */
    private fun attributeSignal(
        extractedAttrs: Map<String, String>,
        candidateDetails: Map<String, Any?>
    ): Float {
        if (extractedAttrs.isEmpty()) return 0f
        val extracted = extractedAttrs.keys.toSet()
        val candidate = candidateDetails.keys.toSet()
        val intersection = extracted.intersect(candidate).size
        val union = (extracted + candidate).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }
}
