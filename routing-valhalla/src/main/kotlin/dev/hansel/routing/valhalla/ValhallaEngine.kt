package dev.hansel.routing.valhalla

import dev.hansel.model.EngineId
import dev.hansel.model.GeoMath
import dev.hansel.model.HighwayPreference
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import dev.hansel.model.Polyline
import dev.hansel.model.Route
import dev.hansel.model.RouteLeg
import dev.hansel.model.RouteRequest
import dev.hansel.model.RouteSummary
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingResult
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType
import dev.hansel.routing.EngineCapabilities
import dev.hansel.routing.EngineHealth
import dev.hansel.routing.RoutingEngine
import dev.hansel.routing.valhalla.dto.ValhallaAutoCostingOptions
import dev.hansel.routing.valhalla.dto.ValhallaDateTime
import dev.hansel.routing.valhalla.dto.ValhallaDirectionsOptions
import dev.hansel.routing.valhalla.dto.ValhallaLeg
import dev.hansel.routing.valhalla.dto.ValhallaLocation
import dev.hansel.routing.valhalla.dto.ValhallaRouteRequest
import dev.hansel.routing.valhalla.dto.ValhallaRouteResponse
import dev.hansel.routing.valhalla.dto.ValhallaStatusResponse
import dev.hansel.routing.valhalla.dto.ValhallaTrip
import dev.hansel.routing.valhalla.dto.valhallaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Adapter for a Valhalla server (`/route`, `/status`). See DESIGN §5.2 / §6.5.
 *
 * Waypoint semantics map 1:1 onto Valhalla location types (`break`, `through`, `via`, `break_through`), so this is the
 * engine with the most faithful shaping behaviour. Approximations are documented inline:
 *  - corridor locks are expanded into `through` points by [expandLocks] (a straight-line heuristic);
 *  - `alternates` is only honoured by Valhalla on 2-location, non time-dependent trips; we still send it and attach a
 *    warning when the request cannot get alternatives.
 */
