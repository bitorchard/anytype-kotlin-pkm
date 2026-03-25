package com.anytypeio.anytype.feature.pebble.ui

import com.anytypeio.anytype.feature.pebble.ui.approval.ApprovalStep
import com.anytypeio.anytype.feature.pebble.ui.approval.ApprovalViewModel
import com.anytypeio.anytype.pebble.assimilation.resolution.DisambiguationResolver
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackDao
import com.anytypeio.anytype.pebble.assimilation.resolution.ResolutionFeedbackStore
import com.anytypeio.anytype.pebble.changecontrol.engine.ChangeExecutor
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetMetadata
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus
import com.anytypeio.anytype.pebble.changecontrol.model.ExecutionResult
import com.anytypeio.anytype.pebble.changecontrol.model.OperationParams
import com.anytypeio.anytype.pebble.changecontrol.model.OperationStatus
import com.anytypeio.anytype.pebble.changecontrol.model.OperationType
import com.anytypeio.anytype.pebble.changecontrol.store.ChangeStore
import com.anytypeio.anytype.pebble.core.PebbleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val changeStore: ChangeStore = mock()
    private val changeExecutor: ChangeExecutor = mock()
    private val disambiguationResolver = DisambiguationResolver()
    private val feedbackDao: ResolutionFeedbackDao = mock()
    private val feedbackStore = ResolutionFeedbackStore(feedbackDao)

    private lateinit var viewModel: ApprovalViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): ApprovalViewModel = ApprovalViewModel(
        changeStore = changeStore,
        changeExecutor = changeExecutor,
        disambiguationResolver = disambiguationResolver,
        feedbackStore = feedbackStore
    )

    private fun makeChangeSet(
        id: String = "cs-1",
        status: ChangeSetStatus = ChangeSetStatus.PENDING,
        summary: String = "Added event",
        operations: List<ChangeOperation> = emptyList(),
        disambiguationChoicesJson: String = ""
    ) = ChangeSet(
        id = id,
        inputId = "input-1",
        traceId = "trace-1",
        status = status,
        summary = summary,
        operations = operations,
        metadata = ChangeSetMetadata(spaceId = "space-1", sourceText = "test"),
        createdAt = 0L,
        disambiguationChoicesJson = disambiguationChoicesJson
    )

    private fun makeOperation(id: String = "op-1", ordinal: Int = 0): ChangeOperation = ChangeOperation(
        id = id,
        changeSetId = "cs-1",
        ordinal = ordinal,
        type = OperationType.SET_DETAILS,
        params = OperationParams.SetDetailsParams(objectId = "obj-1", details = mapOf("name" to "Test")),
        status = OperationStatus.PENDING
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `load with no pending change sets produces empty state`() = runTest(dispatcher) {
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(emptyList())

        viewModel = makeViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.pendingChangeSets.isEmpty())
        assertNull(state.current)
        assertTrue(state.step is ApprovalStep.ReviewingPlan)
    }

    @Test
    fun `load with pending change sets sets first as current`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        val cs2 = makeChangeSet(id = "cs-2")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1, cs2))

        viewModel = makeViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.pendingChangeSets.size)
        assertEquals("cs-1", state.current?.id)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `approve calls executor and advances to next pending set`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1", operations = listOf(makeOperation()))
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1))
            .thenReturn(emptyList())
        whenever(changeExecutor.execute(cs1))
            .thenReturn(ExecutionResult.Success(cs1.id, emptyList()))

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.approve()
        advanceUntilIdle()

        verify(changeStore).updateStatus(cs1.id, ChangeSetStatus.APPROVED)
        verify(changeExecutor).execute(cs1)
        assertTrue(viewModel.state.value.pendingChangeSets.isEmpty())
        assertEquals("Changes applied", viewModel.state.value.message)
    }

    @Test
    fun `reject updates status and does not call executor`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1))
            .thenReturn(emptyList())

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.reject()
        advanceUntilIdle()

        verify(changeStore).updateStatus(cs1.id, ChangeSetStatus.REJECTED)
        verify(changeExecutor, never()).execute(any())
        assertEquals("Rejected", viewModel.state.value.message)
    }

    @Test
    fun `next increments current index`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        val cs2 = makeChangeSet(id = "cs-2")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1, cs2))

        viewModel = makeViewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.currentIndex)
        viewModel.next()
        assertEquals(1, viewModel.state.value.currentIndex)
        assertEquals("cs-2", viewModel.state.value.current?.id)
    }

    @Test
    fun `next does nothing when at last change set`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1))

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.next() // already at last
        assertEquals(0, viewModel.state.value.currentIndex)
    }

    @Test
    fun `previous decrements current index`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        val cs2 = makeChangeSet(id = "cs-2")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1, cs2))

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.next()
        assertEquals(1, viewModel.state.value.currentIndex)

        viewModel.previous()
        assertEquals(0, viewModel.state.value.currentIndex)
        assertEquals("cs-1", viewModel.state.value.current?.id)
    }

    @Test
    fun `previous does nothing when at first change set`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1")
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1))

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.previous()
        assertEquals(0, viewModel.state.value.currentIndex)
    }

    @Test
    fun `approve on partial failure shows error message`() = runTest(dispatcher) {
        val cs1 = makeChangeSet(id = "cs-1", operations = listOf(makeOperation()))
        whenever(changeStore.getChangeSets(status = ChangeSetStatus.PENDING, limit = 50))
            .thenReturn(listOf(cs1))
            .thenReturn(emptyList())
        whenever(changeExecutor.execute(cs1))
            .thenReturn(
                ExecutionResult.PartialFailure(
                    changeSetId = cs1.id,
                    firstFailedOrdinal = 0,
                    error = RuntimeException("graph timeout"),
                    operationResults = emptyList()
                )
            )

        viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.approve()
        advanceUntilIdle()

        val msg = viewModel.state.value.message ?: ""
        assertTrue("message should contain failure info", msg.contains("Apply failed"))
    }
}
