package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey

/**
 * Simple fake [PebbleGraphService] for pebble-core unit tests.
 *
 * Avoids Mockito/coroutine/inline-value-class incompatibility: the `@JvmInline` SpaceId
 * parameter is unboxed to String at the JVM call site, causing `eq(SpaceId(...))` matchers
 * to mismatch the actual String argument.
 */
class FakePebbleGraphService : PebbleGraphService {

    data class CreateObjectTypeCall(
        val space: SpaceId,
        val name: String,
        val uniqueKey: String,
        val layout: Int
    )

    data class CreateRelationCall(
        val space: SpaceId,
        val name: String,
        val uniqueKey: String,
        val format: Int
    )

    val createObjectTypeCalls: MutableList<CreateObjectTypeCall> = mutableListOf()
    val createRelationCalls: MutableList<CreateRelationCall> = mutableListOf()
    val createObjectCalls: MutableList<Triple<SpaceId, TypeKey, Map<String, Any?>>> = mutableListOf()

    var createObjectTypeResult: (CreateObjectTypeCall) -> PebbleObjectResult =
        { call -> PebbleObjectResult(objectId = "fake-type-${call.uniqueKey}") }

    var createRelationResult: (CreateRelationCall) -> PebbleObjectResult =
        { call -> PebbleObjectResult(objectId = "fake-rel-${call.uniqueKey}") }

    val getObjectResults: MutableMap<String, PebbleObject?> = mutableMapOf()

    override suspend fun createObjectType(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        layout: Int
    ): PebbleObjectResult {
        val call = CreateObjectTypeCall(space, name, uniqueKey, layout)
        createObjectTypeCalls.add(call)
        return createObjectTypeResult(call)
    }

    override suspend fun createRelation(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        format: Int
    ): PebbleObjectResult {
        val call = CreateRelationCall(space, name, uniqueKey, format)
        createRelationCalls.add(call)
        return createRelationResult(call)
    }

    override suspend fun createObject(
        space: SpaceId,
        typeKey: TypeKey,
        details: Map<String, Any?>
    ): PebbleObjectResult {
        createObjectCalls.add(Triple(space, typeKey, details))
        return PebbleObjectResult(objectId = "fake-obj-${createObjectCalls.size}")
    }

    override suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>) {}

    override suspend fun addRelationToObject(objectId: Id, relationKey: String) {}

    override suspend fun setRelationValue(objectId: Id, relationKey: String, value: Any?) {}

    override suspend fun deleteObjects(objectIds: List<Id>) {}

    override suspend fun getObject(
        space: SpaceId,
        objectId: Id,
        keys: List<String>
    ): PebbleObject? = getObjectResults[objectId]
}
