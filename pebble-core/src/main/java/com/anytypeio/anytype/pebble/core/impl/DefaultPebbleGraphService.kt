package com.anytypeio.anytype.pebble.core.impl

import com.anytypeio.anytype.core_models.Block
import com.anytypeio.anytype.core_models.Command
import com.anytypeio.anytype.core_models.DVFilter
import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.core_models.Relation
import com.anytypeio.anytype.core_models.Relations
import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.core_models.primitives.TypeKey
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.base.getOrThrow
import com.anytypeio.anytype.domain.block.repo.BlockRepository
import com.anytypeio.anytype.domain.objects.DeleteObjects
import com.anytypeio.anytype.domain.`object`.SetObjectDetails
import com.anytypeio.anytype.domain.page.CreateObject
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleObjectResult
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Core"

/**
 * Default [PebbleGraphService] implementation that delegates CRUD operations to AnyType use
 * cases and the [BlockRepository].
 *
 * Object creation and details/deletion are handled via the existing use-case layer;
 * lower-level operations (type/relation creation, relation assignment) are called directly
 * on [BlockRepository] since no purpose-built use cases exist for them yet.
 */
class DefaultPebbleGraphService @Inject constructor(
    private val repo: BlockRepository,
    private val createObject: CreateObject,
    private val setObjectDetails: SetObjectDetails,
    private val deleteObjects: DeleteObjects,
    private val dispatchers: AppCoroutineDispatchers
) : PebbleGraphService {

    override suspend fun createObject(
        space: SpaceId,
        typeKey: TypeKey,
        details: Map<String, Any?>
    ): PebbleObjectResult {
        val params = CreateObject.Param(
            space = space,
            type = typeKey,
            prefilled = details,
            internalFlags = emptyList()
        )
        val result = createObject.async(params).getOrThrow()
        Timber.tag(TAG).d("createObject id=${result.objectId} typeKey=${result.typeKey}")
        return PebbleObjectResult(objectId = result.objectId, typeKey = result.typeKey.key)
    }

    override suspend fun updateObjectDetails(objectId: Id, details: Map<String, Any?>) {
        setObjectDetails.async(SetObjectDetails.Params(ctx = objectId, details = details))
            .getOrThrow()
    }

    override suspend fun addRelationToObject(objectId: Id, relationKey: String) {
        withContext(dispatchers.io) {
            repo.addRelationToObject(ctx = objectId, relation = relationKey)
        }
    }

    override suspend fun setRelationValue(objectId: Id, relationKey: String, value: Any?) {
        setObjectDetails.async(
            SetObjectDetails.Params(ctx = objectId, details = mapOf(relationKey to value))
        ).getOrThrow()
    }

    override suspend fun deleteObjects(objectIds: List<Id>) {
        deleteObjects.async(DeleteObjects.Params(targets = objectIds)).getOrThrow()
    }

    override suspend fun getObject(
        space: SpaceId,
        objectId: Id,
        keys: List<String>
    ): PebbleObject? {
        val filter = DVFilter(
            relation = Relations.ID,
            condition = Block.Content.DataView.Filter.Condition.EQUAL,
            value = objectId
        )
        val structs = withContext(dispatchers.io) {
            repo.searchObjects(
                space = space,
                filters = listOf(filter),
                keys = keys.ifEmpty { emptyList() },
                limit = 1
            )
        }
        return structs.firstOrNull()?.let { PebbleObjectMapper.fromStruct(it) }
    }

    override suspend fun createObjectType(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        layout: Int
    ): PebbleObjectResult {
        val command = Command.CreateObjectType(
            details = mapOf(
                Relations.NAME to name,
                Relations.UNIQUE_KEY to uniqueKey,
                Relations.LEGACY_LAYOUT to layout.toDouble()
            ),
            spaceId = space.id,
            internalFlags = emptyList()
        )
        val objectId = withContext(dispatchers.io) { repo.createType(command) }
        Timber.tag(TAG).d("createObjectType name=$name uniqueKey=$uniqueKey id=$objectId")
        return PebbleObjectResult(objectId = objectId, typeKey = uniqueKey)
    }

    override suspend fun createRelation(
        space: SpaceId,
        name: String,
        uniqueKey: String,
        format: Int
    ): PebbleObjectResult {
        val relationFormat = Relation.Format.entries.find { it.code == format }
            ?: Relation.Format.SHORT_TEXT
        val wrapper = withContext(dispatchers.io) {
            repo.createRelation(
                space = space.id,
                name = name,
                format = relationFormat,
                formatObjectTypes = emptyList(),
                prefilled = mapOf(Relations.UNIQUE_KEY to uniqueKey)
            )
        }
        val objectId = wrapper.map[Relations.ID] as? String ?: ""
        Timber.tag(TAG).d("createRelation name=$name uniqueKey=$uniqueKey id=$objectId")
        return PebbleObjectResult(objectId = objectId, typeKey = null)
    }
}
