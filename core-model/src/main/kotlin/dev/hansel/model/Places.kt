package dev.hansel.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlaceSource { SEARCH, RECENT, SAVED, MAP_TAP, CURRENT_LOCATION }

@Serializable
data class Place(
    val id: String,
    val name: String,
    val address: String? = null,
    val point: LatLng,
    val source: PlaceSource = PlaceSource.SEARCH,
    val category: String? = null,
)

/** A position fix from GPS/fused location. Raw measurements are preserved; speed is never clamped (DESIGN §6.1). */
@Serializable
data class Fix(
    val point: LatLng,
    val timeMillis: Long,
    val accuracyMeters: Float? = null,
    /** Meters per second, as measured. */
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val altitudeMeters: Double? = null,
    val provider: String? = null,
    val isMock: Boolean = false,
)

/** Freshness of a traffic/incident observation (DESIGN §12). Cached data must never be presented as live. */
@Serializable
enum class Freshness { FRESH, STALE, UNAVAILABLE }
