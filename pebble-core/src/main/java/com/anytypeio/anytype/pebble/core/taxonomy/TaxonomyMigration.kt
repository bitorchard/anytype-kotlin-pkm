package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.PebbleGraphService

/**
 * Describes a single versioned migration step.
 *
 * Each step specifies which version it upgrades FROM (must be [fromVersion]) and
 * applies a set of changes to [PebbleGraphService].
 *
 * Migrations are additive-only: types and relations are never renamed or removed.
 */
data class TaxonomyMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val description: String,
    val steps: List<MigrationStep>
)

/**
 * A single change within a [TaxonomyMigration].
 */
sealed class MigrationStep {
    data class CreateType(val type: PkmObjectType) : MigrationStep()
    data class CreateRelation(val relation: PkmRelation) : MigrationStep()
    data class AddRelationToType(val typeUniqueKey: String, val relation: PkmRelation) : MigrationStep()
}

/**
 * Registry of all taxonomy migrations in version order.
 *
 * Add new [TaxonomyMigration] entries here for each schema version bump.
 */
object TaxonomyMigrations {

    /**
     * Migration 0 → 1: initial taxonomy bootstrap.
     * Creates all 14 custom types and 30 custom relations.
     */
    private val v0ToV1 = TaxonomyMigration(
        fromVersion = 0,
        toVersion = 1,
        description = "Initial PKM taxonomy: 14 custom types, 30 custom relations",
        steps = buildList {
            PkmObjectType.custom().forEach { add(MigrationStep.CreateType(it)) }
            PkmRelation.custom().forEach { add(MigrationStep.CreateRelation(it)) }
        }
    )

    val all: List<TaxonomyMigration> = listOf(v0ToV1)

    /** Returns migrations needed to advance from [currentVersion] to [TaxonomyVersion.CURRENT]. */
    fun pending(currentVersion: Int): List<TaxonomyMigration> =
        all.filter { it.fromVersion >= currentVersion }
            .sortedBy { it.fromVersion }
}
