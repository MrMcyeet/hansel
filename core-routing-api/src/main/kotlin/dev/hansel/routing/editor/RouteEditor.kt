package dev.hansel.routing.editor

import dev.hansel.model.AvoidZone
import dev.hansel.model.CorridorLock
import dev.hansel.model.GeoMath
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingOptions
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Snapshot of the editor: the request being shaped plus undo/redo stacks. Serializable so the app can restore it after
 * process death (DESIGN §12 crash recovery: "restore destination, shaping anchors, selected engine and last route").
 */
@Serializable
data class EditorState(
    val request: RouteRequest,
    val past: List<RouteRequest> = emptyList(),
    val future: List<RouteRequest> = emptyList(),
    /** Monotonic counter so the UI can tell "request changed, re-route" apart from selection changes. */
    val revision: Int = 0,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val isCustom: Boolean get() = request.isCustomShaped
}

/** A user-selected stretch of the currently displayed route, by distance along it. */
data class RouteStretch(val startAlongMeters: Double, val endAlongMeters: Double) {
    init { require(endAlongMeters >= startAlongMeters) }
}

/**
 * Phase 2 route shaping (DESIGN §5.1). Pure state machine: every mutation produces a new [RouteRequest]; the
 * app re-routes on each revision. Positioning of inserted points uses the currently displayed [Route] geometry
 * so that a point dragged onto the map lands in the right place in the waypoint order.
 */
class RouteEditor(initial: RouteRequest) {
    private val _state = MutableStateFlow(EditorState(initial))
    val state: StateFlow<EditorState> = _state
    val request: RouteRequest get() = _state.value.request

    // ---- Undo / redo ------------------------------------------------------------------------------------------

    fun undo(): Boolean {
        val s = _state.value
        if (s.past.isEmpty()) return false
        _state.value = s.copy(request = s.past.last(), past = s.past.dropLast(1), future = listOf(s.request) + s.future, revision = s.revision + 1)
        return true
    }

    fun redo(): Boolean {
        val s = _state.value
        if (s.future.isEmpty()) return false
        _state.value = s.copy(request = s.future.first(), past = s.past + s.request, future = s.future.drop(1), revision = s.revision + 1)
        return true
    }

    /** Drop all shaping: keeps only stops, clears locks, zones and shaping points. */
    fun resetShaping() = mutate { r ->
        r.copy(waypoints = r.waypoints.filter { !it.isShaping }, locks = emptyList(), avoidZones = emptyList())
    }

    /** Replace the whole request (e.g. restored from a saved route template). */
    fun load(request: RouteRequest) = mutate { request }

    private fun mutate(block: (RouteRequest) -> RouteRequest) {
        _state.update { s ->
            val next = block(s.request).copy(id = Ids.newId("req"), issuedAtMillis = System.currentTimeMillis())
            s.copy(request = next, past = (s.past + s.request).takeLast(MAX_HISTORY), future = emptyList(), revision = s.revision + 1)
        }
    }

    // ---- Stops ------------------------------------------------------------------------------------------------

    fun setDestination(place: Place) = mutate { r ->
        val dest = Waypoint(Ids.newId("wp"), place.point, WaypointType.STOP, place.name)
        r.copy(waypoints = r.waypoints.dropLast(1) + dest)
    }

    fun setOrigin(point: LatLng, name: String? = null, heading: Double? = null) = mutate { r ->
        r.copy(waypoints = listOf(Waypoint(r.origin.id, point, WaypointType.STOP, name, heading)) + r.waypoints.drop(1))
    }

    /** Adds a stop before the destination (or at [index] among the intermediate waypoints). */
    fun addStop(place: Place, index: Int? = null) = mutate { r ->
        val wp = Waypoint(Ids.newId("wp"), place.point, WaypointType.STOP, place.name)
        val mid = r.intermediate.toMutableList()
        mid.add(index?.coerceIn(0, mid.size) ?: mid.size, wp)
        r.copy(waypoints = listOf(r.origin) + mid + r.destination)
    }

    fun removeWaypoint(id: String) = mutate { r ->
        require(id != r.origin.id && id != r.destination.id) { "origin/destination cannot be removed; use setOrigin/setDestination" }
        r.copy(waypoints = r.waypoints.filter { it.id != id })
    }

