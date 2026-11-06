package dev.hansel.routing.graphhopper

import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Instruction
import com.graphhopper.util.JsonFeature
import com.graphhopper.util.JsonFeatureCollection
import com.graphhopper.util.Parameters
import com.graphhopper.util.details.PathDetail
import com.graphhopper.util.exceptions.ConnectionNotFoundException
import com.graphhopper.util.exceptions.MaximumNodesExceededException
import com.graphhopper.util.exceptions.PointNotFoundException
import com.graphhopper.util.shapes.GHPoint
import dev.hansel.model.AvoidZone
import dev.hansel.model.EngineId
import dev.hansel.model.HighwayPreference
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import dev.hansel.model.Route
import dev.hansel.model.RouteLeg
import dev.hansel.model.RouteRequest
import dev.hansel.model.RouteSummary
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingOptions
import dev.hansel.model.RoutingResult
import dev.hansel.model.WaypointType
import dev.hansel.routing.EngineCapabilities
import dev.hansel.routing.EngineHealth
import dev.hansel.routing.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * GraphHopper core running inside the app on a graph folder built by [GraphImporter] with [GhProfile]. Everything
 * here is on-device: no network, no Janino (see [HanselGraphHopper]).
 *
 * ## Speed mode vs flexible mode
 * The graph carries a CH preparation for the `car` profile, which answers plain requests in milliseconds. Anything
 * that changes edge costs or the search (a request `custom_model` for avoid options/zones, alternatives,
 * `pass_through`, headings) needs `ch.disable=true` — a plain bidirectional A* over the whole region without
 * landmarks. On a phone that is fine for city-scale trips but can take many seconds for long ones, so requests whose
 * straight-line length exceeds [flexibleMaxStraightLineMeters] fall back to the CH route and report what was
 * skipped in [RoutingResult.warnings]; the one exception is avoid zones, which are always honoured (a wrong route is
 * worse than a slow one).
 *
 * @param graphDir the active GraphHopper folder (`OfflineLayout.activeGraph`).
 * @param strictInterpreter fail requests whose custom model the interpreter cannot handle instead of trying Janino
 *   (which would throw on ART anyway); tests enable it.
 * @param flexibleMaxStraightLineMeters cap above which flexible-mode features are skipped (default 120 km).
 */
