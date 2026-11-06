package dev.hansel.offline

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * On-device place/address index built from the OSM extracts while they are merged (DESIGN §4.1 "search index").
 * Written by the offline pipeline, read by the local geocoder. Deliberately simple: one row per named node/way
 * with a representative point; FTS4 over the searchable text.
 */
@Entity(tableName = "places")
data class PlaceRow(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    /** "node/123" or "way/456" */
    val osmId: String,
    val name: String,
    /** addr:housenumber + addr:street when present, else null. */
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    /** OSM primary tag, e.g. "amenity=fuel", "highway=residential", "place=town". */
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** Higher = more important (towns > suburbs > shops > streets). Used to order results. */
    val rank: Int = 0,
)

@Fts4(contentEntity = PlaceRow::class)
@Entity(tableName = "places_fts")
data class PlaceFts(
    val name: String,
    val street: String?,
    val city: String?,
    val postcode: String?,
)

data class PlaceHit(
    val rowid: Long,
    val osmId: String,
    val name: String,
    val street: String?,
    val city: String?,
    val state: String?,
    val postcode: String?,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val rank: Int,
)

@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PlaceRow>)

    @Query("DELETE FROM places")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Long

    /**
     * FTS prefix match, ranked by importance then by distance to the bias point when given. The caller passes an
     * FTS query such as `hatties* main*`.
     */
    @Query(
        """
        SELECT p.rowid, p.osmId, p.name, p.street, p.city, p.state, p.postcode, p.kind, p.lat, p.lon, p.rank
        FROM places p JOIN places_fts f ON p.rowid = f.rowid
        WHERE places_fts MATCH :ftsQuery
        ORDER BY p.rank DESC,
          ((p.lat - :biasLat) * (p.lat - :biasLat) + (p.lon - :biasLon) * (p.lon - :biasLon) * :lonScale) ASC
        LIMIT :limit
        """
    )
    suspend fun search(ftsQuery: String, biasLat: Double, biasLon: Double, lonScale: Double, limit: Int): List<PlaceHit>

    /** Nearest named places to a point, for reverse geocoding. Bounding-box prefilter then distance order. */
    @Query(
        """
        SELECT p.rowid, p.osmId, p.name, p.street, p.city, p.state, p.postcode, p.kind, p.lat, p.lon, p.rank
        FROM places p
        WHERE p.lat BETWEEN :south AND :north AND p.lon BETWEEN :west AND :east
        ORDER BY ((p.lat - :lat) * (p.lat - :lat) + (p.lon - :lon) * (p.lon - :lon) * :lonScale) ASC
        LIMIT :limit
        """
    )
    suspend fun nearest(lat: Double, lon: Double, south: Double, north: Double, west: Double, east: Double, lonScale: Double, limit: Int): List<PlaceHit>

    @Transaction
    suspend fun replaceAll(rows: List<PlaceRow>) { clear(); insertAll(rows) }
}

@Database(entities = [PlaceRow::class, PlaceFts::class], version = 1, exportSchema = false)
abstract class SearchIndexDb : RoomDatabase() {
    abstract fun places(): PlaceDao

    companion object {
        const val NAME = "hansel-search.db"
        fun build(context: Context): SearchIndexDb =
            Room.databaseBuilder(context.applicationContext, SearchIndexDb::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
