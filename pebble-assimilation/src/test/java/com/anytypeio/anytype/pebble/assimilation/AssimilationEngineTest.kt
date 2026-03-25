package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.llm.LlmException
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.assimilation.plan.PlanGenerator
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolver
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.core.RawVoiceInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Uses [FakeEntityExtractionService] and [FakeEntityResolutionService] instead of
 * Mockito mocks to avoid the `@JvmInline` SpaceId / coroutine matcher incompatibility.
 * [PlanGenerator] and [ChangeStore] are plain Mockito mocks (no inline class parameters).
 */
class AssimilationEngineTest {

    private val entityExtractor = FakeEntityExtractionService()
    private val entityResolver = FakeEntityResolutionService()
    private val planGenerator: PlanGenerator = mock()
    private val changeStore: ChangeStore = mock()
    private val contextWindow: ContextWindow = mock()
    private lateinit var engine: AssimilationEngine

    private val space = SpaceId("test-space")
    private val input = RawVoiceInput(
        id = "input-001",
        traceId = "trace-001",
        text = "Aarav has a basketball game on Friday",
        receivedAt = 0L
    )

    @Before
    fun setup() {
        entityExtractor.calls.clear()
        entityResolver.resolveCallCount = 0
        engine = AssimilationEngine(entityExtractor, entityResolver, planGenerator, changeStore, contextWindow)
    }

    @Test
    fun `successful end-to-end pipeline returns Success with changeSetId`() = runTest {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val extraction = ExtractionResult(listOf(entity), emptyList(), modelVersion = "claude-sonnet-4-5")
        val resolutionResult = EntityResolver.ResolutionResult(
            resolved = listOf(ResolvedEntity(entity, ResolutionDecision.CreateNew("ot-human"))),
            pendingDisambiguation = emptyList()
        )
        val plan = PlanGenerator().generate(
            resolutionResult.resolved, extraction,
            spaceId = space.id, sourceText = input.text
        )

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(resolutionResult)
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenReturn("saved-cs-001")

        val result = engine.process(input, space)

        assertTrue("Expected Success, got $result", result is AssimilationResult.Success)
        assertEquals("trace-001", (result as AssimilationResult.Success).traceId)
        verify(changeStore).save(any())
        verify(contextWindow).record(any())
    }

    @Test
    fun `LLM network exception returns Offline`() = runTest {
        entityExtractor.enqueueError(LlmException.NetworkException("no network"))

        val result = engine.process(input, space)

        assertTrue("Expected Offline, got $result", result is AssimilationResult.Offline)
        verify(changeStore, never()).save(any())
    }

    @Test
    fun `LLM auth exception returns non-retryable Failure`() = runTest {
        entityExtractor.enqueueError(LlmException.AuthException("401 rejected"))

        val result = engine.process(input, space)

        assertTrue("Expected Failure", result is AssimilationResult.Failure)
        assertEquals(false, (result as AssimilationResult.Failure).retryable)
    }

    @Test
    fun `LLM rate limit exception returns retryable Failure`() = runTest {
        entityExtractor.enqueueError(LlmException.RateLimitException("429 rate limit"))

        val result = engine.process(input, space)

        assertTrue(result is AssimilationResult.Failure)
        assertTrue("Rate limit should be retryable", (result as AssimilationResult.Failure).retryable)
    }

    @Test
    fun `empty extraction returns non-retryable Failure`() = runTest {
        entityExtractor.enqueue(ExtractionResult(emptyList(), emptyList()))

        val result = engine.process(input, space)

        assertTrue(result is AssimilationResult.Failure)
        assertEquals(false, (result as AssimilationResult.Failure).retryable)
        verify(changeStore, never()).save(any())
    }

    @Test
    fun `changeStore save failure returns retryable Failure`() = runTest {
        val entity = ExtractedEntity("e1", "ot-human", "Aarav")
        val extraction = ExtractionResult(listOf(entity), emptyList())
        val resolutionResult = EntityResolver.ResolutionResult(
            resolved = listOf(ResolvedEntity(entity, ResolutionDecision.CreateNew("ot-human"))),
            pendingDisambiguation = emptyList()
        )
        val plan = PlanGenerator().generate(
            resolutionResult.resolved, extraction,
            spaceId = space.id, sourceText = input.text
        )

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(resolutionResult)
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenThrow(RuntimeException("DB error"))

        val result = engine.process(input, space)

        assertTrue(result is AssimilationResult.Failure)
        assertTrue("Storage failure should be retryable", (result as AssimilationResult.Failure).retryable)
    }
}
