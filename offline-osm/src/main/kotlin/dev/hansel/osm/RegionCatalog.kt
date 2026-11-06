package dev.hansel.osm

import dev.hansel.model.BoundingBox
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The US states Hansel can download as Geofabrik extracts, with simplified outlines for "which state am I in" and
 * neighbour detection. Outlines come from the US Census Bureau 1:20,000,000 cartographic boundary file
 * (cb_2023_us_state_20m, public domain), Douglas-Peucker simplified to ~200 m and bundled as
 * `dev/hansel/osm/us_states.geojson`. That resolution is fine for picking a region to download; it is not a legal
 * boundary.
 */
object RegionCatalog {

    const val GEOFABRIK_BASE = "https://download.geofabrik.de"
    private const val RESOURCE = "/dev/hansel/osm/us_states.geojson"

    /** Two states are neighbours when some vertex of one lies within this distance of the other's outline. */
    const val NEIGHBOUR_TOLERANCE_M = 2_000.0

    private val states: List<Region> by lazy { load() }

    /** 50 states + District of Columbia + Puerto Rico, sorted by name, neighbours resolved. */
    fun usStates(): List<Region> = states

    fun byId(id: String): Region? = states.firstOrNull { it.id == id }

    fun byPostal(postal: String): Region? = states.firstOrNull { it.postal.equals(postal, ignoreCase = true) }

    /** The US state/territory containing [p], or null (ocean, outside the US, or a Geofabrik-less territory). */
    fun regionAt(p: LatLng): Region? = states.firstOrNull { it.contains(p) }

    /** All states whose outline contains [p]; with simplified borders a point very near a line can match two. */
    fun regionsAt(p: LatLng): List<Region> = states.filter { it.contains(p) }

    fun Region.contains(p: LatLng): Boolean {
        val b = bbox ?: return false
        if (!b.contains(p)) return false
        return polygons.any { GeoMath.pointInPolygon(p, it) }
    }

    fun pbfUrl(parentPath: String, slug: String) = "$GEOFABRIK_BASE/$parentPath/$slug-latest.osm.pbf"

    private fun load(): List<Region> {
        val text = RegionCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing resource $RESOURCE")
        val features = GeoJson.parseFeatureCollection(text)
        val raw = features.map { f ->
            val slug = f.string("geofabrik") ?: error("feature without geofabrik slug: ${f.properties}")
            val rings = f.polygons.map { it.outer }
            val url = pbfUrl("north-america/us", slug)
            Region(
                id = "us/$slug",
                name = f.string("name") ?: slug,
                postal = f.string("postal"),
                pbfUrl = url,
                md5Url = "$url.md5",
                bbox = BoundingBox.of(rings.flatten()),
                polygons = rings,
                neighbours = emptyList(),
                parent = "north-america",
                isoCodes = listOfNotNull(f.string("postal")?.let { "US-$it" }),
            )
        }.sortedBy { it.name }
        val neighbours = computeNeighbours(raw)
        return raw.map { r -> r.copy(neighbours = neighbours[r.id].orEmpty().sorted()) }
    }

    /**
     * Pairwise shared-boundary test. Bounding boxes (expanded by the tolerance) prune most pairs; for the rest, any
     * vertex of A within [NEIGHBOUR_TOLERANCE_M] of any segment of B (or vice versa) makes them neighbours. Runs in
     * a few milliseconds for 52 regions / ~9k vertices.
     */
    internal fun computeNeighbours(regions: List<Region>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (i in regions.indices) for (j in i + 1 until regions.size) {
            val a = regions[i]; val b = regions[j]
            val ab = a.bbox ?: continue; val bb = b.bbox ?: continue
            if (!overlaps(ab.expandedBy(NEIGHBOUR_TOLERANCE_M), bb)) continue
            if (touches(a.polygons, b.polygons) || touches(b.polygons, a.polygons)) {
                out.getOrPut(a.id) { ArrayList() } += b.id
                out.getOrPut(b.id) { ArrayList() } += a.id
            }
        }
        return out
    }

    private fun overlaps(a: BoundingBox, b: BoundingBox) =
        a.west <= b.east && b.west <= a.east && a.south <= b.north && b.south <= a.north

    private fun touches(from: List<List<LatLng>>, to: List<List<LatLng>>): Boolean {
        val toBoxes = to.map { BoundingBox.of(it).expandedBy(NEIGHBOUR_TOLERANCE_M) }
        for (ring in from) for (v in ring) {
            for ((k, other) in to.withIndex()) {
                if (!toBoxes[k].contains(v)) continue
                if (distanceToRingMeters(v, other) <= NEIGHBOUR_TOLERANCE_M) return true
            }
        }
        return false
    }

    /** Equirectangular point-to-segment distance; accurate to well under 1% at these scales. */
    internal fun distanceToRingMeters(p: LatLng, ring: List<LatLng>): Double {
        val cosLat = cos(p.lat * PI / 180.0)
        var best = Double.MAX_VALUE
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[j]; val b = ring[i]
            j = i
            val ax = (a.lon - p.lon) * cosLat; val ay = a.lat - p.lat
            val bx = (b.lon - p.lon) * cosLat; val by = b.lat - p.lat
            // quick reject: segment bbox farther than current best
            val lo = min(abs(ax), abs(bx)); val la = min(abs(ay), abs(by))
            if ((ax > 0) == (bx > 0) && (ay > 0) == (by > 0) && max(lo, la) * GeoMath.METERS_PER_DEGREE_LAT > best) continue
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx; val py = ay + tc * dy
            val d = sqrt(px * px + py * py) * GeoMath.METERS_PER_DEGREE_LAT
            if (d < best) best = d
        }
        return best
    }
}
