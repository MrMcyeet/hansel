package dev.hansel.map

import dev.hansel.model.AvoidZone
import dev.hansel.model.CorridorLock
import dev.hansel.model.Fix
import dev.hansel.model.LatLng
import dev.hansel.model.Route
import dev.hansel.model.Waypoint

/** Everything the map draws on top of the basemap. Immutable; the map diffs by identity and re-sets sources. */
data class MapOverlay(
    val routes: List<Route> = emptyList(),
    val selectedRouteId: String? = null,
    val waypoints: List<Waypoint> = emptyList(),
    val locks: List<CorridorLock> = emptyList(),
    val avoidZones: List<AvoidZone> = emptyList(),
    val position: Fix? = null,
    /** Position snapped to the route during guidance; drawn instead of the raw fix when present. */
    val snapped: LatLng? = null,
    val destination: LatLng? = null,
) {
    val selectedRoute: Route? get() = routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()
}

enum class CameraMode {
    /** User is panning; leave the camera alone. */
    FREE,
    /** Fit all routes (preview/editing). */
    FIT_ROUTES,
    /** Follow the vehicle, heading up, tilted (guidance). */
    FOLLOW,
    /** Center on the current position once at a street zoom (home screen). */
    CENTER_POSITION,
}

class MapGestures(
    val onRouteTap: (routeId: String) -> Unit = {},
    val onMapTap: (LatLng) -> Unit = {},
    val onLongPress: (LatLng) -> Unit = {},
    /** The user grabbed the selected route at [grab] and released at [drop]. */
    val onDragShaping: (grab: LatLng, drop: LatLng) -> Unit = { _, _ -> },
    /** Any user pan/zoom; the host usually switches [CameraMode] to FREE. */
    val onUserMovedCamera: () -> Unit = {},
    /** Drag-to-shape only makes sense when editing a route and stationary (DESIGN §8). */
    val dragEnabled: Boolean = false,
)

object ProtomapsStyle {
    /** Hosted Protomaps v5 style; themes: light, dark, white, grayscale, black. */
    fun url(theme: String, key: String, lang: String = "en"): String =
        "https://api.protomaps.com/styles/v5/${theme.ifBlank { "dark" }}/$lang.json?key=$key"

    const val ATTRIBUTION = "Protomaps © OpenStreetMap contributors"
}