    fun moveWaypoint(id: String, toIntermediateIndex: Int) = mutate { r ->
        val mid = r.intermediate.toMutableList()
        val wp = mid.firstOrNull { it.id == id } ?: return@mutate r
        mid.remove(wp)
        mid.add(toIntermediateIndex.coerceIn(0, mid.size), wp)
        r.copy(waypoints = listOf(r.origin) + mid + r.destination)
    }

    /** Toggle between a real stop (arrival guidance) and a through point (no stop). */
    fun setWaypointType(id: String, type: WaypointType) = mutate { r ->
        r.copy(waypoints = r.waypoints.map { if (it.id == id && it != r.origin && it != r.destination) it.copy(type = type) else it })
    }

    // ---- Shaping points: tap, long-press, drag ----------------------------------------------------------------

    /**
     * "Route through here": inserts a THROUGH shaping point at [point]. If [currentRoute] is given, the point is
     * placed in waypoint order by projecting it onto the route; otherwise it goes before the destination.
     */
    fun addThroughPoint(point: LatLng, currentRoute: Route?, name: String? = null): Waypoint {
        val wp = Waypoint(Ids.newId("shape"), point, WaypointType.THROUGH, name, isShaping = true, radiusMeters = 60)
        mutate { r -> insertByRoutePosition(r, wp, currentRoute, anchorForOrdering = point) }
        return wp
    }

    /**
     * Drag-to-shape (DESIGN §5.1): the user grabbed the selected route at [grabPoint] and released at [dropPoint].
     * If the grab was on an existing shaping point, that point moves; otherwise a new THROUGH point is inserted in
     * the order implied by where the route was grabbed.
     */
    fun dragShapingPoint(currentRoute: Route, grabPoint: LatLng, dropPoint: LatLng, grabToleranceMeters: Double = 40.0): Waypoint {
        val existing = request.intermediate.filter { it.isShaping }.minByOrNull { it.point.distanceTo(grabPoint) }
        if (existing != null && existing.point.distanceTo(grabPoint) <= grabToleranceMeters) {
            val moved = existing.copy(point = dropPoint)
            mutate { r -> r.copy(waypoints = r.waypoints.map { if (it.id == existing.id) moved else it }) }
            return moved
        }
        val wp = Waypoint(Ids.newId("shape"), dropPoint, WaypointType.THROUGH, isShaping = true, radiusMeters = 60)
        mutate { r -> insertByRoutePosition(r, wp, currentRoute, anchorForOrdering = grabPoint) }
        return wp
    }

    private fun insertByRoutePosition(r: RouteRequest, wp: Waypoint, route: Route?, anchorForOrdering: LatLng): RouteRequest {
        if (route == null || route.geometry.size < 2) {
            return r.copy(waypoints = listOf(r.origin) + r.intermediate + wp + r.destination)
        }
        val cum = GeoMath.cumulativeDistances(route.geometry)
        val along = GeoMath.projectOntoPolyline(anchorForOrdering, route.geometry, cum)?.alongMeters ?: cum.last()
        val mid = r.intermediate.toMutableList()
        // Existing intermediates in route order; insert before the first one that lies further along.
        val positions = mid.map { GeoMath.projectOntoPolyline(it.point, route.geometry, cum)?.alongMeters ?: Double.MAX_VALUE }
        val idx = positions.indexOfFirst { it > along }.let { if (it < 0) mid.size else it }
        mid.add(idx, wp)
        return r.copy(waypoints = listOf(r.origin) + mid + r.destination)
    }

    // ---- Stretch-level actions: lock / avoid / keep ------------------------------------------------------------

    /** "Prefer this road" / "Keep this portion": lock the stretch so rerouting preserves it. */
    fun lockStretch(route: Route, stretch: RouteStretch, label: String? = null, anchorSpacingMeters: Double = 300.0): CorridorLock {
        val anchors = sampleStretch(route, stretch, anchorSpacingMeters)
        val lock = CorridorLock(Ids.newId("lock"), anchors, label ?: route.roadNameAt(stretch))
        mutate { r -> r.copy(locks = r.locks + lock) }
        return lock
    }

    /** "Keep this portion" from the origin up to [alongMeters]. */
    fun keepPortion(route: Route, alongMeters: Double): CorridorLock = lockStretch(route, RouteStretch(0.0, alongMeters), "kept portion")

    /** "Avoid this road": a thin polygon around the stretch becomes an avoid zone. */
    fun avoidStretch(route: Route, stretch: RouteStretch, bufferMeters: Double = 15.0, label: String? = null): AvoidZone {
        val pts = sampleStretch(route, stretch, 50.0)
        val zone = AvoidZone(Ids.newId("avoid"), GeoMath.bufferPolyline(pts, bufferMeters), label ?: route.roadNameAt(stretch))
        mutate { r -> r.copy(avoidZones = r.avoidZones + zone) }
        return zone
    }

