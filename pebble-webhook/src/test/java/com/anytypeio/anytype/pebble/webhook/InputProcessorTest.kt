package com.anytypeio.anytype.pebble.webhook

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.AssimilationResult
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import com.anytypeio.anytype.pebble.webhook.pipeline.InputProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputProcessorTest {

    private val queue = FakeInputQueue()
    private val pipeline = FakePipeline()
    private val notifier = FakePipelineNotifier()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var processor: InputProcessor

    private val testSpace = SpaceId("space-test-001")

    @Before
    fun setup() {
        queue.clear()
        pipeline.calls.clear()
        notifier.approvalEvents.clear()
        notifier.autoAppliedEvents.clear()
        notifier.errorEvents.clear()
        processor = InputProcessor(queue, pipeline, notifier)
    }

    private fun makeInput(id: String = "inp-1", text: String = "Hello world") = RawInput(
        id = id,
        traceId = "trace-$id",
        text = text,
        receivedAt = 0L
    )

    @Test
    fun `success result marks entry processed and fires approval notification`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Success(changeSetId = "cs-1", traceId = input.traceId))

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertTrue("entry should be marked processed", queue.processedIds.contains(input.id))
        assertEquals(1, notifier.approvalEvents.size)
        assertEquals("cs-1", notifier.approvalEvents.first().changeSetId)
    }

    @Test
    fun `auto-applied result marks entry processed and fires auto-applied notification`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(
            AssimilationResult.AutoApplied(
                changeSetId = "cs-2",
                traceId = input.traceId,
                summary = "Added event"
            )
        )

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertTrue(queue.processedIds.contains(input.id))
        assertEquals(1, notifier.autoAppliedEvents.size)
        val event = notifier.autoAppliedEvents.first()
        assertEquals("cs-2", event.changeSetId)
        assertEquals("Added event", event.summary)
    }

    @Test
    fun `retryable failure marks entry failed and fires no approval notification`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Failure(error = "network error", retryable = true))

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertTrue(queue.failedIds.any { it.first == input.id })
        assertTrue(notifier.approvalEvents.isEmpty())
    }

    @Test
    fun `non-retryable failure fires error notification`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Failure(error = "permanent error", retryable = false))

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertTrue(queue.failedIds.any { it.first == input.id })
        assertEquals(1, notifier.errorEvents.size)
        assertEquals("PIPELINE_FAILURE", notifier.errorEvents.first().errorType)
    }

    @Test
    fun `offline result leaves entry in queue`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Offline)

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertTrue("entry should NOT be marked processed", !queue.processedIds.contains(input.id))
        assertTrue("entry should NOT be marked failed", queue.failedIds.none { it.first == input.id })
    }

    @Test
    fun `processor does not start twice when called with start again`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Success(changeSetId = "cs-3", traceId = input.traceId))

        processor.start(testScope, testSpace)
        processor.start(testScope, testSpace) // second call is a no-op
        advanceUntilIdle()
        processor.stop()

        assertEquals("pipeline should be called exactly once", 1, pipeline.calls.size)
    }

    @Test
    fun `pipeline is called with correct space id`() = runTest(testDispatcher) {
        val input = makeInput()
        queue.seed(input)
        pipeline.enqueue(AssimilationResult.Offline)

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertEquals(1, pipeline.calls.size)
        assertEquals(testSpace, pipeline.calls.first().second)
    }

    @Test
    fun `multiple inputs are processed in sequence`() = runTest(testDispatcher) {
        val inputs = (1..3).map { makeInput(id = "inp-$it", text = "Text $it") }
        queue.seed(*inputs.toTypedArray())
        repeat(3) { i ->
            pipeline.enqueue(AssimilationResult.Success(changeSetId = "cs-${i + 1}", traceId = "trace-inp-${i + 1}"))
        }

        processor.start(testScope, testSpace)
        advanceUntilIdle()
        processor.stop()

        assertEquals(3, queue.processedIds.size)
        assertEquals(3, notifier.approvalEvents.size)
    }
}
