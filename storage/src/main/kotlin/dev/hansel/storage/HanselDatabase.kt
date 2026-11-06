package dev.hansel.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.json.Json

/**
 * Single on-device database. Not yet encrypted at rest beyond Android file-based encryption; see
 * `storage/EncryptionNote.md` for the DESIGN §13 plan and the Phase 5 gate.
 */
@Database(
    entities = [SavedRouteEntity::class, RecentPlaceEntity::class, TripEntity::class, GpsSampleEntity::class, AbRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HanselDatabase : RoomDatabase() {
    abstract fun savedRoutes(): SavedRouteDao
    abstract fun recentPlaces(): RecentPlaceDao
    abstract fun trips(): TripDao
    abstract fun abRecords(): AbRecordDao

    companion object {
        const val NAME = "hansel.db"

        fun build(context: Context, name: String = NAME): HanselDatabase =
            Room.databaseBuilder(context.applicationContext, HanselDatabase::class.java, name)
                // Version 1; once the schema is stable, switch to real migrations and exportSchema = true.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): HanselDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, HanselDatabase::class.java).build()
    }
}

/** One lenient Json for every persisted model so schema additions with defaults don't break old rows. */
internal val StorageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
