package dev.hansel.model

import kotlinx.serialization.Serializable

/** Which routing backend produced a result. Never use engine-specific edge IDs as a canonical key (see DESIGN §3). */
@Serializable
enum class EngineId {
    /** GraphHopper core running inside the app on a graph built on the phone — the self-contained default. */
    GRAPHHOPPER,
    /** Reserved for a future native on-device Valhalla build; no implementation ships today. */
    VALHALLA,
    /** Deterministic in-memory engine for tests and Demo mode. */
    FAKE,
}

/**
 * Waypoint semantics, following Valhalla's vocabulary (DESIGN §5.1, §5.2):
 * - [STOP] (`break`): a real stop with arrival guidance; legs split here.
 * - [THROUGH]: route passes through without stopping and without allowing a U-turn; no arrival guidance.
 * - [VIA]: like THROUGH but a U-turn is allowed at the point.
 * - [STOP_THROUGH] (`break_through`): stop with arrival guidance, but no U-turn allowed.
 */
@Serializable
enum class WaypointType { STOP, THROUGH, VIA, STOP_THROUGH }

@Serializable
data class Waypoint(
    val id: String,
    val point: LatLng,
    val type: WaypointType = WaypointType.STOP,
    val name: String? = null,
    /** Preferred approach heading in degrees, if the user shaped the route with a direction in mind. */
    val heading: Double? = null,
    /** Snap radius hint for the engine, meters. */
    val radiusMeters: Int? = null,
    /** True for points inserted by dragging or tapping the map rather than searching. They are dropped silently on reroute once passed. */
    val isShaping: Boolean = false,
)

/**
 * An ordered sequence of anchors the route must traverse in order (DESIGN §5.2 "corridor/road lock").
 * The corridor-lock controller expands this into through-points or sub-route requests, and validates the result.
 */
@Serializable
data class CorridorLock(
    val id: String,
    val anchors: List<LatLng>,
    val label: String? = null,
    /** Max distance between the resulting route and each anchor before the lock is considered violated. */
    val toleranceMeters: Double = 30.0,
)

@Serializable
data class AvoidZone(
    val id: String,
    /** Polygon ring; first point need not repeat at the end. */
    val ring: List<LatLng>,
    val label: String? = null,
)

@Serializable
enum class HighwayPreference { AVOID, NEUTRAL, PREFER }

@Serializable
data class RoutingOptions(
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val highways: HighwayPreference = HighwayPreference.NEUTRAL,
    /** Number of alternatives to ask the engine for (0 = none). */
    val alternatives: Int = 2,
    val language: String = "en-US",
    /** Honest observed-behavior ETA vs legal-speed-constrained profile (DESIGN design principle). Phase 5 wires the data. */
    val etaProfile: EtaProfile = EtaProfile.BASELINE,
)

@Serializable
enum class EtaProfile { BASELINE, OBSERVED_BEHAVIOR, LEGAL_SPEED_CONSTRAINED }

/**
 * Engine-independent route request (DESIGN §11 RouteRequest). The first and last [waypoints] are origin and
 * destination and must be [WaypointType.STOP].
 */
@Serializable
data class RouteRequest(
    val id: String,
    val waypoints: List<Waypoint>,
    val locks: List<CorridorLock> = emptyList(),
    val avoidZones: List<AvoidZone> = emptyList(),
    val options: RoutingOptions = RoutingOptions(),
    /** Epoch millis; null = now. */
    val departAtMillis: Long? = null,
    val issuedAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(waypoints.size >= 2) { "route needs an origin and a destination" }
    }
    val origin: Waypoint get() = waypoints.first()
    val destination: Waypoint get() = waypoints.last()
    val intermediate: List<Waypoint> get() = waypoints.subList(1, waypoints.size - 1)
    val stops: List<Waypoint> get() = waypoints.filter { it.type == WaypointType.STOP || it.type == WaypointType.STOP_THROUGH }
    val isCustomShaped: Boolean get() = intermediate.any { it.isShaping } || locks.isNotEmpty() || avoidZones.isNotEmpty()
}

