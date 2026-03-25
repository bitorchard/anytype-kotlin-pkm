package com.anytypeio.anytype.pebble.assimilation.resolution

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

// ── Room entity ──────────────────────────────────────────────────────────────

@Entity(tableName = "resolution_feedback")
data class ResolutionFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityName: String,
    val entityTypeKey: String,
    val resolvedObjectId: String,
    val wasCorrect: Boolean,
    val recordedAt: Long = System.currentTimeMillis()
)

// ── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ResolutionFeedbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ResolutionFeedbackEntity)

    @Query(
        """
        SELECT COUNT(*) FROM resolution_feedback
        WHERE entityName = :name
          AND entityTypeKey = :typeKey
          AND resolvedObjectId = :objectId
          AND wasCorrect = 1
        """
    )
    suspend fun countCorrectResolutions(name: String, typeKey: String, objectId: String): Int

    @Query("DELETE FROM resolution_feedback WHERE recordedAt < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long)
}

// ── Database ─────────────────────────────────────────────────────────────────

@Database(entities = [ResolutionFeedbackEntity::class], version = 1, exportSchema = false)
abstract class ResolutionFeedbackDatabase : RoomDatabase() {
    abstract fun feedbackDao(): ResolutionFeedbackDao

    companion object {
        fun create(context: Context): ResolutionFeedbackDatabase =
            Room.databaseBuilder(context, ResolutionFeedbackDatabase::class.java, "pebble_feedback.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

// ── Store ────────────────────────────────────────────────────────────────────

/**
 * Tracks user-confirmed entity resolution decisions.
 *
 * Past correct resolutions provide a frequency signal for [ScoringEngine], so that
 * if the user has resolved "Aarav" → object X three times before, future inputs
 * will strongly prefer X for any "Aarav" entity.
 */
@Singleton
class ResolutionFeedbackStore @Inject constructor(private val dao: ResolutionFeedbackDao) {

    /**
     * Record a resolution event.
     *
     * @param entityName     The extracted entity name.
     * @param entityTypeKey  The entity's type key.
     * @param resolvedObjectId  The AnyType object ID the user accepted.
     * @param wasCorrect     True for confirmed resolutions; false for user-rejected ones.
     */
    suspend fun recordResolution(
        entityName: String,
        entityTypeKey: String,
        resolvedObjectId: String,
        wasCorrect: Boolean = true
    ) {
        dao.insert(
            ResolutionFeedbackEntity(
                entityName = entityName.lowercase().trim(),
                entityTypeKey = entityTypeKey,
                resolvedObjectId = resolvedObjectId,
                wasCorrect = wasCorrect
            )
        )
    }

    /**
     * Returns a frequency boost (0.0–1.0) for resolving [entityName] to [candidateId].
     *
     * Formula: `min(count / 3.0, 1.0)` — 3 past correct resolutions saturates the signal.
     */
    suspend fun getFrequencyBoost(
        entityName: String,
        entityTypeKey: String,
        candidateId: String
    ): Float {
        val count = dao.countCorrectResolutions(
            name = entityName.lowercase().trim(),
            typeKey = entityTypeKey,
            objectId = candidateId
        )
        return min(count / 3.0f, 1.0f)
    }

    /**
     * Prune feedback entries older than [ageMs] milliseconds (default: 90 days).
     */
    suspend fun prune(ageMs: Long = 90L * 24 * 60 * 60 * 1000) {
        dao.pruneOlderThan(System.currentTimeMillis() - ageMs)
    }
}
