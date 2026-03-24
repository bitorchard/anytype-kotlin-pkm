package com.anytypeio.anytype.pebble

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.block.repo.BlockRepository
import com.anytypeio.anytype.domain.launch.GetDefaultObjectType
import com.anytypeio.anytype.domain.objects.DeleteObjects
import com.anytypeio.anytype.domain.`object`.SetObjectDetails
import com.anytypeio.anytype.domain.page.CreateObject
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleGraphService
import com.anytypeio.anytype.pebble.core.impl.DefaultPebbleSearchService
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Smoke test verifying that [DefaultPebbleGraphService] and [DefaultPebbleSearchService] can be
 * instantiated with their required dependencies and satisfy the [PebbleGraphService] /
 * [PebbleSearchService] contracts.
 *
 * This is the Phase 0 DI verification described in the implementation plan (Task 0.5).
 * The test does not require a full Dagger graph — it constructs the adapters directly with mocks,
 * mirroring what Dagger would do at runtime.
 */
class PebbleDITest {

    private val dispatchers = AppCoroutineDispatchers(
        io = Dispatchers.Unconfined,
        computation = Dispatchers.Unconfined,
        main = Dispatchers.Unconfined
    )
    private val mockRepo: BlockRepository = mock()
    private val mockGetDefaultObjectType: GetDefaultObjectType = mock()
    private val mockSetObjectDetails: SetObjectDetails = mock()
    private val mockDeleteObjects: DeleteObjects = mock()

    @Test
    fun `DefaultPebbleGraphService satisfies PebbleGraphService contract`() {
        val createObject = CreateObject(
            repo = mockRepo,
            getDefaultObjectType = mockGetDefaultObjectType,
            dispatchers = dispatchers
        )
        val service: PebbleGraphService = DefaultPebbleGraphService(
            repo = mockRepo,
            createObject = createObject,
            setObjectDetails = mockSetObjectDetails,
            deleteObjects = mockDeleteObjects,
            dispatchers = dispatchers
        )
        assertNotNull(service)
        assertTrue(service is DefaultPebbleGraphService)
    }

    @Test
    fun `DefaultPebbleSearchService satisfies PebbleSearchService contract`() {
        val service: PebbleSearchService = DefaultPebbleSearchService(
            repo = mockRepo,
            dispatchers = dispatchers
        )
        assertNotNull(service)
        assertTrue(service is DefaultPebbleSearchService)
    }
}
