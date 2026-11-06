package dev.hansel.offline

import android.content.Context
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos

/** Geocoding against the on-device index (DESIGN §4.1 "search index": addresses, streets, places, POIs). */
class OfflineSearch(context: Context) {
    private val db: SearchIndexDb by lazy { SearchIndexDb.build(context) }

    suspend fun search(query: String, near: LatLng?, limit: Int = 10): List<Place> = withContext(Dispatchers.IO) {
        val fts = toFtsQuery(query) ?: return@withContext emptyList()
        val bias = near ?: LatLng(0.0, 0.0)
        val lonScale = if (near == null) 0.0 else cos(near.lat * PI / 180.0).let { it * it }
        runCatching { db.places().search(fts, bias.lat, bias.lon, lonScale, limit * 3) }.getOrDefault(emptyList())
            .distinctBy { it.name.lowercase() to (it.city ?: "") }
            .take(limit)
            .map { it.toPlace() }
    }

    suspend fun reverse(point: LatLng): Place? = withContext(Dispatchers.IO) {
        val d = 0.02
        val lonScale = cos(point.lat * PI / 180.0).let { it * it }
        runCatching { db.places().nearest(point.lat, point.lon, point.lat - d, point.lat + d, point.lon - d, point.lon + d, lonScale, 1) }
            .getOrDefault(emptyList()).firstOrNull()?.toPlace()
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) { runCatching { db.places().count() }.getOrDefault(0L) }

    private fun PlaceHit.toPlace() = Place(
        id = osmId, name = name,
        address = listOfNotNull(street, city, postcode).joinToString(", ").ifBlank { null },
        point = LatLng(lat, lon), source = PlaceSource.SEARCH, category = kind,
    )

    companion object {
        /** "123 main st hattiesburg" → `123* main* st* hattiesburg*` (FTS4 prefix terms, punctuation stripped). */
        fun toFtsQuery(q: String): String? {
            val terms = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            if (terms.isEmpty()) return null
            return terms.joinToString(" ") { "$it*" }
        }
    }
}
