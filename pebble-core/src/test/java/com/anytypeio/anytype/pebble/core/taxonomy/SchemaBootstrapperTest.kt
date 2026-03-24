package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.Relation
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleObjectResult
import com.anytypeio.anytype.domain.objects.StoreOfObjectTypes
import com.anytypeio.anytype.domain.objects.StoreOfRelations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SchemaBootstrapperTest {

    private val graphService: PebbleGraphService = mock()
    private val storeOfObjectTypes: StoreOfObjectTypes = mock()
    private val storeOfRelations: StoreOfRelations = mock()
    private val taxonomyProvider = TaxonomyProvider(storeOfObjectTypes, storeOfRelations)
    private val bootstrapper = SchemaBootstrapper(graphService, taxonomyProvider)

    private val testSpace = SpaceId("test-space-id")

    @Before
    fun setup() {
        // Default: nothing exists yet (fresh space)
    }

    @Test
    fun `bootstrap creates all 14 custom types on fresh space`() = runTest {
        // Nothing exists in store
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
        whenever(graphService.createObjectType(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-id"))
        whenever(graphService.createRelation(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-rel-id"))

        val result = bootstrapper.bootstrap(testSpace)

        assertEquals(14, result.typesCreated.size)
        assertEquals(0, result.typesExisted.size)
        verify(graphService, times(14)).createObjectType(any(), any(), any(), any())
    }

    @Test
    fun `bootstrap creates all 30 custom relations on fresh space`() = runTest {
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
        whenever(graphService.createObjectType(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-id"))
        whenever(graphService.createRelation(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-rel-id"))

        val result = bootstrapper.bootstrap(testSpace)

        assertEquals(30, result.relationsCreated.size)
        assertEquals(0, result.relationsExisted.size)
        verify(graphService, times(30)).createRelation(any(), any(), any(), any())
    }

    @Test
    fun `bootstrap does not call createObjectType for built-in types`() = runTest {
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
        whenever(graphService.createObjectType(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-id"))
        whenever(graphService.createRelation(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-rel-id"))

        bootstrapper.bootstrap(testSpace)

        PkmObjectType.builtIn().forEach { builtInType ->
            verify(graphService, never()).createObjectType(
                any(), any(), eq(builtInType.uniqueKey), any()
            )
        }
    }

    @Test
    fun `bootstrap is idempotent - does not create already existing types`() = runTest {
        // Simulate: all custom types already exist
        val mockType = mock<com.anytypeio.anytype.core_models.ObjectWrapper.Type>()
        whenever(mockType.id).thenReturn("existing-id")
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(mockType)

        val mockRelation = mock<com.anytypeio.anytype.core_models.ObjectWrapper.Relation>()
        whenever(mockRelation.id).thenReturn("existing-rel-id")
        whenever(storeOfRelations.getByKey(any())).thenReturn(mockRelation)

        val result = bootstrapper.bootstrap(testSpace)

        assertTrue(result.isIdempotent)
        assertEquals(0, result.typesCreated.size)
        assertEquals(0, result.relationsCreated.size)
        verify(graphService, never()).createObjectType(any(), any(), any(), any())
        verify(graphService, never()).createRelation(any(), any(), any(), any())
    }

    @Test
    fun `all custom type keys use ot-pkm- prefix`() = runTest {
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
        whenever(graphService.createObjectType(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-id"))
        whenever(graphService.createRelation(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-rel-id"))

        bootstrapper.bootstrap(testSpace)

        // Capture all createObjectType calls and verify uniqueKey prefix
        val captor = org.mockito.kotlin.argumentCaptor<String>()
        verify(graphService, times(14)).createObjectType(any(), any(), captor.capture(), any())
        captor.allValues.forEach { uniqueKey ->
            assertTrue(
                "Created type key '$uniqueKey' must use ot-pkm- prefix",
                uniqueKey.startsWith("ot-pkm-")
            )
        }
    }

    @Test
    fun `all custom relation keys use pkm- prefix`() = runTest {
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
        whenever(graphService.createObjectType(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-id"))
        whenever(graphService.createRelation(any(), any(), any(), any()))
            .thenReturn(PebbleObjectResult(objectId = "new-rel-id"))

        bootstrapper.bootstrap(testSpace)

        val captor = org.mockito.kotlin.argumentCaptor<String>()
        verify(graphService, times(30)).createRelation(any(), any(), captor.capture(), any())
        captor.allValues.forEach { key ->
            assertTrue(
                "Created relation key '$key' must use pkm- prefix",
                key.startsWith("pkm-")
            )
        }
    }
}
