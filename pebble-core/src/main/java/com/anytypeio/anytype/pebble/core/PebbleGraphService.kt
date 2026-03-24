package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey

/**
 * Facade for all create/read/update/delete operations on the AnyType object graph.
 *
 * Downstream pebble modules depend on this interface rather than AnyType use cases directly,
 * keeping the integration surface minimal and merge-safe.
 */
interface PebbleGraphService {

    /** Create a new object of the given type with pre-filled details. */
    suspend fun createObject(
        space: SpaceId,
        typeKey: TypeKey,
        details: Map<String, Any?>
    ): PebbleObjectResult

    /** Bulk-update relation values on an existing object. */
    suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>)

    /** Add a relation to an object's recommended/featured relations. */
    suspend fun addRelationToObject(objectId: Id, relationKey: String)

    /** Set the value of a single relation on an object. */
    suspend fun setRelationValue(objectId: Id, relationKey: String, value: Any?)

    /** Permanently delete one or more objects. */
    suspend fun deleteObjects(objectIds: List<Id>)

    /**
     * Fetch a single object by ID within a given space.
     * Returns null if the object does not exist or is not accessible.
     *
     * @param keys Optional subset of relation keys to include in [PebbleObject.details];
     *             empty list means "all keys".
     */
    suspend fun getObject(
        space: SpaceId,
        objectId: Id,
        keys: List<String> = emptyList()
    ): PebbleObject?

    /**
     * Ensure a custom object type exists in [space].
     * If the type already exists (matched by [uniqueKey]) the middleware returns its ID.
     *
     * @param layout AnyType layout code (e.g. 0 = BASIC, 11 = TODO, 15 = NOTE).
     */
    suspend fun createObjectType(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        layout: Int
    ): PebbleObjectResult

    /**
     * Ensure a custom relation exists in [space].
     *
     * @param format AnyType relation format code (e.g. 0 = SHORT_TEXT, 8 = DATE).
     */
    suspend fun createRelation(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        format: Int
    ): PebbleObjectResult
}
