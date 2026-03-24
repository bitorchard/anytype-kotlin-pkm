package com.anytypeio.anytype.pebble.core.impl

import com.anytypeio.anytype.core_models.primitives.SpaceId
import com.anytypeio.anytype.domain.base.AppCoroutineDispatchers
import com.anytypeio.anytype.domain.block.repo.BlockRepository
import com.anytypeio.anytype.pebble.core.PebbleObject
import com.anytypeio.anytype.pebble.core.PebbleSearchFilter
import com.anytypeio.anytype.pebble.core.PebbleSearchResult
import com.anytypeio.anytype.pebble.core.PebbleSearchService
import com.anytypeio.anytype.pebble.core.PebbleSearchSort
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "Pebble:Core"

/**
 * Default [PebbleSearchService] implementation that delegates to [BlockRepository.searchObjects].
 */
class DefaultPebbleSearchService @Inject constructor(
    private val repo: BlockRepository,
    private val dispatchers: AppCoroutineDispatchers
) : PebbleSearchService {

    override suspend fun searchObjects(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        sorts: List<PebbleSearchSort>,
        fulltext: String,
        keys: List<String>,
        offset: Int,
        limit: Int
    ): List<PebbleObject> = withContext(dispatchers.io) {
        repo.searchObjects(
            space = space,
            filters = PebbleFilterMapper.toDVFilters(filters),
            sorts = PebbleFilterMapper.toDVSorts(sorts),
            fulltext = fulltext,
            offset = offset,
            limit = limit,
            keys = keys
        ).map { PebbleObjectMapper.fromStruct(it) }
    }

    override suspend fun searchWithMeta(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        fulltext: String,
        keys: List<String>,
        limit: Int
    ): List<PebbleSearchResult> {
        // Delegates to the basic search for Phase 0; Phase 4 will upgrade this to
        // repo.searchObjectWithMeta() with highlight extraction.
        return searchObjects(
            space = space,
            filters = filters,
            fulltext = fulltext,
            keys = keys,
            limit = limit
        ).map { PebbleSearchResult(obj = it) }
    }
}
