package dev.hansel.routing.graphhopper

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import dev.hansel.model.EngineId
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
import dev.hansel.routing.EngineCapabilities
import dev.hansel.routing.EngineHealth
import dev.hansel.routing.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * GraphHopper core running inside the app process on a pre-built graph folder. Loads lazily on first use so the
 * load time can be measured and reported through [healthCheck].
 */
class OnDeviceGraphHopperEngine(
    private val graphDir: File,
    private val profileName: String = "car",
    private val chEnabled: Boolean = true,
) : RoutingEngine {
    override val id: EngineId = EngineId.GRAPHHOPPER
    override val capabilities = EngineCapabilities(
        nativeAlternatives = true, throughPoints = false, viaPoints = true,
        avoidPolygons = false, customEdgeCosts = false, onDevice = true,
    )

    @Volatile private var hopper: GraphHopper? = null
    @Volatile var loadMillis: Long = -1
        private set
    @Volatile var loadError: String? = null
        private set

    @Synchronized
    private fun ensureLoaded(): GraphHopper? {
        hopper?.let { return it }
        if (loadError != null) return null
        val t0 = System.currentTimeMillis()
        return try {
            val h = GraphHopper()
            h.graphHopperLocation = graphDir.absolutePath
            h.setProfiles(Profile(profileName))
            if (chEnabled) h.chPreparationHandler.setCHProfiles(CHProfile(profileName))
            h.setAllowWrites(false)
            if (!h.load()) { loadError = "no graph at ${graphDir.absolutePath}"; return null }
            loadMillis = System.currentTimeMillis() - t0
            hopper = h
            h
        } catch (t: Throwable) {
            loadError = "${t::class.simpleName}: ${t.message}"
            null
        }
    }

    override suspend fun healthCheck(): EngineHealth = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val h = ensureLoaded()
        if (h == null) EngineHealth(false, loadError ?: "not loaded", System.currentTimeMillis() - t0)
        else EngineHealth(true, "on-device graph loaded in ${loadMillis} ms · ${graphDir.name} · heap ${Runtime.getRuntime().totalMemory() / 1_048_576} MB", System.currentTimeMillis() - t0)
    }

    override suspend fun route(request: RouteRequest): RoutingResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val h = ensureLoaded() ?: return@withContext RoutingResult(request.id, id, emptyList(), RoutingError.Engine(loadError ?: "graph not loaded"), onDevice = true)
        try {
            val req = GHRequest().setProfile(profileName).setLocale(Locale.US)
            request.waypoints.forEach { req.addPoint(com.graphhopper.util.shapes.GHPoint(it.point.lat, it.point.lon)) }
            val wantAlts = request.options.alternatives > 0 && request.waypoints.size == 2
            if (wantAlts) {
                req.algorithm = Parameters.Algorithms.ALT_ROUTE
                req.putHint(Parameters.CH.DISABLE, true)
                req.putHint(Parameters.Algorithms.AltRoute.MAX_PATHS, request.options.alternatives + 1)
            }
            if (request.intermediate.any { it.type != dev.hansel.model.WaypointType.STOP }) req.putHint(Parameters.Routing.PASS_THROUGH, true)
            val rsp = h.route(req)
            if (rsp.hasErrors()) {
                val msg = rsp.errors.joinToString("; ") { it.message ?: it::class.simpleName ?: "error" }
                val err = if (msg.contains("Connection between locations not found")) RoutingError.NoRoute(msg) else RoutingError.Engine(msg)
                return@withContext RoutingResult(request.id, id, emptyList(), err, System.currentTimeMillis() - t0, onDevice = true)
            }
            val routes = rsp.all.mapIndexed { i, p -> toRoute(request, p, i) }
            RoutingResult(request.id, id, routes, null, System.currentTimeMillis() - t0, onDevice = true,
                warnings = listOfNotNull(if (request.avoidZones.isNotEmpty()) "avoid zones ignored by on-device spike" else null))
        } catch (t: Throwable) {
            RoutingResult(request.id, id, emptyList(), RoutingError.Engine("${t::class.simpleName}: ${t.message}"), System.currentTimeMillis() - t0, onDevice = true)
        }
    }

    private fun toRoute(request: RouteRequest, path: ResponsePath, alt: Int): Route {
        val pts = path.points
        val geometry = (0 until pts.size()).map { LatLng(pts.getLat(it), pts.getLon(it)) }
        var cursor = 0
        val maneuvers = path.instructions.mapIndexed { i, ins ->
            val begin = cursor
            val len = ins.points.size()
            val end = (begin + len).coerceAtMost(geometry.lastIndex)
            cursor = end
            val type = if (i == 0 && ins.sign == 0) ManeuverType.DEPART else GraphHopperSigns.toManeuverType(ins.sign)
            Maneuver(type, ins.getTurnDescription(h().translationMap.getWithFallBack(Locale.US)), streetNames = listOfNotNull(ins.name.takeIf { it.isNotBlank() }),
                distanceMeters = ins.distance, durationSeconds = ins.time / 1000.0, beginShapeIndex = begin, endShapeIndex = end)
        }
        val leg = RouteLeg(0, geometry.lastIndex, path.distance, path.time / 1000.0, maneuvers)
        return Route(Ids.newId("ghlocal"), request.id, id, geometry, listOf(leg), path.distance, path.time / 1000.0,
            summary = RouteSummary(majorRoads = maneuvers.groupBy { it.streetNames.firstOrNull() ?: "" }.filterKeys { it.isNotBlank() }
                .mapValues { e -> e.value.sumOf { it.distanceMeters } }.entries.sortedByDescending { it.value }.take(3).map { it.key }),
            alternativeIndex = alt, label = if (alt == 0) null else "alternative $alt (on-device)")
    }

    private fun h(): GraphHopper = hopper!!
}
