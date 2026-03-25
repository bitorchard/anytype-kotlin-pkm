package com.anytypeio.anytype.pebble.assimilation.resolution

import com.anytypeio.anytype.pebble.core.PebbleFilterCondition
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleSearchFilter
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import com.anytypeio.anytype.pebble.core.PebbleSpaceId
import com.anytypeio.anytype.pebble.core.taxonomy.PkmObjectType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject

private const val TAG = "Pebble:Assimilation"
private const val CACHE_TTL_MS = 30_000L  // 30-second hot cache

/**
 * Hot candidate cache for entity resolution.
 *
 * Pre-warms a full list of Person-type objects and recent objects in the space,
 * refreshed on a 30-second TTL.  This avoids per-query search latency during
 * multi-entity resolution passes.
 *
 * For less common types, falls back to a live [PebbleSearchService] call.
 */
class EntityCache @Inject constructor(
    private val searchService: PebbleSearchService
) {
    private val mutex = Mutex()
    private val cacheByType = mutableMapOf<String, CacheEntry>()

    data class CacheEntry(
        val objects: List<PebbleObject>,
        val fetchedAt: Long
    )

    /**
     * Returns candidate objects for the given [typeKey] in [space].
     *
     * Person objects are cached aggressively; other types get a TTL-based cache.
     */
    suspend fun getCandidates(space: PebbleSpaceId, typeKey: String): List<PebbleObject> =
        mutex.withLock {
            val entry = cacheByType[typeKey]
            val now = System.currentTimeMillis()
            if (entry != null && now - entry.fetchedAt < CACHE_TTL_MS) {
                return@withLock entry.objects
            }
            val fresh = fetchFromSearch(space, typeKey)
            cacheByType[typeKey] = CacheEntry(fresh, now)
            Timber.tag(TAG).d("[EntityCache] refreshed cache for $typeKey: ${fresh.size} objects")
            fresh
        }

    /**
     * Invalidates the entire cache (e.g. on space switch).
     */
    suspend fun invalidate(): Unit = mutex.withLock { cacheByType.clear() }

    // ── Search ───────────────────────────────────────────────────────────────

    private suspend fun fetchFromSearch(
        space: PebbleSpaceId,
        typeKey: String
    ): List<PebbleObject> = runCatching {
        searchService.searchObjects(
            space = space,
            filters = listOf(
                PebbleSearchFilter(
                    relationKey = "type",
                    condition = PebbleFilterCondition.EQUAL,
                    value = typeKey
                ),
                PebbleSearchFilter(
                    relationKey = "isArchived",
                    condition = PebbleFilterCondition.NOT_EQUAL,
                    value = true
                )
            ),
            keys = listOf("id", "name", "type", "lastModifiedDate"),
            limit = 500
        )
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "[EntityCache] search failed for $typeKey")
        emptyList()
    }
}
