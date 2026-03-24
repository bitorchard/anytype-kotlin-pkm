package com.anytypeio.anytype.pebble.webhook.queue

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [InputQueueEntity::class],
    version = 1,
    exportSchema = false
)
abstract class InputQueueDatabase : RoomDatabase() {
    abstract fun inputQueueDao(): InputQueueDao
}
