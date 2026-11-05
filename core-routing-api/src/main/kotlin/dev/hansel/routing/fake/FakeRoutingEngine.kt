package dev.hansel.routing.fake

import dev.hansel.model.EngineId
import dev.hansel.model.GeoMath
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import dev.hansel.model.Route
import dev.hansel.model.RouteLeg
import dev.hansel.model.RouteRequest
import dev.hansel.model.RouteSummary
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingResult
import dev.hansel.model.WaypointType
import dev.hansel.routing.EngineCapabilities
import dev.hansel.routing.EngineHealth
import dev.hansel.routing.RoutingEngine
import kotlinx.coroutines.delay

/**
 * Deterministic in-memory engine. Draws a straight-ish line through every waypoint with a gentle bulge so that
 * alternatives differ geometrically. Used by unit tests (editor, orchestrator, navigation session) and by the app's
 * "demo mode" when no dev server is reachable. It honors avoid zones by refusing routes whose midpoints land inside one,
 * so the editor's avoid logic is testable end to end.
 */
class FakeRoutingEngine(
    private val speedMps: Double = 20.0,
    private val latencyMillis: Long = 0,
    private val alternativeBulgeMeters: Double = 400.0,
) : RoutingEngine {
    override val id = EngineId.FAKE
    override val capabilities = EngineCapabilities(
        nativeAlternatives = true, throughPoints = true, viaPoints = true,
        avoidPolygons = true, customEdgeCosts = true, onDevice = true,
    )

    var callCount: Int = 0
        private set
    var lastRequest: RouteRequest? = null
        private set

    override suspend fun healthCheck() = EngineHealth(true, "fake engine")

    override suspend fun route(request: RouteRequest): RoutingResult {
        callCount++
        lastRequest = request
        if (latencyMillis > 0) delay(latencyMillis)
        val count = 1 + request.options.alternatives.coerceIn(0, 2)
        val routes = (0 until count).mapNotNull { alt -> build(request, alt) }
        if (routes.isEmpty()) {
            return RoutingResult(request.id, id, emptyList(), RoutingError.NoRoute("all candidates cross an avoid zone"), onDevice = true)
        }
        return RoutingResult(request.id, id, routes, onDevice = true)
    }

    private fun build(request: RouteRequest, alt: Int): Route? {
        val geometry = ArrayList<LatLng>()
        val legs = ArrayList<RouteLeg>()
        val wps = request.waypoints
        for (i in 0 until wps.size - 1) {
            val a = wps[i].point; val b = wps[i + 1].point
            val legStart = geometry.size
            val segment = segmentPoints(a, b, alt)
            if (geometry.isNotEmpty()) geometry.removeAt(geometry.size - 1)
            geometry.addAll(segment)
            val legEnd = geometry.size - 1
            val legDist = GeoMath.polylineLengthMeters(geometry.subList(legStart, legEnd + 1))
            val legDur = legDist / speedMps * (1.0 + 0.1 * alt)
            val arriveType = when (wps[i + 1].type) {
                WaypointType.STOP, WaypointType.STOP_THROUGH -> ManeuverType.ARRIVE
                else -> ManeuverType.WAYPOINT_THROUGH
            }
            val maneuvers = listOf(
                Maneuver(if (i == 0) ManeuverType.DEPART else ManeuverType.CONTINUE, "Head toward ${wps[i + 1].name ?: "next point"}",
                    distanceMeters = legDist, durationSeconds = legDur, beginShapeIndex = legStart, endShapeIndex = legEnd),
                Maneuver(arriveType, if (arriveType.isArrival) "Arrive at ${wps[i + 1].name ?: "destination"}" else "Pass through",
                    distanceMeters = 0.0, durationSeconds = 0.0, beginShapeIndex = legEnd, endShapeIndex = legEnd),
            )
            // Legs only split at stops; merge into the previous leg for through/via points.
            val isStop = wps[i + 1].type == WaypointType.STOP || wps[i + 1].type == WaypointType.STOP_THROUGH
            if (!isStop && legs.isNotEmpty()) {
                val prev = legs.removeAt(legs.size - 1)
                legs += prev.copy(endShapeIndex = legEnd, distanceMeters = prev.distanceMeters + legDist,
                    durationSeconds = prev.durationSeconds + legDur, maneuvers = prev.maneuvers.dropLast(1) + maneuvers)
            } else {
                legs += RouteLeg(legStart, legEnd, legDist, legDur, maneuvers)
            }
        }
        // Honor avoid zones: reject if any sampled point lies inside one.
        for (zone in request.avoidZones) {
            if (geometry.any { GeoMath.pointInPolygon(it, zone.ring) }) return null
        }
        val dist = legs.sumOf { it.distanceMeters }
        val dur = legs.sumOf { it.durationSeconds }
        return Route(
            id = Ids.newId("fake"), requestId = request.id, engine = id, geometry = geometry, legs = legs,
            distanceMeters = dist, durationSeconds = dur, summary = RouteSummary(majorRoads = listOf("Fake Rd ${alt + 1}")),
            alternativeIndex = alt, label = if (alt == 0) null else "alternative ${alt}",
        )
    }

    /** 20 points from a to b; alternatives bulge perpendicular to the chord so overlap tests can tell them apart. */
    private fun segmentPoints(a: LatLng, b: LatLng, alt: Int): List<LatLng> {
        val n = 20
        val bearing = a.bearingTo(b)
        val side = if (alt % 2 == 1) 90.0 else 270.0
        return (0..n).map { i ->
            val t = i.toDouble() / n
            val base = LatLng(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
            if (alt == 0) base else {
                val bulge = alternativeBulgeMeters * alt * 4.0 * t * (1.0 - t)
                GeoMath.destination(base, (bearing + side) % 360.0, bulge)
            }
        }
    }
}
