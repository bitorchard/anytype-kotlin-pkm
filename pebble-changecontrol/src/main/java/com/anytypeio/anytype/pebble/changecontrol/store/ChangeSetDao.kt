package com.anytypeio.anytype.pebble.changecontrol.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChangeSetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangeSet(changeSet: ChangeSetCache)

    @Update
    suspend fun updateChangeSet(changeSet: ChangeSetCache)

    @Query("UPDATE change_sets SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE change_sets SET applied_at = :appliedAt WHERE id = :id")
    suspend fun updateAppliedAt(id: String, appliedAt: Long)

    @Query("UPDATE change_sets SET rolled_back_at = :rolledBackAt WHERE id = :id")
    suspend fun updateRolledBackAt(id: String, rolledBackAt: Long)

    @Query("UPDATE change_sets SET error_message = :error WHERE id = :id")
    suspend fun updateErrorMessage(id: String, error: String?)

    @Query("SELECT * FROM change_sets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChangeSetCache?

    @Query("SELECT * FROM change_sets WHERE input_id = :inputId LIMIT 1")
    suspend fun getByInputId(inputId: String): ChangeSetCache?

    @Query("SELECT * FROM change_sets ORDER BY created_at DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<ChangeSetCache>

    @Query("SELECT * FROM change_sets WHERE status = :status ORDER BY created_at DESC LIMIT :limit")
    suspend fun getByStatus(status: String, limit: Int): List<ChangeSetCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: ChangeOperationCache)

    @Query("UPDATE change_operations SET status = :status, before_state_json = :beforeJson, after_state_json = :afterJson, inverse_json = :inverseJson, result_object_id = :resultObjectId WHERE id = :id")
    suspend fun updateOperation(
        id: String,
        status: String,
        beforeJson: String?,
        afterJson: String?,
        inverseJson: String?,
        resultObjectId: String?
    )

    @Query("SELECT * FROM change_operations WHERE change_set_id = :changeSetId ORDER BY ordinal ASC")
    suspend fun getOperationsForChangeSet(changeSetId: String): List<ChangeOperationCache>

    @Query("SELECT * FROM change_operations WHERE id = :id LIMIT 1")
    suspend fun getOperationById(id: String): ChangeOperationCache?
}