class LocalGraphHopperEngine(
    private val graphDir: File,
    private val strictInterpreter: Boolean = false,
    private val flexibleMaxStraightLineMeters: Double = DEFAULT_FLEXIBLE_MAX_STRAIGHT_LINE_METERS,
) : RoutingEngine {
    override val id: EngineId = EngineId.GRAPHHOPPER
    override val capabilities = EngineCapabilities(
        nativeAlternatives = true, throughPoints = false, viaPoints = true,
        avoidPolygons = true, customEdgeCosts = false, onDevice = true,
    )

    @Volatile private var hopper: GraphHopper? = null

    /** Wall time of the last successful [load], or -1. */
    @Volatile var loadMillis: Long = -1
        private set

    /** Why the last load failed, or null. */
    @Volatile var loadError: String? = null
        private set

    val isLoaded: Boolean get() = hopper != null

    /**
     * Opens the graph if it is not open yet. Safe to call from any thread; blocking (hundreds of ms for a state-sized
     * graph with MMAP). Returns null and records [loadError] when there is no usable graph.
     */
    @Synchronized
    fun load(): GraphHopper? {
        hopper?.let { return it }
        if (loadError != null) return null
        val t0 = System.currentTimeMillis()
        return try {
            if (!graphDir.isDirectory || graphDir.listFiles().isNullOrEmpty()) {
                loadError = "no offline graph installed at ${graphDir.absolutePath}"
                return null
            }
            val h = GhProfile.newGraphHopper(graphDir, strictInterpreter = strictInterpreter)
            h.setAllowWrites(false)
            if (!h.load()) {
                loadError = "no offline graph installed (GraphHopper could not load ${graphDir.absolutePath})"
                h.close()
                return null
            }
            loadMillis = System.currentTimeMillis() - t0
            hopper = h
            h
        } catch (t: Throwable) {
            loadError = "no offline graph installed (${t::class.simpleName}: ${t.message})"
            null
        }
    }

    /** Closes the current graph (if any) and forgets the last error, so the next call loads afresh. */
    @Synchronized
    fun reload(): Boolean {
        close()
        return load() != null
    }

    @Synchronized
    fun close() {
        hopper?.let { runCatching { it.close() } }
        hopper = null
        loadError = null
        loadMillis = -1
    }

    override suspend fun healthCheck(): EngineHealth = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val h = load()
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        if (h == null) {
            EngineHealth(false, loadError ?: "not loaded", System.currentTimeMillis() - t0)
        } else {
            val g = h.baseGraph
            EngineHealth(
                true,
                "on-device graph '${graphDir.name}' loaded in $loadMillis ms · ${g.nodes} nodes · ${g.edges} edges · heap used $heapMb MB",
                System.currentTimeMillis() - t0,
            )
        }
    }

    override suspend fun route(request: RouteRequest): RoutingResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        fun fail(error: RoutingError, warnings: List<String> = emptyList()) =
            RoutingResult(request.id, id, emptyList(), error, System.currentTimeMillis() - t0, onDevice = true, warnings = warnings)

        val h = load() ?: return@withContext fail(RoutingError.Engine(loadError ?: "no offline graph installed"))
        try {
            val plan = plan(request)
            val rsp: GHResponse = h.route(plan.request)
            if (rsp.hasErrors()) return@withContext fail(toError(request, rsp.errors), plan.warnings)
            val translation = h.translationMap.getWithFallBack(Locale.US)
            val routes = rsp.all.mapIndexed { i, path -> toRoute(request, path, i) { ins -> ins.getTurnDescription(translation) } }
            if (routes.isEmpty()) return@withContext fail(RoutingError.NoRoute("GraphHopper returned no path"), plan.warnings)
            RoutingResult(request.id, id, routes, null, System.currentTimeMillis() - t0, onDevice = true, warnings = plan.warnings)
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            fail(RoutingError.Engine("${t::class.simpleName}: ${t.message}"))
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Request translation

    private class Plan(val request: GHRequest, val warnings: List<String>)

    private fun plan(request: RouteRequest): Plan {
        val warnings = ArrayList<String>()
        val wps = request.waypoints
        val req = GHRequest().setProfile(GhProfile.NAME).setLocale(Locale.US)
        wps.forEach { req.addPoint(GHPoint(it.point.lat, it.point.lon)) }
        req.putHint(Parameters.Routing.INSTRUCTIONS, true)
        req.putHint(Parameters.Routing.CALC_POINTS, true)
        req.setPathDetails(PATH_DETAILS)

        val straightLine = wps.zipWithNext { a, b -> a.point.distanceTo(b.point) }.sum()
        val tooLong = straightLine > flexibleMaxStraightLineMeters
        val km = "%.0f".format(straightLine / 1000)

        // Avoid options and zones → request custom model (forces flexible mode).
        val model = buildCustomModel(request.options, request.avoidZones)
        var flexible = false
        if (model != null) {
            if (tooLong && request.avoidZones.isEmpty()) {
                warnings += "avoid options ignored (trip of $km km too long for flexible routing on device)"
            } else {
                if (tooLong) warnings += "avoid zones honoured on a $km km trip: flexible routing on device may be slow"
                req.customModel = model
                flexible = true
            }
        }

        // Alternatives: only start→end, flexible mode.
        val wantAlts = request.options.alternatives > 0 && wps.size == 2
        if (wantAlts) {
            if (tooLong) {
                warnings += "alternatives skipped (trip of $km km too long for flexible routing on device)"
            } else {
                req.algorithm = Parameters.Algorithms.ALT_ROUTE
                req.putHint(Parameters.Algorithms.AltRoute.MAX_PATHS, request.options.alternatives + 1)
                req.putHint(Parameters.Algorithms.AltRoute.MAX_WEIGHT, ALT_MAX_WEIGHT_FACTOR)
                req.putHint(Parameters.Algorithms.AltRoute.MAX_SHARE, ALT_MAX_SHARE_FACTOR)
                flexible = true
            }
        }

        // THROUGH/VIA intermediates: GraphHopper has no per-point semantics; pass_through forbids U-turns at all
        // intermediates (closest approximation, same as the old HTTP adapter). Needs flexible mode.
        val hasThrough = request.intermediate.any { it.type == WaypointType.THROUGH || it.type == WaypointType.VIA || it.type == WaypointType.STOP_THROUGH }
        if (hasThrough) {
            if (tooLong) {
                warnings += "through points treated as plain via points (trip of $km km too long for flexible routing on device)"
            } else {
                req.putHint(Parameters.Routing.PASS_THROUGH, true)
                warnings += "through points approximated with pass_through (U-turns forbidden at every intermediate)"
                flexible = true
            }
        }

        // Headings: speed mode cannot use them.
        if (wps.all { it.heading != null }) {
            if (flexible) req.setHeadings(wps.map { it.heading!! })
            else warnings += "headings ignored (not supported in speed mode)"
        } else if (wps.any { it.heading != null }) {
            warnings += "headings ignored (GraphHopper needs a heading for every point or none)"
        }

        if (flexible) req.putHint(Parameters.CH.DISABLE, true)
        return Plan(req, warnings)
    }

    /** Priority rules of the old HTTP adapter, now evaluated by [InterpretedCustomModel]. Null when nothing to add. */
    internal fun buildCustomModel(options: RoutingOptions, zones: List<AvoidZone>): CustomModel? {
        val model = CustomModel()
        var any = false
        fun rule(condition: String, factor: String) { model.addToPriority(Statement.If(condition, Statement.Op.MULTIPLY, factor)); any = true }
        if (options.avoidTolls) rule("toll == ALL", "0.1")
        if (options.avoidFerries) rule("road_environment == FERRY", "0.1")
        if (options.avoidUnpaved) rule(UNPAVED_SURFACES.joinToString(" || ") { "surface == $it" }, "0.1")
        when (options.highways) {
            HighwayPreference.AVOID -> rule("road_class == MOTORWAY", "0.1")
            HighwayPreference.PREFER -> rule("road_class != MOTORWAY", "0.8")
            HighwayPreference.NEUTRAL -> Unit
        }
        if (zones.isNotEmpty()) {
            val areas = JsonFeatureCollection()
            zones.forEachIndexed { i, zone ->
                val areaId = "$AVOID_AREA_PREFIX$i"
                areas.features += JsonFeature(areaId, "Feature", null, toPolygon(zone), emptyMap())
                rule("in_$areaId", "0")
            }
            model.addAreas(areas)
        }
        return if (any) model else null
    }

    private fun toPolygon(zone: AvoidZone): org.locationtech.jts.geom.Polygon {
        val ring = zone.ring
        require(ring.size >= 3) { "avoid zone ${zone.id} needs at least 3 points" }
        val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
        val coords = closed.map { Coordinate(it.lon, it.lat) }.toTypedArray()
        return GEOMETRY_FACTORY.createPolygon(coords)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Response translation

    private fun toError(request: RouteRequest, errors: List<Throwable>): RoutingError {
        val msg = errors.joinToString("; ") { it.message ?: it::class.simpleName ?: "error" }
        for (e in errors) {
            when (e) {
                is ConnectionNotFoundException -> return RoutingError.NoRoute(msg)
                is PointNotFoundException -> return RoutingError.Unreachable(msg, request.waypoints.getOrNull(e.pointIndex)?.id)
                is MaximumNodesExceededException -> return RoutingError.Engine(msg)
            }
        }
        return when {
            msg.contains("Connection between locations not found") -> RoutingError.NoRoute(msg)
            msg.contains("Cannot find point") || msg.contains("out of bounds") -> RoutingError.Unreachable(msg)
            else -> RoutingError.Engine(msg)
        }
    }

    private fun toRoute(request: RouteRequest, path: ResponsePath, alt: Int, translate: (Instruction) -> String): Route {
        val pts = path.points
        val geometry = (0 until pts.size()).map { LatLng(pts.getLat(it), pts.getLon(it)) }
        val instructions = path.instructions.toList()

        // Shape index of each instruction's first point, matched against the full geometry so duplicated junction
        // points between legs cannot desynchronise the cursor.
        val begins = IntArray(instructions.size)
        var cursor = 0
        instructions.forEachIndexed { i, ins ->
            val p = ins.points
            if (p.size() > 0) cursor = indexOfPoint(geometry, cursor, p.getLat(0), p.getLon(0))
            begins[i] = cursor.coerceAtMost(geometry.lastIndex.coerceAtLeast(0))
        }
        val details = path.pathDetails
        val tollSpans = spans(details["toll"]) { it.equals("ALL", true) || it.equals("HGV", true) }
        val ferrySpans = spans(details["road_environment"]) { it.equals("FERRY", true) }
        val motorwaySpans = spans(details["road_class"]) { it.equals("MOTORWAY", true) }
        val unpavedSpans = spans(details["surface"]) { s -> UNPAVED_SURFACES.any { it.equals(s, true) } }

        val legs = ArrayList<RouteLeg>()
        var legManeuvers = ArrayList<Maneuver>()
        var viaCount = 0
        instructions.forEachIndexed { i, ins ->
            val begin = begins[i]
            val end = if (i < instructions.lastIndex) begins[i + 1] else geometry.lastIndex.coerceAtLeast(0)
            var splitLeg = false
            val type = when {
                i == 0 && ins.sign == Instruction.CONTINUE_ON_STREET -> ManeuverType.DEPART
                ins.sign == Instruction.REACHED_VIA -> {
                    val wp = request.intermediate.getOrNull(viaCount++)
                    val isStop = wp == null || wp.type == WaypointType.STOP || wp.type == WaypointType.STOP_THROUGH
                    splitLeg = isStop
                    if (isStop) ManeuverType.WAYPOINT_STOP else ManeuverType.WAYPOINT_THROUGH
                }
                else -> GraphHopperSigns.toManeuverType(ins.sign)
            }
            val name = ins.name?.takeIf { it.isNotBlank() }
            legManeuvers += Maneuver(
                type = type,
                instruction = translate(ins),
                streetNames = listOfNotNull(name),
                distanceMeters = ins.distance,
                durationSeconds = ins.time / 1000.0,
                beginShapeIndex = begin,
                endShapeIndex = end,
                roundaboutExitCount = (ins.extraInfoJSON["exit_number"] as? Number)?.toInt(),
                hasToll = overlaps(tollSpans, begin, end),
                hasFerry = overlaps(ferrySpans, begin, end),
                hasHighway = overlaps(motorwaySpans, begin, end),
            )
            if (splitLeg || i == instructions.lastIndex) {
                legs += RouteLeg(
                    beginShapeIndex = legManeuvers.first().beginShapeIndex,
                    endShapeIndex = legManeuvers.last().endShapeIndex,
                    distanceMeters = legManeuvers.sumOf { it.distanceMeters },
                    durationSeconds = legManeuvers.sumOf { it.durationSeconds },
                    maneuvers = legManeuvers,
                )
                legManeuvers = ArrayList()
            }
        }
        if (legs.isEmpty()) {
            legs += RouteLeg(0, geometry.lastIndex.coerceAtLeast(0), path.distance, path.time / 1000.0, emptyList())
        }
        val maneuvers = legs.flatMap { it.maneuvers }
        val majorRoads = maneuvers.filter { it.streetNames.isNotEmpty() }
            .groupBy { it.streetNames.first() }
            .mapValues { e -> e.value.sumOf { it.distanceMeters } }
            .entries.sortedByDescending { it.value }.take(3).map { it.key }
        return Route(
            id = Ids.newId("ghlocal"), requestId = request.id, engine = id, geometry = geometry, legs = legs,
            distanceMeters = path.distance, durationSeconds = path.time / 1000.0,
            summary = RouteSummary(
                hasTolls = tollSpans.isNotEmpty(), hasFerry = ferrySpans.isNotEmpty(),
                hasHighway = motorwaySpans.isNotEmpty(), hasUnpaved = unpavedSpans.isNotEmpty(), majorRoads = majorRoads,
            ),
            alternativeIndex = alt, label = if (alt == 0) null else "alternative $alt (on-device)",
        )
    }

    private fun indexOfPoint(geometry: List<LatLng>, from: Int, lat: Double, lon: Double): Int {
        for (i in from until geometry.size) {
            val g = geometry[i]
            if (abs(g.lat - lat) < EPS && abs(g.lon - lon) < EPS) return i
        }
        return from
    }

    private fun spans(details: List<PathDetail>?, match: (String) -> Boolean): List<IntRange> =
        details.orEmpty().filter { d -> d.value?.let { match(it.toString()) } == true }.map { it.first..it.last }

    private fun overlaps(spans: List<IntRange>, begin: Int, end: Int): Boolean =
        spans.any { it.first < end && it.last > begin }

    companion object {
        const val DEFAULT_FLEXIBLE_MAX_STRAIGHT_LINE_METERS = 120_000.0
        const val ALT_MAX_WEIGHT_FACTOR = 1.4
        const val ALT_MAX_SHARE_FACTOR = 0.6
        const val AVOID_AREA_PREFIX = "hansel_avoid_"
        val UNPAVED_SURFACES = listOf("UNPAVED", "COMPACTED", "FINE_GRAVEL", "GRAVEL", "GROUND", "DIRT", "GRASS", "SAND")
        private val PATH_DETAILS = listOf("toll", "road_environment", "road_class", "surface")
        private val GEOMETRY_FACTORY = GeometryFactory()
        private const val EPS = 1e-7
    }
}
