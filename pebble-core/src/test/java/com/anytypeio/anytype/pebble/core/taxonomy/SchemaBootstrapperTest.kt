package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.FakePebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleObjectResult
import com.anytypeio.anytype.domain.objects.StoreOfObjectTypes
import com.anytypeio.anytype.domain.objects.StoreOfRelations
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
 * Unit tests for [SchemaBootstrapper].
 *
 * Uses [FakePebbleGraphService] instead of a Mockito mock to avoid the
 * `@JvmInline` value-class / Mockito matcher incompatibility: the SpaceId
 * parameter is unboxed to String at the JVM call site, causing `eq(SpaceId(...))`
 * matchers to mismatch the actual String argument.
 */
class SchemaBootstrapperTest {

    private val graphService = FakePebbleGraphService()
    private val storeOfObjectTypes: StoreOfObjectTypes = mock()
    private val storeOfRelations: StoreOfRelations = mock()
    private val taxonomyProvider = TaxonomyProvider(storeOfObjectTypes, storeOfRelations)
    private val bootstrapper = SchemaBootstrapper(graphService, taxonomyProvider)

    private val testSpace = SpaceId("test-space-id")

    @Before
    fun setup() {
        graphService.createObjectTypeCalls.clear()
        graphService.createRelationCalls.clear()
    }

    private suspend fun stubFreshSpace() {
        whenever(storeOfObjectTypes.getByKey(any())).thenReturn(null)
        whenever(storeOfRelations.getByKey(any())).thenReturn(null)
    }

    @Test
    fun `bootstrap creates all 14 custom types on fresh space`() = runTest {
        stubFreshSpace()

        val result = bootstrapper.bootstrap(testSpace)

        assertEquals(14, result.typesCreated.size)
        assertEquals(0, result.typesExisted.size)
        assertEquals(14, graphService.createObjectTypeCalls.size)
        assertTrue(graphService.createObjectTypeCalls.all { it.space == testSpace })
    }

    @Test
    fun `bootstrap creates all 30 custom relations on fresh space`() = runTest {
        stubFreshSpace()

        val result = bootstrapper.bootstrap(testSpace)

        assertEquals(30, result.relationsCreated.size)
        assertEquals(0, result.relationsExisted.size)
        assertEquals(30, graphService.createRelationCalls.size)
        assertTrue(graphService.createRelationCalls.all { it.space == testSpace })
    }

    @Test
    fun `bootstrap does not call createObjectType for built-in types`() = runTest {
        stubFreshSpace()

        bootstrapper.bootstrap(testSpace)

        val createdKeys = graphService.createObjectTypeCalls.map { it.uniqueKey }.toSet()
        PkmObjectType.builtIn().forEach { builtInType ->
            assertTrue(
                "Built-in type '${builtInType.uniqueKey}' should not be created",
                builtInType.uniqueKey !in createdKeys
            )
        }
    }

    @Test
    fun `bootstrap is idempotent - does not create already existing types`() = runTest {
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
        assertEquals(0, graphService.createObjectTypeCalls.size)
        assertEquals(0, graphService.createRelationCalls.size)
    }

    @Test
    fun `all custom type keys use ot-pkm- prefix`() = runTest {
        stubFreshSpace()

        bootstrapper.bootstrap(testSpace)

        graphService.createObjectTypeCalls.forEach { call ->
            assertTrue(
                "Created type key '${call.uniqueKey}' must use ot-pkm- prefix",
                call.uniqueKey.startsWith("ot-pkm-")
            )
        }
    }

    @Test
    fun `all custom relation keys use pkm- prefix`() = runTest {
        stubFreshSpace()

        bootstrapper.bootstrap(testSpace)

        graphService.createRelationCalls.forEach { call ->
            assertTrue(
                "Created relation key '${call.uniqueKey}' must use pkm- prefix",
                call.uniqueKey.startsWith("pkm-")
            )
        }
    }
}
