package dev.hansel.routing

import dev.hansel.model.CorridorLock
import dev.hansel.model.GeoMath
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import dev.hansel.model.Route
import dev.hansel.model.RouteLeg
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingResult
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType

/**
 * Sits above every engine and turns [CorridorLock]s and shaping waypoints into something the engine can honour,
 * then *verifies* the engine actually honoured them (DESIGN §5.2: "fail visibly rather than silently ignoring an
 * anchor").
 *
 * Strategy A (engine supports through-points): each lock's anchors become ordered [WaypointType.THROUGH] shaping
 * waypoints placed in sequence along the stop order. One request, native maneuvers.
 *
 * Strategy B (no through-point support): the journey is split at every anchor into STOP→STOP sub-requests which are
 * routed independently and stitched, dropping the duplicated arrive/depart pairs at the seams.
 */
class CorridorLockController(private val validator: RouteValidator = RouteValidator()) {

    suspend fun route(engine: RoutingEngine, request: RouteRequest): RoutingResult {
        if (request.locks.isEmpty()) {
            val result = engine.route(request)
            return validator.annotate(request, result)
        }
        val expanded = expandLocksToWaypoints(request)
        val result = if (engine.capabilities.throughPoints) {
            engine.route(expanded)
        } else {
            routeBySubrequests(engine, expanded)
        }
        if (!result.isSuccess) return result
        return validator.annotate(request, result)
    }

    /**
     * Inserts lock anchors as THROUGH waypoints. Position in the sequence: after the existing waypoint whose chord
     * position comes before the lock's first anchor. We project the anchor onto the polyline through the *stop*
     * waypoints, which is a good proxy for "where along the journey this corridor lives" without needing a route first.
     */
    fun expandLocksToWaypoints(request: RouteRequest): RouteRequest {
        if (request.locks.isEmpty()) return request
        val chord = request.waypoints.map { it.point }
        val cum = GeoMath.cumulativeDistances(chord)
        data class Positioned(val along: Double, val wp: Waypoint)
        val positioned = ArrayList<Positioned>()
        request.waypoints.forEachIndexed { i, wp -> positioned += Positioned(cum[i], wp) }
        for (lock in request.locks) {
            val first = lock.anchors.firstOrNull() ?: continue
            val proj = GeoMath.projectOntoPolyline(first, chord, cum) ?: continue
            lock.anchors.forEachIndexed { ai, anchor ->
                // Anchors of one lock stay contiguous and ordered: add a tiny epsilon per anchor.
                positioned += Positioned(proj.alongMeters + ai * 1e-3, Waypoint(
                    id = "${lock.id}#$ai", point = anchor, type = WaypointType.THROUGH,
                    name = lock.label, isShaping = true, radiusMeters = lock.toleranceMeters.toInt().coerceAtLeast(10),
                ))
            }
        }
        // Origin and destination keep their ends regardless of projection quirks.
        val origin = positioned.first { it.wp.id == request.origin.id }
        val dest = positioned.first { it.wp.id == request.destination.id }
        val middle = positioned.filter { it !== origin && it !== dest }.sortedBy { it.along }
        return request.copy(waypoints = listOf(origin.wp) + middle.map { it.wp } + dest.wp, locks = emptyList())
    }

    private suspend fun routeBySubrequests(engine: RoutingEngine, expanded: RouteRequest): RoutingResult {
        val wps = expanded.waypoints
        val results = ArrayList<Route>()
        var latency = 0L
        for (i in 0 until wps.size - 1) {
            val sub = expanded.copy(
                id = "${expanded.id}/sub$i",
                waypoints = listOf(wps[i].copy(type = WaypointType.STOP), wps[i + 1].copy(type = WaypointType.STOP)),
                options = expanded.options.copy(alternatives = 0),
            )
            val r = engine.route(sub)
            latency += r.latencyMillis
            val route = r.primary ?: return RoutingResult(expanded.id, engine.id, emptyList(),
                r.error ?: RoutingError.NoRoute("sub-route $i failed"), latency, r.onDevice)
            results += route
        }
        return RoutingResult(expanded.id, engine.id, listOf(stitch(expanded, results, wps)), null, latency, results.isNotEmpty())
    }

