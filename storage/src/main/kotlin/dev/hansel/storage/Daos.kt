package dev.hansel.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {
    @Upsert suspend fun upsert(route: SavedRouteEntity)

    @Query("SELECT * FROM saved_routes ORDER BY lastUsedAtMillis DESC")
    fun observeAll(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun get(id: String): SavedRouteEntity?

    @Query("UPDATE saved_routes SET lastUsedAtMillis = :atMillis, useCount = useCount + 1 WHERE id = :id")
    suspend fun touch(id: String, atMillis: Long)

    @Query("UPDATE saved_routes SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RecentPlaceDao {
    @Upsert suspend fun upsert(place: RecentPlaceEntity)

    @Query("SELECT * FROM recent_places WHERE id = :id")
    suspend fun get(id: String): RecentPlaceEntity?

    @Query("SELECT * FROM recent_places ORDER BY lastUsedAtMillis DESC LIMIT :limit")
    fun observeRecents(limit: Int): Flow<List<RecentPlaceEntity>>

    @Query("SELECT * FROM recent_places WHERE isSaved = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeSaved(): Flow<List<RecentPlaceEntity>>

    @Query("UPDATE recent_places SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query("DELETE FROM recent_places WHERE id = :id")
    suspend fun delete(id: String)

    /** Bounded retention: drop unsaved recents beyond the newest [keep]. */
    @Query(
        "DELETE FROM recent_places WHERE isSaved = 0 AND id NOT IN " +
            "(SELECT id FROM recent_places WHERE isSaved = 0 ORDER BY lastUsedAtMillis DESC LIMIT :keep)",
    )
    suspend fun pruneUnsaved(keep: Int)
}

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startAtMillis DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startAtMillis DESC LIMIT :limit")
    suspend fun list(limit: Int): List<TripEntity>

    @Query("UPDATE trips SET endAtMillis = :endAtMillis, sampleCount = :sampleCount WHERE id = :id")
    suspend fun finish(id: String, endAtMillis: Long, sampleCount: Int)

    @Query("UPDATE trips SET excluded = :excluded WHERE id = :id")
    suspend fun setExcluded(id: String, excluded: Boolean)

    /** Samples cascade via the FK on [GpsSampleEntity.tripId]. */
    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Insert suspend fun insertSamples(samples: List<GpsSampleEntity>)

    @Query("SELECT * FROM gps_samples WHERE tripId = :tripId ORDER BY atMillis ASC")
    suspend fun samplesForTrip(tripId: String): List<GpsSampleEntity>

    @Query("SELECT COUNT(*) FROM gps_samples WHERE tripId = :tripId")
    suspend fun countSamples(tripId: String): Int

    @Query("SELECT COUNT(*) FROM gps_samples")
    fun observeSampleCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM gps_samples")
    suspend fun countSamples(): Long

    /** Bounded raw-trace retention (DESIGN §12): drop the oldest finished trips until at most [keep] remain. */
    @Query(
        "DELETE FROM trips WHERE endAtMillis IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM trips ORDER BY startAtMillis DESC LIMIT :keep)",
    )
    suspend fun pruneOlderThanNewest(keep: Int)

    @Transaction
    suspend fun deleteTrip(id: String) = delete(id)
}

@Dao
interface AbRecordDao {
    @Upsert suspend fun upsert(record: AbRecordEntity)

    @Query("SELECT * FROM ab_records WHERE requestId = :requestId")
    suspend fun get(requestId: String): AbRecordEntity?

    @Query("SELECT * FROM ab_records ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AbRecordEntity>

    @Query("SELECT * FROM ab_records ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AbRecordEntity>>

    @Query("DELETE FROM ab_records")
    suspend fun deleteAll()
}
