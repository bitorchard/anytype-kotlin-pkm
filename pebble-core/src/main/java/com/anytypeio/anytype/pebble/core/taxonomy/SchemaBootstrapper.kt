package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Core"

/**
 * Ensures all custom PKM types and relations exist in a space.
 *
 * This is the low-level bootstrapper. Call [bootstrap] once per space open, typically
 * from [MigrationRunner]. The operation is idempotent: if a type or relation already
 * exists in the space (matched by unique key), the AnyType middleware returns the
 * existing ID without creating a duplicate.
 */
class SchemaBootstrapper @Inject constructor(
    private val graphService: PebbleGraphService,
    private val taxonomyProvider: TaxonomyProvider
) {

    /**
     * Creates all missing custom types and relations in [space].
     *
     * @return [BootstrapResult] summarising what was created vs already existed.
     */
    suspend fun bootstrap(space: SpaceId): BootstrapResult {
        val typesCreated = mutableListOf<String>()
        val typesExisted = mutableListOf<String>()
        val relationsCreated = mutableListOf<String>()
        val relationsExisted = mutableListOf<String>()

        val effectiveTypes = taxonomyProvider.effectiveTypes()
        for (effective in effectiveTypes) {
            if (effective.definition.isBuiltIn) continue
            if (effective.exists) {
                typesExisted += effective.definition.uniqueKey
                Timber.tag(TAG).d("Type already exists: ${effective.definition.uniqueKey}")
            } else {
                runCatching {
                    graphService.createObjectType(
                        space = space,
                        name = effective.definition.displayName,
                        uniqueKey = effective.definition.uniqueKey,
                        layout = effective.definition.layout.code
                    )
                }.onSuccess {
                    typesCreated += effective.definition.uniqueKey
                    Timber.tag(TAG).d("Created type: ${effective.definition.uniqueKey}")
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to create type: ${effective.definition.uniqueKey}")
                    throw BootstrapException("Failed to create type ${effective.definition.uniqueKey}", e)
                }
            }
        }

        val effectiveRelations = taxonomyProvider.effectiveRelations()
        for (effective in effectiveRelations) {
            if (effective.definition.isBuiltIn) continue
            if (effective.exists) {
                relationsExisted += effective.definition.key
                Timber.tag(TAG).d("Relation already exists: ${effective.definition.key}")
            } else {
                runCatching {
                    graphService.createRelation(
                        space = space,
                        name = effective.definition.displayName,
                        uniqueKey = effective.definition.key,
                        format = effective.definition.format.code
                    )
                }.onSuccess {
                    relationsCreated += effective.definition.key
                    Timber.tag(TAG).d("Created relation: ${effective.definition.key}")
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to create relation: ${effective.definition.key}")
                    throw BootstrapException("Failed to create relation ${effective.definition.key}", e)
                }
            }
        }

        return BootstrapResult(
            typesCreated = typesCreated,
            typesExisted = typesExisted,
            relationsCreated = relationsCreated,
            relationsExisted = relationsExisted
        )
    }
}

data class BootstrapResult(
    val typesCreated: List<String>,
    val typesExisted: List<String>,
    val relationsCreated: List<String>,
    val relationsExisted: List<String>
) {
    val isFullyFresh: Boolean get() = typesExisted.isEmpty() && relationsExisted.isEmpty()
    val isIdempotent: Boolean get() = typesCreated.isEmpty() && relationsCreated.isEmpty()
}

class BootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)
