package dev.hansel.search

import dev.hansel.model.BoundingBox
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * [Geocoder] backed by a Nominatim HTTP API (self-hosted on the dev PC; see dev-server/README.md).
 *
 * Endpoints used:
 *  - `GET /search?q=&format=jsonv2&limit=&addressdetails=1[&viewbox=&bounded=0]`
 *  - `GET /reverse?lat=&lon=&format=jsonv2&addressdetails=1`
 *
 * Nominatim's public-server usage policy (1 req/s, identifying User-Agent) does not apply to a
 * self-hosted instance, but we always send [userAgent] so this client would behave if pointed at one.
 *
 * Never throws for HTTP/network/parse problems: [search] returns an empty list and [reverse] returns
 * null, and a one-line description goes to [onError].
 *
 * @param baseUrl e.g. `http://192.168.1.50:8080` (trailing slash optional).
 * @param biasRadiusMeters half-size of the `viewbox` placed around `near` in [search].
 */
class NominatimGeocoder(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = "Hansel/0.1 (personal)",
    private val biasRadiusMeters: Double = 50_000.0,
    private val onError: (String) -> Unit = {},
) : Geocoder {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun search(query: String, near: LatLng?, limit: Int): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = base.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", q)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("addressdetails", "1")
            .apply {
                if (near != null) {
                    val box = BoundingBox(near.lat, near.lon, near.lat, near.lon).expandedBy(biasRadiusMeters)
                    // viewbox=<x1>,<y1>,<x2>,<y2> (lon,lat); bounded=0 = prefer, don't restrict.
                    addQueryParameter(
                        "viewbox",
                        listOf(box.west, box.north, box.east, box.south).joinToString(",") { fmt(it) },
                    )
                    addQueryParameter("bounded", "0")
                }
            }
            .build()

        val body = get(url, "search") ?: return emptyList()
        val element = parse(body, "search") ?: return emptyList()
        val array = element as? JsonArray
        if (array == null) {
            // Nominatim reports some failures as 200 + {"error": "..."}.
            onError("nominatim search: unexpected response ${body.take(200)}")
            return emptyList()
        }
        return array.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(NominatimPlace.serializer(), item) }
                .getOrElse { onError("nominatim search: cannot decode result: ${it.message}"); null }
                ?.toPlace()
        }
    }

    override suspend fun reverse(point: LatLng): Place? {
        val url = base.newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("lat", fmt(point.lat))
            .addQueryParameter("lon", fmt(point.lon))
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .build()

        val body = get(url, "reverse") ?: return null
        val element = parse(body, "reverse") ?: return null
        val obj = element as? JsonObject ?: run {
            onError("nominatim reverse: unexpected response ${body.take(200)}")
            return null
        }
        if ("error" in obj) {
            // e.g. {"error":"Unable to geocode"} - nothing nearby; not an error worth logging loudly.
            return null
        }
        return runCatching { json.decodeFromJsonElement(NominatimPlace.serializer(), obj) }
            .getOrElse { onError("nominatim reverse: cannot decode result: ${it.message}"); null }
            ?.toPlace()
    }

    /** Executes a GET on the IO dispatcher; returns the body on 2xx, else null (after reporting). */
    private suspend fun get(url: HttpUrl, what: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    onError("nominatim $what: HTTP ${resp.code} ${text.take(200)}")
                    null
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            onError("nominatim $what: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parse(body: String, what: String): JsonElement? =
        runCatching { json.parseToJsonElement(body) }
            .getOrElse { onError("nominatim $what: invalid JSON: ${it.message}"); null }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    private fun NominatimPlace.toPlace(): Place? {
        val latD = lat.toDoubleOrNull()
        val lonD = lon.toDoubleOrNull()
        if (latD == null || lonD == null || latD !in -90.0..90.0 || lonD !in -180.0..180.0) {
            onError("nominatim: result $osmType/$osmId has bad coordinates lat=$lat lon=$lon")
            return null
        }
        val display = displayName.orEmpty()
        val shortName = name?.takeIf { it.isNotBlank() }
            ?: display.substringBefore(',').trim().takeIf { it.isNotEmpty() }
            ?: "$latD,$lonD"
        val id = if (osmType != null && osmId != null) "$osmType/$osmId" else "nominatim/$placeId"
        val cat = listOfNotNull(category, type).joinToString("/").takeIf { it.isNotEmpty() }
        return Place(
            id = id,
            name = shortName,
            address = display.takeIf { it.isNotBlank() },
            point = LatLng(latD, lonD),
            source = PlaceSource.SEARCH,
            category = cat,
        )
    }

    @Serializable
    private data class NominatimPlace(
        @SerialName("place_id") val placeId: Long? = null,
        @SerialName("osm_type") val osmType: String? = null,
        @SerialName("osm_id") val osmId: Long? = null,
        val lat: String = "",
        val lon: String = "",
        val category: String? = null,
        val type: String? = null,
        val name: String? = null,
        @SerialName("display_name") val displayName: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
