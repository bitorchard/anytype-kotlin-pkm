package com.anytypeio.anytype.pebble.assimilation.resolution

/**
 * String-similarity utilities for entity name matching.
 *
 * Combines Levenshtein edit distance with token-level overlap so that both
 * character-level misspellings ("Arav" → "Aarav") and word-order variants
 * ("Dr. Patel" → "Patel, Dr.") score reasonably.
 */
object NameSimilarity {

    /**
     * Combined similarity score in the range [0.0, 1.0].
     *
     * Weighted 60% normalised Levenshtein + 40% token-overlap Jaccard.
     */
    fun score(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        val norm = (a.trim() to b.trim()).let { (x, y) ->
            // Case-insensitive, collapse whitespace
            x.lowercase().replace(Regex("\\s+"), " ") to
                y.lowercase().replace(Regex("\\s+"), " ")
        }
        val (x, y) = norm
        if (x == y) return 1f

        val editSim = 1f - (levenshtein(x, y).toFloat() / maxOf(x.length, y.length))
        val tokenSim = tokenOverlap(x, y)
        return 0.60f * editSim + 0.40f * tokenSim
    }

    /**
     * Levenshtein edit distance between two strings.
     * Uses the standard DP approach — O(m×n) time and O(n) space.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { k -> prev[k] = curr[k] }
        }
        return prev[b.length]
    }

    /**
     * Jaccard similarity on the token (word) sets of two strings.
     * "Dr. Patel" vs "Patel" → intersection={patel}, union={dr, patel} → 0.5
     */
    fun tokenOverlap(a: String, b: String): Float {
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1f
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        val intersection = tokensA.intersect(tokensB).size
        val union = (tokensA + tokensB).toSet().size
        return intersection.toFloat() / union
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
}
