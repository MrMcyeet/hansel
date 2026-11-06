package dev.hansel.osm

import dev.hansel.model.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal GeoJSON reader: enough for FeatureCollections of Polygon/MultiPolygon features with flat properties.
 * Built on kotlinx.serialization's JSON tree so the module needs no extra dependency.
 */
object GeoJson {

    /** A polygon as GeoJSON models it: first ring is the outer boundary, the rest are holes. */
    data class Polygon(val outer: List<LatLng>, val holes: List<List<LatLng>>)

    data class Feature(
        val properties: Map<String, JsonElement>,
        /** Empty for features without geometry (e.g. the Geofabrik "nogeom" index). */
        val polygons: List<Polygon>,
    ) {
        fun string(key: String): String? = (properties[key] as? JsonPrimitive)?.contentOrNull
        fun long(key: String): Long? = (properties[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        fun obj(key: String): JsonObject? = properties[key] as? JsonObject
        fun strings(key: String): List<String> = (properties[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseFeatureCollection(text: String): List<Feature> {
        val root = json.parseToJsonElement(text).jsonObject
        require(root["type"]?.jsonPrimitive?.contentOrNull == "FeatureCollection") { "not a FeatureCollection" }
        return root["features"]?.jsonArray?.map { parseFeature(it.jsonObject) } ?: emptyList()
    }

    fun parseFeature(f: JsonObject): Feature {
        val props = (f["properties"] as? JsonObject)?.toMap() ?: emptyMap()
        val geom = f["geometry"]
        val polygons: List<Polygon> = if (geom == null || geom is JsonNull) emptyList() else parseGeometry(geom.jsonObject)
        return Feature(props, polygons)
    }

    private fun parseGeometry(g: JsonObject): List<Polygon> = when (val type = g["type"]?.jsonPrimitive?.contentOrNull) {
        "Polygon" -> listOf(parsePolygon(g["coordinates"]!!.jsonArray))
        "MultiPolygon" -> g["coordinates"]!!.jsonArray.map { parsePolygon(it.jsonArray) }
        "GeometryCollection" -> g["geometries"]?.jsonArray?.flatMap { parseGeometry(it.jsonObject) } ?: emptyList()
        else -> emptyList<Polygon>().also { require(type != null) { "geometry without type" } } // points/lines: ignored
    }

    private fun parsePolygon(rings: JsonArray): Polygon {
        val parsed = rings.map { ring -> ring.jsonArray.map { pos -> position(pos.jsonArray) } }
        require(parsed.isNotEmpty()) { "polygon without rings" }
        return Polygon(parsed[0], parsed.drop(1))
    }

    /** GeoJSON positions are [lon, lat(, alt)]. */
    private fun position(a: JsonArray): LatLng = LatLng(lat = a[1].jsonPrimitive.double, lon = a[0].jsonPrimitive.double)
}
