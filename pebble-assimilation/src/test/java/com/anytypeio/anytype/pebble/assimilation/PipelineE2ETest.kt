package com.anytypeio.anytype.pebble.assimilation

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.assimilation.context.ContextWindow
import com.anytypeio.anytype.pebble.assimilation.model.AssimilationPlan
import com.anytypeio.anytype.pebble.assimilation.model.ExtractedEntity
import com.anytypeio.anytype.pebble.assimilation.model.ExtractionResult
import com.anytypeio.anytype.pebble.assimilation.model.ResolutionDecision
import com.anytypeio.anytype.pebble.assimilation.model.ResolvedEntity
import com.anytypeio.anytype.pebble.assimilation.model.DisambiguationChoice
import com.anytypeio.anytype.pebble.assimilation.model.ScoredCandidate
import com.anytypeio.anytype.pebble.assimilation.model.SignalBreakdown
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.assimilation.plan.PlanGenerator
import com.anytypeio.anytype.pebble.assimilation.resolution.EntityResolver
import com.anytypeio.anytype.pebble.assimilation.llm.LlmException
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.core.RawVoiceInput
import com.anytypeio.anytype.pebble.core.observability.PipelineEvent
import com.anytypeio.anytype.pebble.core.observability.PipelineEventStore
import com.anytypeio.anytype.pebble.core.observability.PipelineStage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * End-to-end pipeline integration tests.
 *
 * Uses [FakeEntityExtractionService] and [FakeEntityResolutionService] to avoid
 * the Mockito `@JvmInline` value-class / coroutine matcher incompatibility.
 * [PlanGenerator], [ChangeStore], [ChangeExecutor], and [PipelineEventStore] remain
 * as Mockito mocks because they don't take inline class parameters.
 */
class PipelineE2ETest {

    private val entityExtractor = FakeEntityExtractionService()
    private val entityResolver = FakeEntityResolutionService()
    private val planGenerator: PlanGenerator = mock()
    private val changeStore: ChangeStore = mock()
    private val contextWindow: ContextWindow = ContextWindow()
    private val eventStore: PipelineEventStore = mock()
    private val changeExecutor: ChangeExecutor = mock()

    private val space = SpaceId("space-e2e")
    private val voiceInput = RawVoiceInput(
        id = "input-e2e-001",
        traceId = "trace-e2e-001",
        text = "Aarav has a basketball game on Friday at 5pm",
        receivedAt = 0L
    )

    private lateinit var engine: AssimilationEngine

