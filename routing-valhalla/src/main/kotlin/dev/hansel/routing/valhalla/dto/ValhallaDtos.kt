package dev.hansel.routing.valhalla.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for Valhalla's `/route` endpoint (https://valhalla.github.io/valhalla/api/route/api-reference/).
 * Only the fields Hansel reads or writes are modelled; everything else is ignored on decode.
 */
internal val valhallaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

// ---------------------------------------------------------------- request

@Serializable
data class ValhallaLocation(
    val lat: Double,
    val lon: Double,
    /** `break` | `through` | `via` | `break_through`. */
    val type: String = "break",
    val name: String? = null,
    /** Degrees, north-based clockwise. */
    val heading: Int? = null,
    @SerialName("heading_tolerance") val headingTolerance: Int? = null,
    /** Snap radius hint, meters. */
    val radius: Int? = null,
)

@Serializable
data class ValhallaAutoCostingOptions(
    @SerialName("use_tolls") val useTolls: Double? = null,
    @SerialName("use_ferry") val useFerry: Double? = null,
    @SerialName("use_highways") val useHighways: Double? = null,
    @SerialName("exclude_unpaved") val excludeUnpaved: Boolean? = null,
)

@Serializable
data class ValhallaDirectionsOptions(
    val units: String = "kilometers",
    val language: String = "en-US",
    @SerialName("directions_type") val directionsType: String = "instructions",
)

@Serializable
data class ValhallaDateTime(
    /** 0 = current, 1 = depart at, 2 = arrive by. */
    val type: Int,
    /** `YYYY-MM-DDTHH:MM` local to the origin. */
    val value: String,
)

@Serializable
data class ValhallaRouteRequest(
    val locations: List<ValhallaLocation>,
    val costing: String,
    @SerialName("costing_options") val costingOptions: Map<String, ValhallaAutoCostingOptions>? = null,
    val units: String = "kilometers",
    val language: String = "en-US",
    @SerialName("directions_options") val directionsOptions: ValhallaDirectionsOptions = ValhallaDirectionsOptions(),
    /** Number of alternate routes requested. Valhalla only honours this for 2-location, non time-dependent routes. */
    val alternates: Int? = null,
    /** Exterior rings as `[[lon, lat], ...]`, closed (first == last). */
    @SerialName("exclude_polygons") val excludePolygons: List<List<List<Double>>>? = null,
    @SerialName("date_time") val dateTime: ValhallaDateTime? = null,
    @SerialName("shape_format") val shapeFormat: String = "polyline6",
    val format: String = "json",
    val id: String? = null,
)

// ---------------------------------------------------------------- response

@Serializable
data class ValhallaSummary(
    /** Seconds. */
    val time: Double = 0.0,
    /** In the requested units: kilometers. */
    val length: Double = 0.0,
    @SerialName("has_toll") val hasToll: Boolean = false,
    @SerialName("has_highway") val hasHighway: Boolean = false,
    @SerialName("has_ferry") val hasFerry: Boolean = false,
)

@Serializable
data class ValhallaManeuver(
    /** Integer maneuver type; see [dev.hansel.routing.valhalla.ValhallaManeuverTypes]. */
    val type: Int = 0,
    val instruction: String = "",
    @SerialName("verbal_pre_transition_instruction") val verbalPreTransitionInstruction: String? = null,
    @SerialName("verbal_post_transition_instruction") val verbalPostTransitionInstruction: String? = null,
    @SerialName("street_names") val streetNames: List<String> = emptyList(),
    /** Seconds. */
    val time: Double = 0.0,
    /** Kilometers. */
    val length: Double = 0.0,
    @SerialName("begin_shape_index") val beginShapeIndex: Int = 0,
    @SerialName("end_shape_index") val endShapeIndex: Int = 0,
    @SerialName("roundabout_exit_count") val roundaboutExitCount: Int? = null,
    @SerialName("bearing_before") val bearingBefore: Double? = null,
    @SerialName("bearing_after") val bearingAfter: Double? = null,
    val toll: Boolean = false,
    val ferry: Boolean = false,
    val highway: Boolean = false,
    val rough: Boolean = false,
)

@Serializable
data class ValhallaLeg(
    val summary: ValhallaSummary = ValhallaSummary(),
    /** polyline6-encoded shape. */
    val shape: String = "",
    val maneuvers: List<ValhallaManeuver> = emptyList(),
)

@Serializable
data class ValhallaTrip(
    val status: Int = 0,
    @SerialName("status_message") val statusMessage: String? = null,
    val units: String? = null,
    val language: String? = null,
    val summary: ValhallaSummary = ValhallaSummary(),
    val legs: List<ValhallaLeg> = emptyList(),
    val warnings: List<ValhallaWarning> = emptyList(),
)

@Serializable
data class ValhallaWarning(val code: Int? = null, val text: String? = null)

@Serializable
data class ValhallaAlternate(val trip: ValhallaTrip)

@Serializable
data class ValhallaRouteResponse(
    val trip: ValhallaTrip? = null,
    val alternates: List<ValhallaAlternate> = emptyList(),
    val id: String? = null,
    // Error shape: {"error_code":442,"error":"No path could be found for input","status_code":400,"status":"Bad Request"}
    @SerialName("error_code") val errorCode: Int? = null,
    val error: String? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    val status: String? = null,
)

@Serializable
data class ValhallaStatusResponse(
    val version: String? = null,
    @SerialName("tileset_last_modified") val tilesetLastModified: Long? = null,
)
