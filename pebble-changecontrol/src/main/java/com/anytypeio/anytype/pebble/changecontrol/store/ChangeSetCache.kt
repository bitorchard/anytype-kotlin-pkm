package com.anytypeio.anytype.pebble.changecontrol.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anytypeio.anytype.pebble.changecontrol.model.ChangeSetStatus

/**
 * Room entity that caches the summary fields of a [com.anytypeio.anytype.pebble.changecontrol.model.ChangeSet].
 *
 * Full operation data lives in AnyType objects; this cache is optimised for fast list
 * queries in the UI without middleware round-trips.
 */
@Entity(tableName = "change_sets")
data class ChangeSetCache(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "input_id") val inputId: String,
    @ColumnInfo(name = "trace_id") val traceId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "applied_at") val appliedAt: Long?,
    @ColumnInfo(name = "rolled_back_at") val rolledBackAt: Long?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "operation_count") val operationCount: Int,
    /** JSON-serialised disambiguation choices (opaque; typed by pebble-assimilation). */
    @ColumnInfo(name = "disambiguation_choices_json", defaultValue = "")
    val disambiguationChoicesJson: String = ""
)

/**
 * Room entity for cached [com.anytypeio.anytype.pebble.changecontrol.model.ChangeOperation] summaries.
 */
@Entity(tableName = "change_operations")
data class ChangeOperationCache(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "change_set_id") val changeSetId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "params_json") val paramsJson: String,
    @ColumnInfo(name = "inverse_json") val inverseJson: String?,
    @ColumnInfo(name = "before_state_json") val beforeStateJson: String?,
    @ColumnInfo(name = "after_state_json") val afterStateJson: String?,
    @ColumnInfo(name = "result_object_id") val resultObjectId: String?
)
