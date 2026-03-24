package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.Id
import com.anytypeio.anytype.domain.objects.StoreOfObjectTypes
import com.anytypeio.anytype.domain.objects.StoreOfRelations
import javax.inject.Inject

/**
 * Merges the code-defined taxonomy with AnyType runtime state.
 *
 * Use [effectiveTypes] and [effectiveRelations] to determine which schema elements
 * already exist in a space and which need to be created by [SchemaBootstrapper].
 */
class TaxonomyProvider @Inject constructor(
    private val storeOfObjectTypes: StoreOfObjectTypes,
    private val storeOfRelations: StoreOfRelations
) {

    /** All code-defined PKM types (from the sealed hierarchy). */
    val coreTypes: List<PkmObjectType> get() = PkmObjectType.all()

    /** All code-defined PKM relations (from the sealed hierarchy). */
    val coreRelations: List<PkmRelation> get() = PkmRelation.all()

    /**
     * Returns [EffectiveType] for every custom PKM type, indicating whether each
     * type already exists in the space (queried via [StoreOfObjectTypes]).
     * Built-in types always have [EffectiveType.exists] = true since AnyType ships with them.
     */
    suspend fun effectiveTypes(): List<EffectiveType> {
        return coreTypes.map { definition ->
            if (definition.isBuiltIn) {
                val existing = storeOfObjectTypes.getByKey(definition.uniqueKey)
                EffectiveType(
                    definition = definition,
                    anytypeId = existing?.id,
                    exists = true
                )
            } else {
                val existing = storeOfObjectTypes.getByKey(definition.uniqueKey)
                EffectiveType(
                    definition = definition,
                    anytypeId = existing?.id,
                    exists = existing != null
                )
            }
        }
    }

    /**
     * Returns [EffectiveRelation] for every custom PKM relation, indicating whether each
     * relation already exists in the space.
     * Built-in relations always have [EffectiveRelation.exists] = true.
     */
    suspend fun effectiveRelations(): List<EffectiveRelation> {
        return coreRelations.map { definition ->
            if (definition.isBuiltIn) {
                val existing = storeOfRelations.getByKey(definition.key)
                EffectiveRelation(
                    definition = definition,
                    anytypeId = existing?.id,
                    exists = true
                )
            } else {
                val existing = storeOfRelations.getByKey(definition.key)
                EffectiveRelation(
                    definition = definition,
                    anytypeId = existing?.id,
                    exists = existing != null
                )
            }
        }
    }
}

/**
 * Resolved state of a [PkmObjectType] against a live AnyType space.
 *
 * @property definition The code-defined type.
 * @property anytypeId The AnyType object ID for this type, if it exists in the space.
 * @property exists Whether the type already exists (true for built-ins and previously bootstrapped types).
 */
data class EffectiveType(
    val definition: PkmObjectType,
    val anytypeId: Id?,
    val exists: Boolean
)

/**
 * Resolved state of a [PkmRelation] against a live AnyType space.
 *
 * @property definition The code-defined relation.
 * @property anytypeId The AnyType object ID for this relation, if it exists in the space.
 * @property exists Whether the relation already exists.
 */
data class EffectiveRelation(
    val definition: PkmRelation,
    val anytypeId: Id?,
    val exists: Boolean
)