@Serializable
enum class ManeuverType {
    DEPART, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT,
    CONTINUE, STRAIGHT, BECOMES,
    SLIGHT_LEFT, SLIGHT_RIGHT, TURN_LEFT, TURN_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT,
    KEEP_LEFT, KEEP_RIGHT, KEEP_STRAIGHT,
    MERGE, MERGE_LEFT, MERGE_RIGHT,
    RAMP_LEFT, RAMP_RIGHT, RAMP_STRAIGHT,
    EXIT_LEFT, EXIT_RIGHT,
    ROUNDABOUT_ENTER, ROUNDABOUT_EXIT,
    FERRY_ENTER, FERRY_EXIT,
    WAYPOINT_STOP, WAYPOINT_THROUGH,
    UNKNOWN;

    val isArrival: Boolean get() = this == ARRIVE || this == ARRIVE_LEFT || this == ARRIVE_RIGHT
}

@Serializable
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val verbalPreTransition: String? = null,
    val verbalPostTransition: String? = null,
    val streetNames: List<String> = emptyList(),
    /** Distance covered *by* this maneuver, i.e. from this maneuver's point to the next one. */
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Index into [Route.geometry] where this maneuver begins. */
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val bearingBefore: Double? = null,
    val bearingAfter: Double? = null,
    val roundaboutExitCount: Int? = null,
    val exitNumber: String? = null,
    val hasToll: Boolean = false,
    val hasFerry: Boolean = false,
    val hasHighway: Boolean = false,
)

/** A leg runs between two consecutive STOP/STOP_THROUGH waypoints. */
@Serializable
data class RouteLeg(
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuvers: List<Maneuver>,
)

@Serializable
data class RouteSummary(
    val hasTolls: Boolean = false,
    val hasFerry: Boolean = false,
    val hasHighway: Boolean = false,
    val hasUnpaved: Boolean = false,
    /** Most significant named roads in order, for the "via I-10 / US-49" line on route cards. */
    val majorRoads: List<String> = emptyList(),
)

/**
 * A routable result from one engine (DESIGN §11 RouteCandidate). [geometry] is the full shape; legs and maneuvers
 * index into it.
 */
@Serializable
data class Route(
    val id: String,
    val requestId: String,
    val engine: EngineId,
    val geometry: List<LatLng>,
    val legs: List<RouteLeg>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Engine's un-personalized duration when a personalized profile was applied; equal to [durationSeconds] otherwise. */
    val baseDurationSeconds: Double = durationSeconds,
    val summary: RouteSummary = RouteSummary(),
    /** Engine-specific edge identifiers when available; provenance only, never a canonical key. */
    val edgeIds: List<String> = emptyList(),
    /** 0..1 — how much we trust the ETA (sample size, traffic freshness, etc.). Null until Phase 5 computes it. */
    val confidence: Double? = null,
    /** Which alternative this was from the engine's perspective; 0 = the engine's preferred route. */
    val alternativeIndex: Int = 0,
    /** Short human label of what distinguishes it from the primary, e.g. "via US-90, avoids I-10". */
    val label: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val maneuvers: List<Maneuver> get() = legs.flatMap { it.maneuvers }
    val bounds: BoundingBox get() = BoundingBox.of(geometry)
}

@Serializable
sealed class RoutingError {
    abstract val message: String

    @Serializable data class NoRoute(override val message: String = "No route found") : RoutingError()
    @Serializable data class Unreachable(override val message: String, val waypointId: String? = null) : RoutingError()
    @Serializable data class LockViolated(override val message: String, val lockId: String, val anchorIndex: Int) : RoutingError()
    @Serializable data class Unsupported(override val message: String) : RoutingError()
    @Serializable data class Network(override val message: String) : RoutingError()
    @Serializable data class Engine(override val message: String, val code: Int? = null) : RoutingError()
}

/** Result of one engine answering one request. [routes] is ordered: index 0 is the engine's preferred route. */
@Serializable
data class RoutingResult(
    val requestId: String,
    val engine: EngineId,
    val routes: List<Route>,
    val error: RoutingError? = null,
    val latencyMillis: Long = 0,
    /** Whether the engine ran on this device; false for a LAN/dev-server endpoint. Never label a server route "offline". */
    val onDevice: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = error == null && routes.isNotEmpty()
    val primary: Route? get() = routes.firstOrNull()
}
