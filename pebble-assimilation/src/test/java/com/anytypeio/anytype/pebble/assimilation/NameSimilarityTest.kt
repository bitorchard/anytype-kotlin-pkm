package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.resolution.NameSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSimilarityTest {

    @Test
    fun `identical strings score 1`() {
        assertEquals(1f, NameSimilarity.score("Aarav", "Aarav"), 0.001f)
    }

    @Test
    fun `identical strings case-insensitive score 1`() {
        assertEquals(1f, NameSimilarity.score("aarav", "AARAV"), 0.001f)
    }

    @Test
    fun `completely different strings score near 0`() {
        val s = NameSimilarity.score("Aarav", "Zeppelin")
        assertTrue("Expected score < 0.2, got $s", s < 0.2f)
    }

    @Test
    fun `single character deletion scores high`() {
        // "Arav" vs "Aarav" — 1 char difference
        val s = NameSimilarity.score("Arav", "Aarav")
        assertTrue("Expected score > 0.7 for single-char deletion, got $s", s > 0.7f)
    }

    @Test
    fun `title with last name scores medium`() {
        // "Dr. Patel" vs "Patel" — token overlap = 0.5
        val s = NameSimilarity.score("Dr. Patel", "Patel")
        assertTrue("Expected score > 0.4 for Dr. Patel vs Patel, got $s", s > 0.4f)
    }

    @Test
    fun `blank strings score 0`() {
        assertEquals(0f, NameSimilarity.score("", "Aarav"), 0.001f)
        assertEquals(0f, NameSimilarity.score("Aarav", ""), 0.001f)
    }

    @Test
    fun `levenshtein of equal strings is 0`() {
        assertEquals(0, NameSimilarity.levenshtein("hello", "hello"))
    }

    @Test
    fun `levenshtein single substitution is 1`() {
        assertEquals(1, NameSimilarity.levenshtein("kitten", "sitten"))
    }

    @Test
    fun `levenshtein classic kitten to sitting is 3`() {
        assertEquals(3, NameSimilarity.levenshtein("kitten", "sitting"))
    }

    @Test
    fun `token overlap exact match is 1`() {
        assertEquals(1f, NameSimilarity.tokenOverlap("dr patel", "dr patel"), 0.001f)
    }

    @Test
    fun `token overlap partial match is between 0 and 1`() {
        val s = NameSimilarity.tokenOverlap("dr patel", "patel")
        assertTrue("Expected 0 < overlap < 1, got $s", s > 0f && s < 1f)
    }
}
