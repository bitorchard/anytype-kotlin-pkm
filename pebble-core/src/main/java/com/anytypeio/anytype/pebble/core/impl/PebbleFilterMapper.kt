package com.anytypeio.anytype.pebble.core.impl

import com.anytypeio.anytype.core_models.Block
import com.anytypeio.anytype.core_models.DVFilter
import com.anytypeio.anytype.core_models.DVSort
import com.anytypeio.anytype.core_models.Relation
import com.anytypeio.anytype.core_models.RelationFormat
import com.anytypeio.anytype.pebble.core.PebbleFilterCondition
import com.anytypeio.anytype.pebble.core.PebbleSearchFilter
import com.anytypeio.anytype.pebble.core.PebbleSearchSort
import com.anytypeio.anytype.pebble.core.PebblesortType

/**
 * Maps Pebble filter/sort models to AnyType's native [DVFilter]/[DVSort] types.
 */
object PebbleFilterMapper {

    fun toDVFilter(filter: PebbleSearchFilter): DVFilter = DVFilter(
        relation = filter.relationKey,
        condition = toDVCondition(filter.condition),
        value = filter.value
    )

    fun toDVSort(
        sort: PebbleSearchSort,
        relationFormat: RelationFormat = Relation.Format.SHORT_TEXT
    ): DVSort = DVSort(
        relationKey = sort.relationKey,
        type = if (sort.type == PebblesortType.ASC) {
            Block.Content.DataView.Sort.Type.ASC
        } else {
            Block.Content.DataView.Sort.Type.DESC
        },
        relationFormat = relationFormat
    )

    fun toDVFilters(filters: List<PebbleSearchFilter>): List<DVFilter> =
        filters.map(::toDVFilter)

    fun toDVSorts(sorts: List<PebbleSearchSort>): List<DVSort> =
        sorts.map { toDVSort(it) }

    private fun toDVCondition(
        condition: PebbleFilterCondition
    ): Block.Content.DataView.Filter.Condition = when (condition) {
        PebbleFilterCondition.EQUAL -> Block.Content.DataView.Filter.Condition.EQUAL
        PebbleFilterCondition.NOT_EQUAL -> Block.Content.DataView.Filter.Condition.NOT_EQUAL
        PebbleFilterCondition.GREATER -> Block.Content.DataView.Filter.Condition.GREATER
        PebbleFilterCondition.LESS -> Block.Content.DataView.Filter.Condition.LESS
        PebbleFilterCondition.GREATER_OR_EQUAL -> Block.Content.DataView.Filter.Condition.GREATER_OR_EQUAL
        PebbleFilterCondition.LESS_OR_EQUAL -> Block.Content.DataView.Filter.Condition.LESS_OR_EQUAL
        PebbleFilterCondition.LIKE -> Block.Content.DataView.Filter.Condition.LIKE
        PebbleFilterCondition.NOT_LIKE -> Block.Content.DataView.Filter.Condition.NOT_LIKE
        PebbleFilterCondition.IN -> Block.Content.DataView.Filter.Condition.IN
        PebbleFilterCondition.NOT_IN -> Block.Content.DataView.Filter.Condition.NOT_IN
        PebbleFilterCondition.EMPTY -> Block.Content.DataView.Filter.Condition.EMPTY
        PebbleFilterCondition.NOT_EMPTY -> Block.Content.DataView.Filter.Condition.NOT_EMPTY
        PebbleFilterCondition.EXISTS -> Block.Content.DataView.Filter.Condition.EXISTS
    }
}