class ValhallaEngine(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val costing: String = "auto",
) : RoutingEngine {
    private val baseUrl = baseUrl.trimEnd('/')

    override val id = EngineId.VALHALLA
    override val capabilities = EngineCapabilities(
        nativeAlternatives = true, throughPoints = true, viaPoints = true,
        avoidPolygons = true, customEdgeCosts = false, onDevice = false,
    )

    // ------------------------------------------------------------------ health

    override suspend fun healthCheck(): EngineHealth = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url("$baseUrl/status").get().build()).execute().use { resp ->
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    return@withContext EngineHealth(false, "HTTP ${resp.code} from /status", elapsed)
                }
                val status = runCatching { valhallaJson.decodeFromString(ValhallaStatusResponse.serializer(), body) }.getOrNull()
                val detail = buildString {
                    append("valhalla")
                    status?.version?.let { append(" ").append(it) }
                    status?.tilesetLastModified?.let { append(", tiles ").append(Instant.ofEpochSecond(it)) }
                }
                EngineHealth(true, detail, elapsed)
            }
        } catch (e: IOException) {
            EngineHealth(false, "unreachable: ${e.message ?: e.javaClass.simpleName}", (System.nanoTime() - started) / 1_000_000)
        }
    }

    // ------------------------------------------------------------------ route

    override suspend fun route(request: RouteRequest): RoutingResult {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun fail(error: RoutingError, warnings: List<String> = emptyList()) =
            RoutingResult(request.id, id, emptyList(), error, elapsed(), onDevice = false, warnings = warnings)

        val warnings = ArrayList<String>()
        val wire = try {
            buildRequest(request, warnings)
        } catch (e: Exception) {
            return fail(RoutingError.Engine("could not build Valhalla request: ${e.message}"))
        }
        val json = valhallaJson.encodeToString(ValhallaRouteRequest.serializer(), wire)

        val http = withContext(Dispatchers.IO) {
            try {
                client.newCall(
                    Request.Builder().url("$baseUrl/route").post(json.toRequestBody(JSON_MEDIA_TYPE)).build(),
                ).execute().use { resp -> HttpOutcome.Ok(resp.code, resp.body.string()) }
            } catch (e: IOException) {
                HttpOutcome.Failed(e)
            }
        }
        return when (http) {
            is HttpOutcome.Failed -> fail(RoutingError.Network(http.cause.message ?: http.cause.javaClass.simpleName))
            is HttpOutcome.Ok -> {
                val parsed = try {
                    valhallaJson.decodeFromString(ValhallaRouteResponse.serializer(), http.body)
                } catch (e: SerializationException) {
                    return fail(RoutingError.Engine("unparseable Valhalla response (HTTP ${http.code}): ${e.message?.take(200)}", http.code))
                } catch (e: IllegalArgumentException) {
                    return fail(RoutingError.Engine("unparseable Valhalla response (HTTP ${http.code}): ${e.message?.take(200)}", http.code))
                }
                val trip = parsed.trip
                if (http.code !in 200..299 || trip == null || parsed.errorCode != null) {
                    return fail(mapError(http.code, parsed))
                }
                trip.warnings.forEach { w -> w.text?.let { warnings += "valhalla: $it" } }
                val routes = try {
                    listOf(toRoute(request, trip, 0)) + parsed.alternates.mapIndexed { i, alt -> toRoute(request, alt.trip, i + 1) }
                } catch (e: Exception) {
                    return fail(RoutingError.Engine("could not convert Valhalla trip: ${e.message}"))
                }
                RoutingResult(request.id, id, routes, null, elapsed(), onDevice = false, warnings = warnings)
            }
        }
    }

    private sealed class HttpOutcome {
        data class Ok(val code: Int, val body: String) : HttpOutcome()
        data class Failed(val cause: IOException) : HttpOutcome()
    }

    // ------------------------------------------------------------------ request mapping

    internal fun buildRequest(request: RouteRequest, warnings: MutableList<String>): ValhallaRouteRequest {
        val waypoints = expandLocks(request)
        val locations = waypoints.mapIndexed { i, wp ->
            // Valhalla always treats the first and last location as `break`; sending anything else is ignored.
            val type = if (i == 0 || i == waypoints.lastIndex) "break" else wp.type.toValhallaType()
            ValhallaLocation(
                lat = wp.point.lat, lon = wp.point.lon, type = type, name = wp.name,
                heading = wp.heading?.let { ((it % 360.0 + 360.0) % 360.0).toInt() },
                headingTolerance = if (wp.heading != null) DEFAULT_HEADING_TOLERANCE else null,
                radius = wp.radiusMeters,
            )
        }

        val o = request.options
        val costingOptions = ValhallaAutoCostingOptions(
            useTolls = if (o.avoidTolls) 0.0 else null,
            useFerry = if (o.avoidFerries) 0.0 else null,
            excludeUnpaved = if (o.avoidUnpaved) true else null,
            useHighways = when (o.highways) {
                HighwayPreference.AVOID -> 0.1
                HighwayPreference.PREFER -> 1.0
                HighwayPreference.NEUTRAL -> null
            },
        )
        val hasCostingOptions = costingOptions != ValhallaAutoCostingOptions()

        val polygons = request.avoidZones.mapNotNull { zone ->
            if (zone.ring.size < 3) return@mapNotNull null
            val ring = zone.ring.map { listOf(it.lon, it.lat) }
            // Valhalla wants closed exterior rings.
            if (ring.first() == ring.last()) ring else ring + listOf(ring.first())
        }

        val alternates = o.alternatives.coerceAtLeast(0)
        if (alternates > 0 && locations.size > 2) {
            warnings += "Valhalla does not produce alternates for multi-point routes; only the primary route will be returned"
        }
        val dateTime = request.departAtMillis?.let { millis ->
            if (alternates > 0) warnings += "Valhalla does not produce alternates for time-dependent routes"
            // Valhalla wants the origin's local time; the device zone is our best guess without a timezone lookup.
            ValhallaDateTime(type = 1, value = DATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())))
        }

        return ValhallaRouteRequest(
            locations = locations,
            costing = costing,
            costingOptions = if (hasCostingOptions) mapOf(costing to costingOptions) else null,
            units = "kilometers",
            language = o.language,
            directionsOptions = ValhallaDirectionsOptions(units = "kilometers", language = o.language),
            alternates = if (alternates > 0) alternates else null,
            excludePolygons = polygons.ifEmpty { null },
            dateTime = dateTime,
            shapeFormat = "polyline6",
            format = "json",
            id = request.id,
        )
    }

    /**
     * Corridor-lock heuristic. Each lock's anchors become consecutive `through` waypoints. Where in the waypoint list
     * they go is decided *without* a route: we project the lock's first anchor onto the straight-line chord through the
     * request's waypoints and insert the anchors after the waypoint that precedes that projection. For a plain
     * origin→destination request this degenerates to "between origin and destination, ordered by distance from the
     * origin", which is the common case. Anchors of one lock always stay contiguous and in their given order.
     * The caller (CorridorLockController) validates afterwards that the route really traverses every anchor.
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

    /** Concatenates all legs into one geometry, offsetting every leg's and maneuver's shape indices accordingly. */
    internal fun toRoute(request: RouteRequest, trip: ValhallaTrip, alternativeIndex: Int): Route {
        val geometry = ArrayList<LatLng>()
        val legs = ArrayList<RouteLeg>(trip.legs.size)
        val roadDistance = HashMap<String, Double>()
        var anyRough = false

        for (leg in trip.legs) {
            val shape = Polyline.decode(leg.shape, precision = 6)
            // Consecutive legs share their boundary vertex: leg N's last point == leg N+1's first point. Drop the
            // duplicate so the concatenated geometry has no zero-length segment, and offset indices by the index of
            // that shared vertex.
            val offset = if (geometry.isEmpty()) 0 else geometry.size - 1
            if (geometry.isNotEmpty() && shape.isNotEmpty()) geometry.addAll(shape.subList(1, shape.size)) else geometry.addAll(shape)
            val legEnd = (geometry.size - 1).coerceAtLeast(offset)

            val maneuvers = leg.maneuvers.map { m ->
                val streets = m.streetNames
                if (m.length > 0 && streets.isNotEmpty()) {
                    roadDistance.merge(streets.first(), m.length * 1000.0, Double::plus)
                }
                if (m.rough) anyRough = true
                Maneuver(
                    type = ValhallaManeuverTypes.toManeuverType(m.type),
                    instruction = m.instruction,
                    verbalPreTransition = m.verbalPreTransitionInstruction,
                    verbalPostTransition = m.verbalPostTransitionInstruction,
                    streetNames = streets,
                    distanceMeters = m.length * 1000.0,
                    durationSeconds = m.time,
                    beginShapeIndex = (m.beginShapeIndex + offset).coerceIn(offset, legEnd),
                    endShapeIndex = (m.endShapeIndex + offset).coerceIn(offset, legEnd),
                    bearingBefore = m.bearingBefore,
                    bearingAfter = m.bearingAfter,
                    roundaboutExitCount = m.roundaboutExitCount,
                    hasToll = m.toll, hasFerry = m.ferry, hasHighway = m.highway,
                )
            }
            legs += RouteLeg(
                beginShapeIndex = offset, endShapeIndex = legEnd,
                distanceMeters = leg.summary.length * 1000.0, durationSeconds = leg.summary.time,
                maneuvers = maneuvers,
            )
        }

        val legSummaries = trip.legs.map { it.summary }
        val summary = RouteSummary(
            hasTolls = trip.summary.hasToll || legSummaries.any { it.hasToll },
            hasFerry = trip.summary.hasFerry || legSummaries.any { it.hasFerry },
            hasHighway = trip.summary.hasHighway || legSummaries.any { it.hasHighway },
            hasUnpaved = anyRough,
            majorRoads = roadDistance.entries.sortedByDescending { it.value }.take(3).map { it.key },
        )
        val distance = if (trip.summary.length > 0) trip.summary.length * 1000.0 else legs.sumOf { it.distanceMeters }
        val duration = if (trip.summary.time > 0) trip.summary.time else legs.sumOf { it.durationSeconds }
        return Route(
            id = Ids.newId("valhalla"), requestId = request.id, engine = id, geometry = geometry, legs = legs,
            distanceMeters = distance, durationSeconds = duration, summary = summary,
            alternativeIndex = alternativeIndex,
            label = if (alternativeIndex == 0) null else summary.majorRoads.firstOrNull()?.let { "via $it" },
        )
    }

    /**
     * Valhalla error bodies look like `{"error_code":442,"error":"No path could be found for input","status_code":400}`.
     * Codes (from the API reference "Internal error codes" table):
     *  - 442 No path could be found for input → [RoutingError.NoRoute]
     *  - 170 Locations are in unconnected regions, 171 No suitable edges near location,
     *    440 Cannot reach destination, 441 Location is unreachable → [RoutingError.Unreachable]
     *  - anything else → [RoutingError.Engine] with the code.
     */
    internal fun mapError(httpCode: Int, parsed: ValhallaRouteResponse): RoutingError {
        val code = parsed.errorCode
        val message = parsed.error ?: parsed.status ?: "HTTP $httpCode"
        return when {
            code == 442 || message.contains("No path could be found", ignoreCase = true) -> RoutingError.NoRoute(message)
            code in UNREACHABLE_CODES || message.contains("No suitable edges", ignoreCase = true) ||
                message.contains("unreachable", ignoreCase = true) -> RoutingError.Unreachable(message)
            else -> RoutingError.Engine(message, code ?: httpCode)
        }
    }

    private fun WaypointType.toValhallaType(): String = when (this) {
        WaypointType.STOP -> "break"
        WaypointType.THROUGH -> "through"
        WaypointType.VIA -> "via"
        WaypointType.STOP_THROUGH -> "break_through"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val UNREACHABLE_CODES = setOf(170, 171, 440, 441)
        const val DEFAULT_HEADING_TOLERANCE = 60
    }
}

