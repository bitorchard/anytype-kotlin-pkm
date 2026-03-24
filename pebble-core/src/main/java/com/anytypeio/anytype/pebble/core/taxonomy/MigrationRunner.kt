package com.anytypeio.anytype.pebble.core.taxonomy

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.pebble.core.PebbleGraphService
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Core"

/**
 * Applies pending [TaxonomyMigration] steps to bring a space up to [TaxonomyVersion.CURRENT].
 *
 * On space open:
 * 1. Read the current taxonomy version from the sentinel object (or assume version 0).
 * 2. Determine which migrations are pending.
 * 3. Execute each migration's steps via [PebbleGraphService].
 * 4. Update the sentinel object with the new version.
 *
 * All migrations are idempotent. Re-running is safe.
 */
class MigrationRunner @Inject constructor(
    private val graphService: PebbleGraphService,
    private val searchService: PebbleSearchService,
    private val bootstrapper: SchemaBootstrapper
) {

    /**
     * Ensures the space is at [TaxonomyVersion.CURRENT].
     *
     * For a fresh space this runs the full bootstrap. For an existing space that is
     * already current, this is a no-op (beyond the version check).
     */
    suspend fun ensureUpToDate(space: SpaceId) {
        val storedVersion = readStoredVersion(space)
        Timber.tag(TAG).d("Space $space: stored taxonomy version = $storedVersion, current = ${TaxonomyVersion.CURRENT}")

        if (storedVersion >= TaxonomyVersion.CURRENT) {
            Timber.tag(TAG).d("Taxonomy is up to date — no migrations needed")
            return
        }

        val pending = TaxonomyMigrations.pending(storedVersion)
        if (pending.isEmpty()) {
            Timber.tag(TAG).d("No pending migrations for version $storedVersion")
            writeVersion(space, TaxonomyVersion.CURRENT)
            return
        }

        for (migration in pending) {
            Timber.tag(TAG).d("Running migration ${migration.fromVersion} → ${migration.toVersion}: ${migration.description}")
            applyMigration(space, migration)
            Timber.tag(TAG).d("Migration ${migration.toVersion} complete")
        }

        writeVersion(space, TaxonomyVersion.CURRENT)
        Timber.tag(TAG).d("Taxonomy migrations complete. Space is now at version ${TaxonomyVersion.CURRENT}")
    }

    private suspend fun applyMigration(space: SpaceId, migration: TaxonomyMigration) {
        for (step in migration.steps) {
            when (step) {
                is MigrationStep.CreateType -> {
                    graphService.createObjectType(
                        space = space,
                        name = step.type.displayName,
                        uniqueKey = step.type.uniqueKey,
                        layout = step.type.layout.code
                    )
                }
                is MigrationStep.CreateRelation -> {
                    graphService.createRelation(
                        space = space,
                        name = step.relation.displayName,
                        uniqueKey = step.relation.key,
                        format = step.relation.format.code
                    )
                }
                is MigrationStep.AddRelationToType -> {
                    // The type must already exist; look it up by unique key.
                    val typeObjects = searchService.searchObjects(
                        space = space,
                        filters = listOf(
                            com.anytypeio.anytype.pebble.core.PebbleSearchFilter(
                                relationKey = "uniqueKey",
                                condition = com.anytypeio.anytype.pebble.core.PebbleFilterCondition.EQUAL,
                                value = step.typeUniqueKey
                            )
                        ),
                        limit = 1
                    )
                    val typeId = typeObjects.firstOrNull()?.id
                    if (typeId != null) {
                        graphService.addRelationToObject(typeId, step.relation.key)
                    } else {
                        Timber.tag(TAG).w("AddRelationToType: type ${step.typeUniqueKey} not found in space — skipping")
                    }
                }
            }
        }
    }

    /** Reads the stored taxonomy version from the sentinel object, or 0 if not set. */
    private suspend fun readStoredVersion(space: SpaceId): Int {
        return runCatching {
            val results = searchService.searchObjects(
                space = space,
                filters = listOf(
                    com.anytypeio.anytype.pebble.core.PebbleSearchFilter(
                        relationKey = "uniqueKey",
                        condition = com.anytypeio.anytype.pebble.core.PebbleFilterCondition.EQUAL,
                        value = TaxonomyVersion.SENTINEL_KEY
                    )
                ),
                limit = 1
            )
            results.firstOrNull()
                ?.details
                ?.get(TaxonomyVersion.SENTINEL_VERSION_DETAIL)
                ?.toString()
                ?.toIntOrNull()
                ?: 0
        }.getOrElse { e ->
            Timber.tag(TAG).w(e, "Could not read taxonomy version sentinel — assuming version 0")
            0
        }
    }

    /** Writes the current version to the sentinel object, creating it if needed. */
    private suspend fun writeVersion(space: SpaceId, version: Int) {
        runCatching {
            val existing = searchService.searchObjects(
                space = space,
                filters = listOf(
                    com.anytypeio.anytype.pebble.core.PebbleSearchFilter(
                        relationKey = "uniqueKey",
                        condition = com.anytypeio.anytype.pebble.core.PebbleFilterCondition.EQUAL,
                        value = TaxonomyVersion.SENTINEL_KEY
                    )
                ),
                limit = 1
            ).firstOrNull()

            if (existing != null) {
                graphService.updateObjectDetails(
                    objectId = existing.id,
                    details = mapOf(TaxonomyVersion.SENTINEL_VERSION_DETAIL to version.toString())
                )
            } else {
                graphService.createObject(
                    space = space,
                    typeKey = com.anytypeio.anytype.core_models.primitives.TypeKey(TaxonomyVersion.SENTINEL_KEY),
                    details = mapOf(
                        "name" to "PKM Taxonomy Version",
                        TaxonomyVersion.SENTINEL_VERSION_DETAIL to version.toString()
                    )
                )
            }
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to write taxonomy version sentinel")
        }
    }
}
