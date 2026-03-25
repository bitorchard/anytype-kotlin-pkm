package com.anytypeio.anytype.feature.pebble.ui

import com.anytypeio.anytype.feature.pebble.ui.manual.ManualInputState
import com.anytypeio.anytype.feature.pebble.ui.manual.ManualInputViewModel
import com.anytypeio.anytype.pebble.webhook.model.InputQueueEntry
import com.anytypeio.anytype.pebble.webhook.model.RawInput
import com.anytypeio.anytype.pebble.webhook.queue.InputQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualInputViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var queue: StubInputQueue
    private lateinit var viewModel: ManualInputViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        queue = StubInputQueue()
        viewModel = ManualInputViewModel(queue)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(viewModel.state.value is ManualInputState.Idle)
    }

    @Test
    fun `submit blank text does not change state`() = runTest(dispatcher) {
        viewModel.submit("   ")
        advanceUntilIdle()
        assertTrue(viewModel.state.value is ManualInputState.Idle)
        assertEquals(0, queue.enqueued.size)
    }

    @Test
    fun `submit valid text transitions to Submitted with the returned id`() = runTest(dispatcher) {
        queue.nextId = "entry-42"
        viewModel.submit("Aarav has a game on Friday")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is ManualInputState.Submitted)
        assertEquals("entry-42", (state as ManualInputState.Submitted).inputId)
    }

    @Test
    fun `submit trims text before enqueuing`() = runTest(dispatcher) {
        viewModel.submit("  hello world  ")
        advanceUntilIdle()

        assertEquals(1, queue.enqueued.size)
        assertEquals("hello world", queue.enqueued.first().text)
    }

    @Test
    fun `submit sets source to manual`() = runTest(dispatcher) {
        viewModel.submit("some text")
        advanceUntilIdle()

        assertEquals("manual", queue.enqueued.first().source)
    }

    @Test
    fun `submit when queue throws transitions to Error state`() = runTest(dispatcher) {
        queue.throwOnEnqueue = RuntimeException("disk full")
        viewModel.submit("some text")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is ManualInputState.Error)
        assertEquals("disk full", (state as ManualInputState.Error).message)
    }

    @Test
    fun `reset transitions back to Idle from Submitted`() = runTest(dispatcher) {
        queue.nextId = "id-1"
        viewModel.submit("some text")
        advanceUntilIdle()

        viewModel.reset()
        assertTrue(viewModel.state.value is ManualInputState.Idle)
    }

    @Test
    fun `reset is idempotent when already Idle`() {
        viewModel.reset()
        assertTrue(viewModel.state.value is ManualInputState.Idle)
    }

    // ── Stub ─────────────────────────────────────────────────────────────────

    private class StubInputQueue : InputQueue {
        val enqueued = mutableListOf<RawInput>()
        var nextId: String = "stub-id"
        var throwOnEnqueue: Exception? = null

        override suspend fun enqueue(input: RawInput): String {
            throwOnEnqueue?.let { throw it }
            enqueued.add(input)
            return nextId
        }

        override suspend fun dequeue(): InputQueueEntry? = null
        override suspend fun markProcessed(id: String, resultChangeSetId: String?) {}
        override suspend fun markFailed(id: String, error: String) {}
        override fun getPending(): Flow<List<InputQueueEntry>> = MutableStateFlow(emptyList())
        override suspend fun getRecent(limit: Int): List<InputQueueEntry> = emptyList()
    }
}