/**
 * Valhalla maneuver `type` integers, verified against the route API reference (TripLeg maneuver type table):
 * 0 None, 1 Start, 2 StartRight, 3 StartLeft, 4 Destination, 5 DestinationRight, 6 DestinationLeft, 7 Becomes,
 * 8 Continue, 9 SlightRight, 10 Right, 11 SharpRight, 12 UturnRight, 13 UturnLeft, 14 SharpLeft, 15 Left,
 * 16 SlightLeft, 17 RampStraight, 18 RampRight, 19 RampLeft, 20 ExitRight, 21 ExitLeft, 22 StayStraight,
 * 23 StayRight, 24 StayLeft, 25 Merge, 26 RoundaboutEnter, 27 RoundaboutExit, 28 FerryEnter, 29 FerryExit,
 * 30-36 transit (unused), 37 MergeRight, 38 MergeLeft, 39-43 elevator/steps/escalator/building (unused).
 */
object ValhallaManeuverTypes {
    fun toManeuverType(code: Int): ManeuverType = when (code) {
        1, 2, 3 -> ManeuverType.DEPART
        4 -> ManeuverType.ARRIVE
        5 -> ManeuverType.ARRIVE_RIGHT
        6 -> ManeuverType.ARRIVE_LEFT
        7 -> ManeuverType.BECOMES
        8 -> ManeuverType.CONTINUE
        9 -> ManeuverType.SLIGHT_RIGHT
        10 -> ManeuverType.TURN_RIGHT
        11 -> ManeuverType.SHARP_RIGHT
        12 -> ManeuverType.UTURN_RIGHT
        13 -> ManeuverType.UTURN_LEFT
        14 -> ManeuverType.SHARP_LEFT
        15 -> ManeuverType.TURN_LEFT
        16 -> ManeuverType.SLIGHT_LEFT
        17 -> ManeuverType.RAMP_STRAIGHT
        18 -> ManeuverType.RAMP_RIGHT
        19 -> ManeuverType.RAMP_LEFT
        20 -> ManeuverType.EXIT_RIGHT
        21 -> ManeuverType.EXIT_LEFT
        22 -> ManeuverType.KEEP_STRAIGHT
        23 -> ManeuverType.KEEP_RIGHT
        24 -> ManeuverType.KEEP_LEFT
        25 -> ManeuverType.MERGE
        26 -> ManeuverType.ROUNDABOUT_ENTER
        27 -> ManeuverType.ROUNDABOUT_EXIT
        28 -> ManeuverType.FERRY_ENTER
        29 -> ManeuverType.FERRY_EXIT
        37 -> ManeuverType.MERGE_RIGHT
        38 -> ManeuverType.MERGE_LEFT
        else -> ManeuverType.UNKNOWN
    }
}
