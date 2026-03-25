package com.anytypeio.anytype.pebble.changecontrol

import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleObjectResult

/**
 * Simple fake [PebbleGraphService] for unit tests.
 *
 * Using a hand-written fake instead of Mockito avoids the Mockito/coroutine/
 * inline-value-class incompatibility: Mockito's `any()` matcher returns null
 * and can cause NPE when unboxing `@JvmInline value class` parameters inside
 * suspend functions.
 */
class FakePebbleGraphService : PebbleGraphService {

    /** Queue of results returned by successive [createObject] calls. */
    val createObjectResults: ArrayDeque<() -> PebbleObjectResult> = ArrayDeque()

    /** Per-ID results returned by [getObject]. */
    val getObjectResults: MutableMap<String, PebbleObject?> = mutableMapOf()

    /** Recorded [createObject] calls: (space, typeKey, details). */
    val createObjectCalls: MutableList<Triple<SpaceId, TypeKey, Map<String, Any?>>> = mutableListOf()

    /** Recorded [updateObjectDetails] calls: (objectId, details). */
    val updateObjectDetailsCalls: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()

    /** Recorded [getObject] calls: (space, objectId, keys). */
    val getObjectCalls: MutableList<Triple<SpaceId, String, List<String>>> = mutableListOf()

    /** Recorded [deleteObjects] call argument lists. */
    val deleteObjectsCalls: MutableList<List<String>> = mutableListOf()

    /** Recorded [addRelationToObject] calls: (objectId, relationKey). */
    val addRelationCalls: MutableList<Pair<String, String>> = mutableListOf()

    /** Recorded [setRelationValue] calls: (objectId, relationKey, value). */
    val setRelationValueCalls: MutableList<Triple<String, String, Any?>> = mutableListOf()

    fun enqueueCreateObject(result: PebbleObjectResult) {
        createObjectResults.addLast { result }
    }

    fun enqueueCreateObjectError(error: Throwable) {
        createObjectResults.addLast { throw error }
    }

    override suspend fun createObject(
        space: SpaceId,
        typeKey: TypeKey,
        details: Map<String, Any?>
    ): PebbleObjectResult {
        createObjectCalls.add(Triple(space, typeKey, details))
        return createObjectResults.removeFirst()()
    }

    override suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>) {
        updateObjectDetailsCalls.add(objectId to details)
    }

    override suspend fun addRelationToObject(objectId: Id, relationKey: String) {
        addRelationCalls.add(objectId to relationKey)
    }

    override suspend fun setRelationValue(objectId: Id, relationKey: String, value: Any?) {
        setRelationValueCalls.add(Triple(objectId, relationKey, value))
    }

    override suspend fun deleteObjects(objectIds: List<Id>) {
        deleteObjectsCalls.add(objectIds)
    }

    override suspend fun getObject(
        space: SpaceId,
        objectId: Id,
        keys: List<String>
    ): PebbleObject? {
        getObjectCalls.add(Triple(space, objectId, keys))
        return getObjectResults[objectId]
    }

    override suspend fun createObjectType(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        layout: Int
    ): PebbleObjectResult = PebbleObjectResult(objectId = "fake-type-$uniqueKey")

    override suspend fun createRelation(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        format: Int
    ): PebbleObjectResult = PebbleObjectResult(objectId = "fake-rel-$uniqueKey")
}
