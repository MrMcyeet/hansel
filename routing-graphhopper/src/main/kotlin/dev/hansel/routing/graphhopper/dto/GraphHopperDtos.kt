package dev.hansel.routing.graphhopper.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for GraphHopper's JSON `POST /route` API (GraphHopper >= 7; docs/web/api-doc.md). POST parameters use
 * the plural names (`points`, `headings`, `curbsides`, ...) and points are `[lon, lat]`.
 */
internal val graphHopperJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

// ---------------------------------------------------------------- request

@Serializable
data class GraphHopperRouteRequest(
    /** `[lon, lat]` pairs. */
    val points: List<List<Double>>,
    val profile: String,
    val instructions: Boolean = true,
    /** `false` → `paths[].points` is a GeoJSON LineString instead of an encoded polyline. */
    @SerialName("points_encoded") val pointsEncoded: Boolean = false,
    @SerialName("calc_points") val calcPoints: Boolean = true,
    val elevation: Boolean = false,
    val locale: String = "en",
    val details: List<String> = listOf("road_class", "toll"),
    /** Avoid U-turns at via points (applies to the whole request; GraphHopper has no per-point switch). */
    @SerialName("pass_through") val passThrough: Boolean? = null,
    /** One heading for the start point, or one per point. */
    val headings: List<Double>? = null,
    /** Indices into [points] whose via arrival should produce a REACHED_VIA instruction. */
    @SerialName("announced_via_points") val announcedViaPoints: List<Int>? = null,
    val algorithm: String? = null,
    @SerialName("alternative_route.max_paths") val alternativeMaxPaths: Int? = null,
    @SerialName("alternative_route.max_weight_factor") val alternativeMaxWeightFactor: Double? = null,
    @SerialName("alternative_route.max_share_factor") val alternativeMaxShareFactor: Double? = null,
    @SerialName("ch.disable") val chDisable: Boolean? = null,
    @SerialName("custom_model") val customModel: JsonObject? = null,
)

// ---------------------------------------------------------------- response

@Serializable
data class GraphHopperPoints(
    val type: String = "LineString",
    /** `[lon, lat(, ele)]` per vertex. */
    val coordinates: List<List<Double>> = emptyList(),
)

@Serializable
data class GraphHopperInstruction(
    /** See [dev.hansel.routing.graphhopper.GraphHopperSigns]. */
    val sign: Int = 0,
    val text: String = "",
    @SerialName("street_name") val streetName: String = "",
    /** Meters. */
    val distance: Double = 0.0,
    /** Milliseconds. */
    val time: Long = 0,
    /** `[firstPointIndex, lastPointIndex]` into `points`. */
    val interval: List<Int> = emptyList(),
    @SerialName("exit_number") val exitNumber: Int? = null,
    val exited: Boolean? = null,
    @SerialName("turn_angle") val turnAngle: Double? = null,
)

@Serializable
data class GraphHopperPath(
    /** Meters. */
    val distance: Double = 0.0,
    /** Milliseconds. */
    val time: Long = 0,
    val weight: Double? = null,
    val ascend: Double? = null,
    val descend: Double? = null,
    @SerialName("points_encoded") val pointsEncoded: Boolean = false,
    val points: GraphHopperPoints = GraphHopperPoints(),
    @SerialName("snapped_waypoints") val snappedWaypoints: GraphHopperPoints? = null,
    val instructions: List<GraphHopperInstruction> = emptyList(),
    /** Each detail is a list of `[fromIndex, toIndex, value]` triples; value type depends on the detail. */
    val details: Map<String, List<List<JsonElement>>> = emptyMap(),
    val bbox: List<Double>? = null,
    @SerialName("points_order") val pointsOrder: List<Int>? = null,
)

@Serializable
data class GraphHopperHint(val message: String? = null, val details: String? = null)

@Serializable
data class GraphHopperRouteResponse(
    val paths: List<GraphHopperPath> = emptyList(),
    /**
     * Success bodies carry `hints` as an *object* (`{"visited_nodes.sum": ...}`), error bodies as an *array* of
     * [GraphHopperHint]; keep it raw and let [errorHints] sort it out.
     */
    val hints: JsonElement? = null,
    // Error shape: {"message":"Cannot find point 2: ...","hints":[{"message":"...","details":"..."}]}
    val message: String? = null,
) {
    val errorHints: List<GraphHopperHint>
        get() = (hints as? JsonArray)?.mapNotNull { runCatching { graphHopperJson.decodeFromJsonElement(GraphHopperHint.serializer(), it) }.getOrNull() }
            ?: emptyList()
}

@Serializable
data class GraphHopperInfoResponse(
    val version: String? = null,
    @SerialName("build_date") val buildDate: String? = null,
    @SerialName("import_date") val importDate: String? = null,
)
