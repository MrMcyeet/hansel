package dev.hansel.routing.graphhopper

import dev.hansel.model.EngineId
import dev.hansel.model.GeoMath
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
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType
import dev.hansel.routing.EngineCapabilities
import dev.hansel.routing.EngineHealth
import dev.hansel.routing.RoutingEngine
import dev.hansel.routing.graphhopper.dto.GraphHopperInfoResponse
import dev.hansel.routing.graphhopper.dto.GraphHopperPath
import dev.hansel.routing.graphhopper.dto.GraphHopperRouteRequest
import dev.hansel.routing.graphhopper.dto.GraphHopperRouteResponse
import dev.hansel.routing.graphhopper.dto.graphHopperJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Adapter for a GraphHopper server (`POST /route`, `/health`, `/info`). See DESIGN §5.2 / §6.5.
 *
 * GraphHopper's waypoint vocabulary is thinner than Valhalla's, so several semantics are approximated and surfaced
 * as [RoutingResult.warnings]:
 *  - Every intermediate point is a "via" that may generate a REACHED_VIA instruction. [WaypointType.THROUGH] /
 *    [WaypointType.STOP_THROUGH] (no U-turn) are approximated with the request-wide `pass_through=true`, which then
 *    also applies to STOP/VIA points in the same request. Which via points get an arrival instruction is controlled
 *    with `announced_via_points` (stops only); legs are split at those REACHED_VIA instructions.
 *  - Avoidances (tolls, ferries, motorways, unpaved) are expressed as a `custom_model` priority rule set, which forces
 *    `ch.disable=true` (flexible mode, slower).
 *  - Native alternatives (`algorithm=alternative_route`) only exist for two-point requests.
 *  - Corridor locks are expanded into intermediate points by [expandLocks] (straight-line heuristic).
 */
