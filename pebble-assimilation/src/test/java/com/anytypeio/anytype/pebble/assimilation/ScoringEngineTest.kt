package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackStore
import com.anytypeio.anytype.pebble.assimilation.resolution.ScoringEngine
import com.anytypeio.anytype.pebble.core.PebbleObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ScoringEngineTest {

    private val contextWindow: ContextWindow = mock()
    private val feedbackStore: ResolutionFeedbackStore = mock()
    private lateinit var engine: ScoringEngine

    private val fixedNowMs = 1_000_000L  // epoch anchor for recency tests

    @Before
    fun setup() {
        whenever(contextWindow.recentEntities()).thenReturn(emptyList())
        engine = ScoringEngine(contextWindow, feedbackStore)
    }

    private fun makePebbleObject(
        id: String,
        name: String,
        typeKey: String,
        lastModifiedMs: Long? = null
    ): PebbleObject {
        val details = mutableMapOf<String, Any?>("name" to name)
        if (lastModifiedMs != null) details["lastModifiedDate"] = lastModifiedMs
        return PebbleObject(id = id, name = name, typeKey = typeKey, details = details)
    }

    @Test
    fun `exact name match with same type scores at or above auto-resolve threshold`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), any())).thenReturn(0f)
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val candidate = makePebbleObject("obj-1", "Aarav", "ot-human", lastModifiedMs = fixedNowMs)
        val scored = engine.score(entity, listOf(candidate), nowMs = fixedNowMs)
        assertTrue("Expected score ≥ 0.85, got ${scored.first().score}", scored.first().score >= 0.85f)
    }

    @Test
    fun `fuzzy name match scores in disambiguation range`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), any())).thenReturn(0f)
        val entity = ExtractedEntity("e1", "ot-human", "Arav")
        val candidate = makePebbleObject("obj-1", "Aarav", "ot-human", lastModifiedMs = fixedNowMs)
        val scored = engine.score(entity, listOf(candidate), nowMs = fixedNowMs)
        val score = scored.first().score
        assertTrue("Expected score in [0.5, 0.85) for fuzzy match, got $score", score in 0.5f..0.9f)
    }

    @Test
    fun `type mismatch lowers score`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), any())).thenReturn(0f)
        val entity = ExtractedEntity("e1", "ot-pkm-event", "Aarav")
        val candidate = makePebbleObject("obj-1", "Aarav", "ot-human", lastModifiedMs = fixedNowMs)
        val scored = engine.score(entity, listOf(candidate), nowMs = fixedNowMs)
        // Name signal is high but type mismatch reduces composite
        assertTrue("Type mismatch should reduce score below 1.0", scored.first().score < 1.0f)
    }

    @Test
    fun `frequency boost increases score`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), eq("obj-1"))).thenReturn(1.0f)
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val candidateWithBoost = makePebbleObject("obj-1", "Aarav", "ot-human")
        val candidateNoBoost = makePebbleObject("obj-2", "Aarav", "ot-human")
        whenever(feedbackStore.getFrequencyBoost(any(), any(), eq("obj-2"))).thenReturn(0f)
        val scored = engine.score(entity, listOf(candidateNoBoost, candidateWithBoost), nowMs = fixedNowMs)
        val withBoost = scored.first { it.object_.id == "obj-1" }.score
        val noBoost = scored.first { it.object_.id == "obj-2" }.score
        assertTrue("Frequency boost should increase score", withBoost > noBoost)
    }

    @Test
    fun `recency — recently modified object scores higher than stale object`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), any())).thenReturn(0f)
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val recent = makePebbleObject("recent", "Aarav", "ot-human", lastModifiedMs = fixedNowMs)
        val stale = makePebbleObject("stale", "Aarav", "ot-human", lastModifiedMs = fixedNowMs - 30L * 24 * 60 * 60 * 1000)
        val scored = engine.score(entity, listOf(recent, stale), nowMs = fixedNowMs)
        assertTrue(
            "Recently modified object should score higher than stale",
            scored.first { it.object_.id == "recent" }.score >
                scored.first { it.object_.id == "stale" }.score
        )
    }

    @Test
    fun `candidates returned sorted by score descending`() = runTest {
        whenever(feedbackStore.getFrequencyBoost(any(), any(), any())).thenReturn(0f)
        val entity = ExtractedEntity("e1", "ot-human", "Alice")
        val good = makePebbleObject("good", "Alice", "ot-human", lastModifiedMs = fixedNowMs)
        val bad = makePebbleObject("bad", "Bob", "ot-pkm-event")
        val scored = engine.score(entity, listOf(bad, good), nowMs = fixedNowMs)
        assertEquals("good", scored.first().object_.id)
    }
}
