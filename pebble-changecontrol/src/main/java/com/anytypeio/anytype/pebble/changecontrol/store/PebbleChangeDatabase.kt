package com.anytypeio.anytype.pebble.changecontrol.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ChangeSetCache::class, ChangeOperationCache::class],
    version = 2,
    exportSchema = false
)
abstract class PebbleChangeDatabase : RoomDatabase() {
    abstract fun changeSetDao(): ChangeSetDao
}