class GraphHopperEngine(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val profile: String = "car",
    private val apiKey: String? = null,
) : RoutingEngine {
    private val baseUrl = baseUrl.trimEnd('/')

    override val id = EngineId.GRAPHHOPPER
    override val capabilities = EngineCapabilities(
        nativeAlternatives = true,
        /** Approximated via request-wide `pass_through`; the lock controller may prefer sub-requests. */
        throughPoints = false,
        viaPoints = true, avoidPolygons = true, customEdgeCosts = false, onDevice = false,
    )

    // ------------------------------------------------------------------ health

    /** GET `/health` (200 = graph loaded); older servers lack it, so fall back to `/info`. */
    override suspend fun healthCheck(): EngineHealth = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        try {
            client.newCall(get("/health")).execute().use { resp ->
                if (resp.isSuccessful) return@withContext EngineHealth(true, "graphhopper healthy", elapsed())
                if (resp.code != 404 && resp.code != 405) return@withContext EngineHealth(false, "HTTP ${resp.code} from /health", elapsed())
            }
            client.newCall(get("/info")).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) return@withContext EngineHealth(false, "HTTP ${resp.code} from /info", elapsed())
                val info = runCatching { graphHopperJson.decodeFromString(GraphHopperInfoResponse.serializer(), body) }.getOrNull()
                val detail = buildString {
                    append("graphhopper")
                    info?.version?.let { append(" ").append(it) }
                    (info?.importDate ?: info?.buildDate)?.let { append(", data ").append(it) }
                }
                EngineHealth(true, detail, elapsed())
            }
        } catch (e: IOException) {
            EngineHealth(false, "unreachable: ${e.message ?: e.javaClass.simpleName}", elapsed())
        }
    }

    // ------------------------------------------------------------------ route

    override suspend fun route(request: RouteRequest): RoutingResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun fail(error: RoutingError, warnings: List<String> = emptyList()) =
            RoutingResult(request.id, id, emptyList(), error, elapsed(), onDevice = false, warnings = warnings)

        val warnings = ArrayList<String>()
        val plan = try {
            buildRequest(request, warnings)
        } catch (e: Exception) {
            return fail(RoutingError.Engine("could not build GraphHopper request: ${e.message}"))
        }
        val json = graphHopperJson.encodeToString(GraphHopperRouteRequest.serializer(), plan.wire)

        val http = withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(withKey("$baseUrl/route")).post(json.toRequestBody(JSON_MEDIA_TYPE))
                client.newCall(builder.build()).execute().use { resp -> HttpOutcome.Ok(resp.code, resp.body.string()) }
            } catch (e: IOException) {
                HttpOutcome.Failed(e)
            }
        }
        return when (http) {
            is HttpOutcome.Failed -> fail(RoutingError.Network(http.cause.message ?: http.cause.javaClass.simpleName), warnings)
            is HttpOutcome.Ok -> {
                val parsed = try {
                    graphHopperJson.decodeFromString(GraphHopperRouteResponse.serializer(), http.body)
                } catch (e: SerializationException) {
                    return fail(RoutingError.Engine("unparseable GraphHopper response (HTTP ${http.code}): ${e.message?.take(200)}", http.code), warnings)
                } catch (e: IllegalArgumentException) {
                    return fail(RoutingError.Engine("unparseable GraphHopper response (HTTP ${http.code}): ${e.message?.take(200)}", http.code), warnings)
                }
                if (http.code !in 200..299 || parsed.message != null || parsed.paths.isEmpty()) {
                    return fail(mapError(http.code, parsed, plan.waypoints), warnings)
                }
                val routes = try {
                    parsed.paths.mapIndexed { i, path -> toRoute(request, path, i, plan, warnings) }
                } catch (e: Exception) {
                    return fail(RoutingError.Engine("could not convert GraphHopper path: ${e.message}"), warnings)
                }
                RoutingResult(request.id, id, routes, null, elapsed(), onDevice = false, warnings = warnings.distinct())
            }
        }
    }

    private sealed class HttpOutcome {
        data class Ok(val code: Int, val body: String) : HttpOutcome()
        data class Failed(val cause: IOException) : HttpOutcome()
    }

    private fun get(path: String) = Request.Builder().url(withKey("$baseUrl$path")).get().build()

    private fun withKey(url: String): String =
        if (apiKey.isNullOrBlank()) url else url + (if ('?' in url) "&" else "?") + "key=$apiKey"

    // ------------------------------------------------------------------ request mapping

    /** The wire request plus what we need to interpret the answer (which engine points are stops). */
    internal class Plan(val wire: GraphHopperRouteRequest, val waypoints: List<Waypoint>) {
        val intermediate: List<Waypoint> get() = waypoints.subList(1, waypoints.size - 1)
    }

    internal fun buildRequest(request: RouteRequest, warnings: MutableList<String>): Plan {
        val waypoints = expandLocks(request)
        val intermediate = waypoints.subList(1, waypoints.size - 1)
        val o = request.options

        // --- waypoint semantics (approximated; see class doc)
        val noUturnPoints = intermediate.filter { it.type == WaypointType.THROUGH || it.type == WaypointType.STOP_THROUGH }
        val passThrough = noUturnPoints.isNotEmpty()
        if (passThrough) {
            val others = intermediate.size - noUturnPoints.size
            warnings += if (others == 0) {
                "GraphHopper approximates through-points with request-wide pass_through"
            } else {
                "GraphHopper approximates through-points with request-wide pass_through; U-turns are also suppressed at $others other via point(s)"
            }
        }
        val announced = intermediate.withIndex().filter { it.value.isStop }.map { it.index + 1 }

        // --- headings: GraphHopper takes either one (origin) or one per point; no per-point "unset" exists.
        val headings: List<Double>? = when {
            waypoints.all { it.heading != null } -> waypoints.map { it.heading!!.normalized() }
            waypoints.first().heading != null -> {
                if (waypoints.drop(1).any { it.heading != null }) warnings += "GraphHopper headings must be all-or-origin-only; only the origin heading was sent"
                listOf(waypoints.first().heading!!.normalized())
            }
            waypoints.any { it.heading != null } -> { warnings += "GraphHopper headings must be all-or-origin-only; headings were dropped"; null }
            else -> null
        }

        // --- alternatives: alternative_route only works between exactly two points.
        val alternatives = o.alternatives.coerceAtLeast(0)
        val wantAlternatives = alternatives > 0 && waypoints.size == 2
        if (alternatives > 0 && waypoints.size > 2) warnings += "GraphHopper cannot compute alternatives for multi-point routes; only the primary route will be returned"

        // --- custom model for avoidances and avoid zones (needs flexible mode).
        val customModel = buildCustomModel(o, request)

        val wire = GraphHopperRouteRequest(
            points = waypoints.map { listOf(it.point.lon, it.point.lat) },
            profile = profile,
            instructions = true, pointsEncoded = false, calcPoints = true, elevation = false,
            locale = o.language.replace('-', '_'),
            details = listOf("road_class", "toll", "road_environment"),
            passThrough = if (passThrough) true else null,
            headings = headings,
            announcedViaPoints = if (intermediate.isEmpty()) null else announced,
            algorithm = if (wantAlternatives) "alternative_route" else null,
            alternativeMaxPaths = if (wantAlternatives) alternatives + 1 else null,
            alternativeMaxWeightFactor = if (wantAlternatives) ALT_MAX_WEIGHT_FACTOR else null,
            alternativeMaxShareFactor = if (wantAlternatives) ALT_MAX_SHARE_FACTOR else null,
            chDisable = if (wantAlternatives || customModel != null) true else null,
            customModel = customModel,
        )
        return Plan(wire, waypoints)
    }

    /**
     * Priority rules: `multiply_by` 0.1 is a strong-but-not-absolute avoidance (parity with Valhalla's `use_*=0`,
     * which also does not guarantee avoidance); avoid zones use 0 so they are hard blocks. "Prefer highways" cannot
     * raise priority above 1, so it is expressed by slightly demoting everything that is not a motorway/trunk.
     * Returns null when nothing needs customizing so CH stays enabled.
     */
    internal fun buildCustomModel(o: RoutingOptions, request: RouteRequest): JsonObject? {
        val rules = ArrayList<Pair<String, String>>()
        if (o.avoidTolls) rules += "toll == ALL" to SOFT_AVOID
        if (o.avoidFerries) rules += "road_environment == FERRY" to SOFT_AVOID
        if (o.avoidUnpaved) rules += UNPAVED_SURFACES.joinToString(" || ") { "surface == $it" } to SOFT_AVOID
        when (o.highways) {
            HighwayPreference.AVOID -> rules += "road_class == MOTORWAY" to SOFT_AVOID
            HighwayPreference.PREFER -> rules += "road_class != MOTORWAY && road_class != TRUNK" to "0.8"
            HighwayPreference.NEUTRAL -> Unit
        }
        val zones = request.avoidZones.filter { it.ring.size >= 3 }
        zones.forEachIndexed { i, _ -> rules += "in_${areaId(i)}" to "0" }
        if (rules.isEmpty()) return null

        return buildJsonObject {
            putJsonArray("priority") {
                for ((condition, factor) in rules) addJsonObject { put("if", condition); put("multiply_by", factor) }
            }
            if (zones.isNotEmpty()) {
                putJsonObject("areas") {
                    put("type", "FeatureCollection")
                    putJsonArray("features") {
                        zones.forEachIndexed { i, zone ->
                            addJsonObject {
                                put("type", "Feature")
                                put("id", areaId(i))
                                putJsonObject("properties") { put("label", zone.label ?: zone.id) }
                                putJsonObject("geometry") {
                                    put("type", "Polygon")
                                    putJsonArray("coordinates") {
                                        add(buildJsonArray {
                                            val ring = if (zone.ring.first() == zone.ring.last()) zone.ring else zone.ring + zone.ring.first()
                                            for (p in ring) add(buildJsonArray { add(p.lon); add(p.lat) })
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Area ids must be valid identifiers (they become `in_<id>` variables), so we do not use the zone's own id. */
    private fun areaId(index: Int) = "hansel_avoid_$index"

    /**
     * Corridor-lock heuristic (same as the Valhalla adapter). Each lock's anchors become consecutive THROUGH points
     * placed by projecting the lock's first anchor onto the straight-line chord through the request's waypoints and
     * inserting after the waypoint that precedes that projection. For origin→destination requests this is simply
     * "between origin and destination, ordered by distance from origin". Anchors of one lock stay contiguous and
     * ordered. The lock controller validates afterwards that the route really traverses every anchor.
     */
    internal fun expandLocks(request: RouteRequest): List<Waypoint> {
        if (request.locks.isEmpty()) return request.waypoints
        val chord = request.waypoints.map { it.point }
        val cum = GeoMath.cumulativeDistances(chord)
        data class Positioned(val along: Double, val order: Int, val wp: Waypoint)
        val positioned = ArrayList<Positioned>()
        request.waypoints.forEachIndexed { i, wp -> positioned += Positioned(cum[i], 0, wp) }
        request.locks.forEachIndexed { li, lock ->
            val first = lock.anchors.firstOrNull() ?: return@forEachIndexed
            val along = GeoMath.projectOntoPolyline(first, chord, cum)?.alongMeters ?: 0.0
            lock.anchors.forEachIndexed { ai, anchor ->
                positioned += Positioned(along, 1 + li * 10_000 + ai, Waypoint(
                    id = "${lock.id}#$ai", point = anchor, type = WaypointType.THROUGH, name = lock.label,
                    radiusMeters = lock.toleranceMeters.toInt().coerceAtLeast(10), isShaping = true,
                ))
            }
        }
        val origin = positioned.first(); val dest = positioned[request.waypoints.lastIndex]
        val middle = positioned.filter { it !== origin && it !== dest }.sortedWith(compareBy({ it.along }, { it.order }))
        return listOf(origin.wp) + middle.map { it.wp } + dest.wp
    }

    // ------------------------------------------------------------------ response mapping

    internal fun toRoute(request: RouteRequest, path: GraphHopperPath, alternativeIndex: Int, plan: Plan, warnings: MutableList<String>): Route {
        val geometry = path.points.coordinates.map { c -> LatLng(c[1], c[0]) }
        val lastIndex = (geometry.size - 1).coerceAtLeast(0)
        val instructions = path.instructions

        // Which REACHED_VIA instructions end a leg. With `announced_via_points` only stops produce one; an older
        // server announces every via point. Match whichever count we got; otherwise give up on leg splitting.
        val viaCount = instructions.count { it.sign == GraphHopperSigns.REACHED_VIA }
        val intermediate = plan.intermediate
        val stops = intermediate.count { it.isStop }
        val viaIsStop: List<Boolean> = when (viaCount) {
            intermediate.size -> intermediate.map { it.isStop }
            stops -> List(viaCount) { true }
            else -> { if (viaCount > 0) warnings += "GraphHopper returned $viaCount via arrivals for ${intermediate.size} via points; legs not split"; List(viaCount) { false } }
        }

        val legs = ArrayList<RouteLeg>()
        var legManeuvers = ArrayList<Maneuver>()
        var legBegin = 0
        var legDist = 0.0; var legTime = 0L
        var viaSeen = 0
        val roadDistance = LinkedHashMap<String, Double>()
        var anyFerry = false

        instructions.forEachIndexed { i, ins ->
            val begin = ins.interval.getOrNull(0)?.coerceIn(0, lastIndex) ?: 0
            val end = ins.interval.getOrNull(1)?.coerceIn(begin, lastIndex) ?: begin
            val isVia = ins.sign == GraphHopperSigns.REACHED_VIA
            val viaStop = isVia && viaIsStop.getOrElse(viaSeen) { false }
            val type = when {
                i == 0 && ins.sign == GraphHopperSigns.CONTINUE_ON_STREET -> ManeuverType.DEPART
                isVia -> if (viaStop) ManeuverType.ARRIVE else ManeuverType.WAYPOINT_THROUGH
                else -> GraphHopperSigns.toManeuverType(ins.sign)
            }
            if (ins.sign == GraphHopperSigns.FERRY) anyFerry = true
            if (ins.distance > 0 && ins.streetName.isNotBlank()) roadDistance.merge(ins.streetName, ins.distance, Double::plus)
            legManeuvers += Maneuver(
                type = type,
                instruction = ins.text,
                streetNames = if (ins.streetName.isBlank()) emptyList() else listOf(ins.streetName),
                distanceMeters = ins.distance,
                durationSeconds = ins.time / 1000.0,
                beginShapeIndex = begin,
                endShapeIndex = end,
                roundaboutExitCount = ins.exitNumber, // only present on USE_ROUNDABOUT instructions
                hasFerry = ins.sign == GraphHopperSigns.FERRY,
            )
            legDist += ins.distance; legTime += ins.time
            val closesLeg = viaStop || ins.sign == GraphHopperSigns.FINISH || i == instructions.lastIndex
            if (isVia) viaSeen++
            if (closesLeg) {
                val legEnd = if (ins.sign == GraphHopperSigns.FINISH || i == instructions.lastIndex) lastIndex else end
                legs += RouteLeg(legBegin, legEnd, legDist, legTime / 1000.0, legManeuvers)
                // Next leg starts at the shared vertex, like Valhalla's concatenated legs.
                legBegin = legEnd; legManeuvers = ArrayList(); legDist = 0.0; legTime = 0L
            }
        }
        if (legs.isEmpty()) {
            legs += RouteLeg(0, lastIndex, path.distance, path.time / 1000.0, legManeuvers)
        }

        val tolls = path.details["toll"].orEmpty().any { it.getOrNull(2).asStringOrNull() == "ALL" }
        val roadClasses = path.details["road_class"].orEmpty().mapNotNull { it.getOrNull(2).asStringOrNull()?.lowercase() }
        val environments = path.details["road_environment"].orEmpty().mapNotNull { it.getOrNull(2).asStringOrNull()?.lowercase() }
        val summary = RouteSummary(
            hasTolls = tolls,
            hasFerry = anyFerry || "ferry" in environments,
            hasHighway = "motorway" in roadClasses,
            hasUnpaved = "track" in roadClasses,
            majorRoads = roadDistance.entries.sortedByDescending { it.value }.take(3).map { it.key },
        )
        return Route(
            id = Ids.newId("graphhopper"), requestId = request.id, engine = id, geometry = geometry, legs = legs,
            distanceMeters = path.distance, durationSeconds = path.time / 1000.0, summary = summary,
            alternativeIndex = alternativeIndex,
            label = if (alternativeIndex == 0) null else summary.majorRoads.firstOrNull()?.let { "via $it" },
            warnings = if (plan.wire.passThrough == true) listOf("through-points approximated with pass_through") else emptyList(),
        )
    }

    /**
     * GraphHopper errors are `{"message": "...", "hints": [{"message": "...", "details": "..."}]}`, usually HTTP 400.
     *  - "Connection between locations not found" → [RoutingError.NoRoute]
     *  - "Cannot find point N: lat,lon" / "Point N is out of bounds" → [RoutingError.Unreachable] with the waypoint id
     *  - anything else → [RoutingError.Engine] with the HTTP code.
     */
    internal fun mapError(httpCode: Int, parsed: GraphHopperRouteResponse, waypoints: List<Waypoint>): RoutingError {
        val hints = parsed.errorHints
        val base = parsed.message ?: hints.firstOrNull()?.message ?: "HTTP $httpCode"
        val details = hints.mapNotNull { it.details }.filter { it.isNotBlank() && it != base }
        val message = if (details.isEmpty()) base else "$base (${details.joinToString("; ")})"
        return when {
            base.contains("Connection between locations not found", ignoreCase = true) ||
                base.contains("No route found", ignoreCase = true) -> RoutingError.NoRoute(message)
            base.contains("Cannot find point", ignoreCase = true) || base.contains("out of bounds", ignoreCase = true) -> {
                val index = POINT_INDEX.find(base)?.groupValues?.get(1)?.toIntOrNull()
                RoutingError.Unreachable(message, index?.let { waypoints.getOrNull(it)?.id })
            }
            parsed.paths.isEmpty() && parsed.message == null && httpCode in 200..299 -> RoutingError.NoRoute("GraphHopper returned no paths")
            else -> RoutingError.Engine(message, httpCode)
        }
    }

    private val Waypoint.isStop: Boolean get() = type == WaypointType.STOP || type == WaypointType.STOP_THROUGH
    private fun Double.normalized() = (this % 360.0 + 360.0) % 360.0
    private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val POINT_INDEX = Regex("""point (\d+)""", RegexOption.IGNORE_CASE)
        const val ALT_MAX_WEIGHT_FACTOR = 1.4
        const val ALT_MAX_SHARE_FACTOR = 0.6
        const val SOFT_AVOID = "0.1"
        /** GraphHopper `Surface` enum members that are not paved. */
        val UNPAVED_SURFACES = listOf("UNPAVED", "COMPACTED", "FINE_GRAVEL", "GRAVEL", "GROUND", "DIRT", "GRASS", "SAND")
    }
}

/**
 * GraphHopper instruction `sign` codes, verified against `com.graphhopper.util.Instruction` (master) and
 * docs/web/api-doc.md:
 * UNKNOWN=-99, U_TURN_UNKNOWN=-98, U_TURN_LEFT=-8, KEEP_LEFT=-7, ROUNDABOUT_EXIT=-6 (deprecated, unused), TURN_SHARP_LEFT=-3,
 * TURN_LEFT=-2, TURN_SLIGHT_LEFT=-1, CONTINUE_ON_STREET=0, TURN_SLIGHT_RIGHT=1, TURN_RIGHT=2, TURN_SHARP_RIGHT=3,
 * FINISH=4, REACHED_VIA=5, ROUNDABOUT_USE=6, KEEP_RIGHT=7, U_TURN_RIGHT=8, FERRY=9, PT_START_TRIP=101,
 * PT_TRANSFER=102, PT_END_TRIP=103, IGNORE=Integer.MIN_VALUE.
 */
object GraphHopperSigns {
    const val UNKNOWN = -99
    const val U_TURN_UNKNOWN = -98
    const val U_TURN_LEFT = -8
    const val KEEP_LEFT = -7
    const val ROUNDABOUT_EXIT = -6
    const val TURN_SHARP_LEFT = -3
    const val TURN_LEFT = -2
    const val TURN_SLIGHT_LEFT = -1
    const val CONTINUE_ON_STREET = 0
    const val TURN_SLIGHT_RIGHT = 1
    const val TURN_RIGHT = 2
    const val TURN_SHARP_RIGHT = 3
    const val FINISH = 4
    const val REACHED_VIA = 5
    const val USE_ROUNDABOUT = 6
    const val KEEP_RIGHT = 7
    const val U_TURN_RIGHT = 8
    const val FERRY = 9

    /** Context-free mapping; the engine special-cases the first CONTINUE (→ DEPART) and REACHED_VIA (stop vs through). */
    fun toManeuverType(sign: Int): ManeuverType = when (sign) {
        U_TURN_UNKNOWN, U_TURN_LEFT -> ManeuverType.UTURN_LEFT // side unknown: assume right-hand traffic
        KEEP_LEFT -> ManeuverType.KEEP_LEFT
        ROUNDABOUT_EXIT -> ManeuverType.ROUNDABOUT_EXIT
        TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        TURN_LEFT -> ManeuverType.TURN_LEFT
        TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT
        CONTINUE_ON_STREET -> ManeuverType.CONTINUE
        TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT
        TURN_RIGHT -> ManeuverType.TURN_RIGHT
        TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
        FINISH -> ManeuverType.ARRIVE
        REACHED_VIA -> ManeuverType.WAYPOINT_STOP
        USE_ROUNDABOUT -> ManeuverType.ROUNDABOUT_ENTER
        KEEP_RIGHT -> ManeuverType.KEEP_RIGHT
        U_TURN_RIGHT -> ManeuverType.UTURN_RIGHT
        FERRY -> ManeuverType.FERRY_ENTER
        else -> ManeuverType.UNKNOWN
    }
}
