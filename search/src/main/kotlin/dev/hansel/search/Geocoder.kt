package dev.hansel.search

import dev.hansel.model.LatLng
import dev.hansel.model.Place

/**
 * Forward and reverse geocoding (DESIGN.md §15 Phase 1 "initial regional search").
 *
 * Implementations never throw for network or server problems: [search] returns an empty list and
 * [reverse] returns null, and the failure is reported through the implementation's error callback.
 */
interface Geocoder {
    /**
     * Free-text address/POI search.
     * @param near optional bias point (e.g. the map centre or current location); results are not
     *   restricted to its surroundings, only preferred.
     * @param limit maximum number of results.
     */
    suspend fun search(query: String, near: LatLng?, limit: Int = 8): List<Place>

    /** Nearest addressable place to [point], or null if none / on error. */
    suspend fun reverse(point: LatLng): Place?
}
