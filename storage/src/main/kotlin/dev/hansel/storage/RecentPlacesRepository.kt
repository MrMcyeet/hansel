package dev.hansel.storage

import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Recent and saved destinations. A place is keyed by [Place.id] (search result id, or a synthetic id for map taps),
 * so re-selecting the same destination bumps its use count instead of duplicating it.
 */
class RecentPlacesRepository(
    private val db: HanselDatabase,
    private val maxUnsaved: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.recentPlaces()

    /** Call when the user picks [place] as a destination/stop. Keeps `isSaved` if it was already saved. */
    suspend fun recordUse(place: Place) {
        val existing = dao.get(place.id)
        dao.upsert(
            RecentPlaceEntity(
                id = place.id,
                name = place.name,
                address = place.address ?: existing?.address,
                lat = place.point.lat,
                lon = place.point.lon,
                category = place.category ?: existing?.category,
                lastUsedAtMillis = clock(),
                useCount = (existing?.useCount ?: 0) + 1,
                isSaved = existing?.isSaved ?: (place.source == PlaceSource.SAVED),
            ),
        )
        dao.pruneUnsaved(maxUnsaved)
    }

    /** Most recently used first; saved places are included and surface with [PlaceSource.SAVED]. */
    fun recents(limit: Int = 10): Flow<List<Place>> = dao.observeRecents(limit).map { rows -> rows.map(::toPlace) }

    fun saved(): Flow<List<Place>> = dao.observeSaved().map { rows -> rows.map(::toPlace) }

    suspend fun get(id: String): Place? = dao.get(id)?.let(::toPlace)

    /** Toggles the star. [place] is inserted first if it has never been used, so saving from search works. */
    suspend fun setSaved(id: String, saved: Boolean) = dao.setSaved(id, saved)

    suspend fun save(place: Place) {
        if (dao.get(place.id) == null) {
            dao.upsert(
                RecentPlaceEntity(
                    id = place.id, name = place.name, address = place.address, lat = place.point.lat, lon = place.point.lon,
                    category = place.category, lastUsedAtMillis = clock(), useCount = 0, isSaved = true,
                ),
            )
        } else {
            dao.setSaved(place.id, true)
        }
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun toPlace(e: RecentPlaceEntity) = Place(
        id = e.id,
        name = e.name,
        address = e.address,
        point = LatLng(e.lat, e.lon),
        source = if (e.isSaved) PlaceSource.SAVED else PlaceSource.RECENT,
        category = e.category,
    )
}