    @Before
    fun setup() {
        entityExtractor.calls.clear()
        entityResolver.resolveCallCount = 0
        engine = AssimilationEngine(
            entityExtractor = entityExtractor,
            entityResolver = entityResolver,
            planGenerator = planGenerator,
            changeStore = changeStore,
            contextWindow = contextWindow,
            eventStore = eventStore
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createOp(spaceId: String = space.id) = ChangeOperation(
        id = "op-001",
        changeSetId = "cs-placeholder",
        ordinal = 0,
        type = OperationType.CREATE_OBJECT,
        params = OperationParams.CreateObjectParams(spaceId = spaceId, typeKey = "ot-human")
    )

    private fun metadata(confidence: Float) = ChangeSetMetadata(
        spaceId = space.id,
        sourceText = voiceInput.text,
        extractionConfidence = confidence,
        modelVersion = "claude-sonnet-4-5"
    )

    private fun stubbedResolution(entities: List<ExtractedEntity>): EntityResolver.ResolutionResult {
        val resolved = entities.map { ResolvedEntity(it, ResolutionDecision.CreateNew(it.typeKey)) }
        return EntityResolver.ResolutionResult(resolved, emptyList())
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `happy path - voice input flows through all stages and returns Success`() = runTest {
        val entities = listOf(
            ExtractedEntity("e1", "ot-human", "Aarav"),
            ExtractedEntity("e2", "ot-sport", "basketball")
        )
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.92f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.92f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }

        val result = engine.process(voiceInput, space)

        assertTrue("Expected Success but got $result", result is AssimilationResult.Success)
        assertNotNull((result as AssimilationResult.Success).changeSetId)
    }

    @Test
    fun `pipeline records observability events for all stages`() = runTest {
        val entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav"))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.85f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.85f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }

        engine.process(voiceInput, space)

        val eventCaptor = argumentCaptor<PipelineEvent>()
        verify(eventStore, atLeastOnce()).record(eventCaptor.capture())
        val stages = eventCaptor.allValues.map { it.stage }

        assertTrue("Expected ENTITY_RESOLVING event", PipelineStage.ENTITY_RESOLVING in stages)
        assertTrue("Expected ENTITY_RESOLVED event", PipelineStage.ENTITY_RESOLVED in stages)
        assertTrue("Expected PLAN_GENERATED event", PipelineStage.PLAN_GENERATED in stages)
        assertTrue("Expected APPROVAL_PENDING event", PipelineStage.APPROVAL_PENDING in stages)
    }

    // ── Auto-approve path ─────────────────────────────────────────────────────

    @Test
    fun `auto-approve - high confidence plan is executed immediately and returns AutoApplied`() = runTest {
        engine = AssimilationEngine(
            entityExtractor, entityResolver, planGenerator, changeStore, contextWindow,
            eventStore, autoApproveThreshold = 0.80f, changeExecutor = changeExecutor
        )

        val entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav"))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.95f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.95f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }
        whenever(changeExecutor.execute(any<ChangeSet>())).thenReturn(
            ExecutionResult.Success("cs-001", emptyList())
        )

        val result = engine.process(voiceInput, space)

        assertTrue("Expected AutoApplied but got $result", result is AssimilationResult.AutoApplied)
    }

    @Test
    fun `auto-approve - low confidence plan waits for approval`() = runTest {
        engine = AssimilationEngine(
            entityExtractor, entityResolver, planGenerator, changeStore, contextWindow,
            eventStore, autoApproveThreshold = 0.90f, changeExecutor = changeExecutor
        )

        val entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav", confidence = 0.60f))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.65f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.65f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }

        val result = engine.process(voiceInput, space)

        assertTrue("Expected Success (pending review) but got $result", result is AssimilationResult.Success)
    }

    @Test
    fun `auto-approve - disambiguation required falls back to pending review`() = runTest {
        engine = AssimilationEngine(
            entityExtractor, entityResolver, planGenerator, changeStore, contextWindow,
            eventStore, autoApproveThreshold = 0.80f, changeExecutor = changeExecutor
        )

        val entities = listOf(ExtractedEntity("e1", "ot-human", "Alex"))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.92f)
        val alexSmith = PebbleObject("obj-alex-s", "Alex Smith", "ot-human", emptyMap(), null)
        val alexJohnson = PebbleObject("obj-alex-j", "Alex Johnson", "ot-human", emptyMap(), null)
        val disambiguationChoices = listOf(
            DisambiguationChoice(
                entity = entities[0],
                candidates = listOf(
                    ScoredCandidate(alexSmith, 0.80f, SignalBreakdown()),
                    ScoredCandidate(alexJohnson, 0.79f, SignalBreakdown())
                )
            )
        )
        val resolutionResult = EntityResolver.ResolutionResult(
            resolved = emptyList(),
            pendingDisambiguation = disambiguationChoices
        )
        val plan = AssimilationPlan(
            operations = emptyList(),
            disambiguationChoices = disambiguationChoices,
            metadata = metadata(0.92f)
        )

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(resolutionResult)
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }

        val result = engine.process(voiceInput, space)

        assertTrue("Expected Success (pending review) but got $result", result is AssimilationResult.Success)
    }

    // ── Error paths ────────────────────────────────────────────────────────────

    @Test
    fun `LLM offline returns AssimilationResult Offline`() = runTest {
        entityExtractor.enqueueError(LlmException.NetworkException("timeout"))

        val result = engine.process(voiceInput, space)

        assertEquals(AssimilationResult.Offline, result)
    }

    @Test
    fun `LLM rate limit returns retryable Failure`() = runTest {
        entityExtractor.enqueueError(LlmException.RateLimitException("429"))

        val result = engine.process(voiceInput, space)

        assertTrue(result is AssimilationResult.Failure)
        assertTrue((result as AssimilationResult.Failure).retryable)
    }

    @Test
    fun `empty extraction result returns non-retryable Failure`() = runTest {
        entityExtractor.enqueue(ExtractionResult(emptyList(), emptyList()))

        val result = engine.process(voiceInput, space)

        assertTrue(result is AssimilationResult.Failure)
        assertTrue(!(result as AssimilationResult.Failure).retryable)
    }

    @Test
    fun `change store persistence failure returns retryable Failure`() = runTest {
        val entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav"))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.90f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.90f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenThrow(RuntimeException("DB unavailable"))

        val result = engine.process(voiceInput, space)

        assertTrue(result is AssimilationResult.Failure)
        assertTrue((result as AssimilationResult.Failure).retryable)
    }

    // ── Context window ────────────────────────────────────────────────────────

    @Test
    fun `context window is updated after successful processing`() = runTest {
        val entities = listOf(ExtractedEntity("e1", "ot-human", "Aarav"))
        val extraction = ExtractionResult(entities, emptyList(), overallConfidence = 0.88f)
        val plan = AssimilationPlan(operations = listOf(createOp()), metadata = metadata(0.88f))

        entityExtractor.enqueue(extraction)
        entityResolver.willReturn(stubbedResolution(entities))
        whenever(planGenerator.generate(any(), any(), any(), any(), any(), any(), any())).thenReturn(plan)
        whenever(changeStore.save(any())).thenAnswer { inv -> (inv.getArgument<ChangeSet>(0)).id }

        engine.process(voiceInput, space)

        val recentEntities = contextWindow.recentEntities()
        assertTrue("Context window should contain the resolved entity", recentEntities.isNotEmpty())
    }
}
