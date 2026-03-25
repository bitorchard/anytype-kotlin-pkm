package com.anytypeio.anytype.pebble.core.observability

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PipelineEventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PipelineObservabilityDatabase : RoomDatabase() {
    abstract fun pipelineEventDao(): PipelineEventDao
}
