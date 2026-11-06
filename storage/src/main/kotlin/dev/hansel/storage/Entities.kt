package dev.hansel.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved route template: the [dev.hansel.model.RouteRequest] serialized with kotlinx `Json`. */
@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val requestJson: String,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
    val useCount: Int,
)

@Entity(tableName = "recent_places")
data class RecentPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lon: Double,
    val category: String?,
    val lastUsedAtMillis: Long,
    val useCount: Int,
    val isSaved: Boolean,
)

/** DESIGN §11 Trip. [source] is "active" (guided navigation) or "passive" (opt-in background recording). */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startAtMillis: Long,
    val endAtMillis: Long?,
    val requestId: String?,
    val routeId: String?,
    val engine: String?,
    val source: String,
    /** Per-trip exclusion from learning (DESIGN §13). The samples stay until the trip is deleted. */
    @ColumnInfo(defaultValue = "0") val excluded: Boolean = false,
    val sampleCount: Int,
)

/** DESIGN §11 GpsSample. Raw measurements; [speedMps] is never clamped (DESIGN §6.1). */
@Entity(
    tableName = "gps_samples",
    indices = [Index("tripId")],
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class GpsSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val atMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val bearing: Float?,
    val provider: String?,
)

/** DESIGN §11 Benchmark. The whole [dev.hansel.benchmark.AbRecord] lives in [json]; the columns are for indexing. */
@Entity(tableName = "ab_records")
data class AbRecordEntity(
    @PrimaryKey val requestId: String,
    val json: String,
    val createdAtMillis: Long,
    val completedAtMillis: Long?,
)
