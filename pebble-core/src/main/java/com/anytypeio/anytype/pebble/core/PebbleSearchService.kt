package com.anytypeio.anytype.pebble.core

import com.anytypeio.anytype.core_models.primitives.SpaceId

/**
 * Facade for search/query operations on the AnyType object graph.
 */
interface PebbleSearchService {

    suspend fun searchObjects(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        sorts: List<PebbleSearchSort> = emptyList(),
        fulltext: String = "",
        keys: List<String> = emptyList(),
        offset: Int = 0,
        limit: Int = 100
    ): List<PebbleObject>

    suspend fun searchWithMeta(
        space: SpaceId,
        filters: List<PebbleSearchFilter>,
        fulltext: String = "",
        keys: List<String> = emptyList(),
        limit: Int = 100
    ): List<PebbleSearchResult>
}

// ---------------------------------------------------------------------------
// Filter / Sort model
// ---------------------------------------------------------------------------

data class PebbleSearchFilter(
    val relationKey: String,
    val condition: PebbleFilterCondition,
    val value: Any? = null
)

enum class PebbleFilterCondition {
    EQUAL,
    NOT_EQUAL,
    GREATER,
    LESS,
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL,
    LIKE,
    NOT_LIKE,
    IN,
    NOT_IN,
    EMPTY,
    NOT_EMPTY,
    EXISTS
}

data class PebbleSearchSort(
    val relationKey: String,
    val type: PebblesortType = PebblesortType.ASC
)

enum class PebblesortType { ASC, DESC }