    /** Concatenate sub-routes; seams at non-stop waypoints lose their ARRIVE/DEPART pair and get a CONTINUE instead. */
    fun stitch(request: RouteRequest, parts: List<Route>, wps: List<Waypoint>): Route {
        val geometry = ArrayList<LatLng>()
        val legs = ArrayList<RouteLeg>()
        var pendingManeuvers = ArrayList<Maneuver>()
        var legStart = 0
        var legDist = 0.0; var legDur = 0.0
        parts.forEachIndexed { pi, part ->
            val offset = if (geometry.isEmpty()) 0 else geometry.size - 1
            if (geometry.isNotEmpty()) geometry.removeAt(geometry.size - 1)
            geometry.addAll(part.geometry)
            val seamIsStop = pi == parts.size - 1 || wps[pi + 1].type == WaypointType.STOP || wps[pi + 1].type == WaypointType.STOP_THROUGH
            val shifted = part.maneuvers.map { m -> m.copy(beginShapeIndex = m.beginShapeIndex + offset, endShapeIndex = m.endShapeIndex + offset) }
            val cleaned = shifted.filterIndexed { mi, m ->
                val isLeadingDepart = mi == 0 && m.type == ManeuverType.DEPART && pi > 0
                val isTrailingArrive = mi == shifted.lastIndex && m.type.isArrival && !seamIsStop
                !(isLeadingDepart || isTrailingArrive)
            }.let { list ->
                if (pi > 0) {
                    // Replace the dropped depart with a continue so guidance keeps flowing through the seam.
                    val first = shifted.first()
                    listOf(first.copy(type = ManeuverType.CONTINUE, instruction = "Continue")) + list.drop(if (list.firstOrNull() === first) 1 else 0)
                } else list
            }
            pendingManeuvers.addAll(cleaned)
            legDist += part.distanceMeters; legDur += part.durationSeconds
            if (seamIsStop) {
                legs += RouteLeg(legStart, geometry.size - 1, legDist, legDur, pendingManeuvers)
                pendingManeuvers = ArrayList(); legStart = geometry.size - 1; legDist = 0.0; legDur = 0.0
            }
        }
        val first = parts.first()
        return Route(
            id = Ids.newId("stitched"), requestId = request.id, engine = first.engine, geometry = geometry, legs = legs,
            distanceMeters = parts.sumOf { it.distanceMeters }, durationSeconds = parts.sumOf { it.durationSeconds },
            baseDurationSeconds = parts.sumOf { it.baseDurationSeconds },
            summary = first.summary.copy(
                hasTolls = parts.any { it.summary.hasTolls }, hasFerry = parts.any { it.summary.hasFerry },
                hasHighway = parts.any { it.summary.hasHighway }, hasUnpaved = parts.any { it.summary.hasUnpaved },
                majorRoads = parts.flatMap { it.summary.majorRoads }.distinct().take(4),
            ),
            edgeIds = parts.flatMap { it.edgeIds },
            warnings = listOf("stitched from ${parts.size} sub-routes (engine lacks through-points)"),
        )
    }
}

/** Checks that an engine's routes honour the shaping constraints the user set. */
class RouteValidator {
    data class Violation(val routeId: String, val error: RoutingError)

    fun annotate(request: RouteRequest, result: RoutingResult): RoutingResult {
        if (!result.isSuccess) return result
        val checked = result.routes.map { route ->
            val problems = validate(request, route)
            if (problems.isEmpty()) route else route.copy(warnings = route.warnings + problems.map { it.error.message })
        }
        // If the primary violates a lock, that is a hard failure: surface it instead of quietly navigating elsewhere.
        val primaryLock = validate(request, checked.first()).firstOrNull { it.error is RoutingError.LockViolated }
        return if (primaryLock != null) result.copy(routes = checked, error = primaryLock.error) else result.copy(routes = checked)
    }

    fun validate(request: RouteRequest, route: Route): List<Violation> {
        val out = ArrayList<Violation>()
        val cum = GeoMath.cumulativeDistances(route.geometry)
        var lastAlong = -1.0
        for (lock in request.locks) {
            lock.anchors.forEachIndexed { i, anchor ->
                val proj = GeoMath.projectOntoPolyline(anchor, route.geometry, cum)
                if (proj == null || proj.distanceMeters > lock.toleranceMeters) {
                    out += Violation(route.id, RoutingError.LockViolated("Route misses locked road ${lock.label ?: lock.id} (anchor ${i + 1})", lock.id, i))
                } else if (proj.alongMeters < lastAlong - lock.toleranceMeters) {
                    out += Violation(route.id, RoutingError.LockViolated("Route visits locked road ${lock.label ?: lock.id} out of order", lock.id, i))
                } else lastAlong = proj.alongMeters
            }
        }
        for (wp in request.intermediate) {
            val proj = GeoMath.projectOntoPolyline(wp.point, route.geometry, cum)
            val tol = (wp.radiusMeters ?: 50).toDouble().coerceAtLeast(50.0)
            if (proj == null || proj.distanceMeters > tol) {
                out += Violation(route.id, RoutingError.Unreachable("Route does not pass through ${wp.name ?: "shaping point"}", wp.id))
            }
        }
        for (zone in request.avoidZones) {
            if (route.geometry.any { GeoMath.pointInPolygon(it, zone.ring) }) {
                out += Violation(route.id, RoutingError.Engine("Route enters avoided area ${zone.label ?: zone.id}"))
            }
        }
        return out
    }
}