    fun addAvoidZone(ring: List<LatLng>, label: String? = null): AvoidZone {
        require(ring.size >= 3)
        val zone = AvoidZone(Ids.newId("avoid"), ring, label)
        mutate { r -> r.copy(avoidZones = r.avoidZones + zone) }
        return zone
    }

    fun removeLock(id: String) = mutate { r -> r.copy(locks = r.locks.filter { it.id != id }) }
    fun removeAvoidZone(id: String) = mutate { r -> r.copy(avoidZones = r.avoidZones.filter { it.id != id }) }
    fun setOptions(options: RoutingOptions) = mutate { r -> r.copy(options = options) }

    private fun sampleStretch(route: Route, stretch: RouteStretch, spacing: Double): List<LatLng> {
        val cum = GeoMath.cumulativeDistances(route.geometry)
        val end = stretch.endAlongMeters.coerceAtMost(cum.last())
        val start = stretch.startAlongMeters.coerceIn(0.0, end)
        val out = ArrayList<LatLng>()
        var d = start
        while (d < end) { out += GeoMath.pointAlong(route.geometry, d, cum); d += spacing }
        out += GeoMath.pointAlong(route.geometry, end, cum)
        return if (out.size >= 2) out else listOf(GeoMath.pointAlong(route.geometry, start, cum), GeoMath.pointAlong(route.geometry, end, cum))
    }

    // ---- Rerouting with preserved constraints ------------------------------------------------------------------

    /**
     * Builds the request for a mid-trip reroute (DESIGN §15 Phase 2 "rerouting with preserved constraints"):
     * origin becomes the vehicle; shaping points, lock anchors and stops already passed are dropped.
     * Does not touch the editor's own history: the result is for the rerouter, not an edit.
     */
    fun forReroute(currentRoute: Route, position: LatLng, heading: Double?, passedAlongMeters: Double, passedSlackMeters: Double = 150.0): RouteRequest {
        val r = request
        val cum = GeoMath.cumulativeDistances(currentRoute.geometry)
        fun along(p: LatLng) = GeoMath.projectOntoPolyline(p, currentRoute.geometry, cum)?.alongMeters ?: Double.MAX_VALUE
        val cutoff = passedAlongMeters - passedSlackMeters
        // Everything already behind the vehicle is dropped — shaping points and visited stops alike. Stops sit on
        // the route geometry, so their along-distance is reliable; a stop ahead of the cutoff is still to be visited.
        val remaining = r.intermediate.filter { wp -> along(wp.point) > cutoff }
        val locks = r.locks.mapNotNull { lock ->
            val keep = lock.anchors.filter { along(it) > cutoff }
            if (keep.size >= 2) lock.copy(anchors = keep) else null
        }
        val origin = Waypoint(Ids.newId("wp"), position, WaypointType.STOP, "Current location", heading)
        return r.copy(id = Ids.newId("reroute"), waypoints = listOf(origin) + remaining + r.destination, locks = locks,
            issuedAtMillis = System.currentTimeMillis())
    }

    /** Called when a reroute result is accepted so the editor's request reflects what is actually being navigated. */
    fun adoptRerouted(request: RouteRequest) {
        _state.update { s -> s.copy(request = request, revision = s.revision + 1) }
    }

    private fun Route.roadNameAt(stretch: RouteStretch): String? {
        val cum = GeoMath.cumulativeDistances(geometry)
        val mid = (stretch.startAlongMeters + stretch.endAlongMeters) / 2
        val idx = cum.indexOfFirst { it >= mid }.let { if (it < 0) geometry.lastIndex else it }
        return maneuvers.lastOrNull { it.beginShapeIndex <= idx }?.streetNames?.firstOrNull()
    }

    companion object {
        const val MAX_HISTORY = 50

        fun newRequest(origin: LatLng, destination: Place, originName: String = "Current location", options: RoutingOptions = RoutingOptions()): RouteRequest =
            RouteRequest(
                id = Ids.newId("req"),
                waypoints = listOf(
                    Waypoint(Ids.newId("wp"), origin, WaypointType.STOP, originName),
                    Waypoint(Ids.newId("wp"), destination.point, WaypointType.STOP, destination.name),
                ),
                options = options,
            )
    }
}
