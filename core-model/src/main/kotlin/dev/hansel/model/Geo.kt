package dev.hansel.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate. Latitude/longitude in degrees. */
@Serializable
data class LatLng(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }

    /** Great-circle distance in meters. */
    fun distanceTo(other: LatLng): Double = GeoMath.haversineMeters(this, other)

    /** Initial bearing in degrees [0, 360). */
    fun bearingTo(other: LatLng): Double = GeoMath.bearingDegrees(this, other)

    override fun toString(): String = "%.6f,%.6f".format(lat, lon)
}

@Serializable
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: LatLng get() = LatLng((south + north) / 2, (west + east) / 2)
    fun contains(p: LatLng): Boolean = p.lat in south..north && p.lon in west..east
    fun expandedBy(meters: Double): BoundingBox {
        val dLat = meters / GeoMath.METERS_PER_DEGREE_LAT
        val dLon = meters / (GeoMath.METERS_PER_DEGREE_LAT * cos(center.lat * PI / 180.0))
        return BoundingBox(south - dLat, west - dLon, north + dLat, east + dLon)
    }

    companion object {
        fun of(points: List<LatLng>): BoundingBox {
            require(points.isNotEmpty())
            var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
            for (p in points) { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
            return BoundingBox(s, w, n, e)
        }
    }
}

/** A point snapped onto a polyline: the nearest point, the index of the segment it lies on and the fraction along it. */
data class PolylineProjection(
    val point: LatLng,
    val segmentIndex: Int,
    val fraction: Double,
    val distanceMeters: Double,
    /** Distance along the polyline from its start to [point]. */
    val alongMeters: Double,
)

object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val METERS_PER_DEGREE_LAT = 111_320.0

    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = (b.lat - a.lat).toRadians()
        val dLon = (b.lon - a.lon).toRadians()
        val la1 = a.lat.toRadians()
        val la2 = b.lat.toRadians()
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val la1 = a.lat.toRadians(); val la2 = b.lat.toRadians()
        val dLon = (b.lon - a.lon).toRadians()
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (atan2(y, x).toDegrees() + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, in degrees [0, 180]. */
    fun bearingDelta(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Destination point given start, bearing (deg) and distance (m). */
    fun destination(start: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val ad = meters / EARTH_RADIUS_M
        val br = bearingDeg.toRadians()
        val la1 = start.lat.toRadians(); val lo1 = start.lon.toRadians()
        val la2 = asin(sin(la1) * cos(ad) + cos(la1) * sin(ad) * cos(br))
        val lo2 = lo1 + atan2(sin(br) * sin(ad) * cos(la1), cos(ad) - sin(la1) * sin(la2))
        return LatLng(la2.toDegrees(), ((lo2.toDegrees() + 540.0) % 360.0) - 180.0)
    }

    fun polylineLengthMeters(line: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until line.size) total += haversineMeters(line[i - 1], line[i])
        return total
    }

    /** Cumulative distance from the start of the line to each vertex. `result[0] == 0.0`. */
    fun cumulativeDistances(line: List<LatLng>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) out[i] = out[i - 1] + haversineMeters(line[i - 1], line[i])
        return out
    }

    /**
     * Project [p] onto the polyline using a local equirectangular approximation, which is accurate
     * to well under a meter at the scales navigation cares about (hundreds of meters).
     */
    fun projectOntoPolyline(p: LatLng, line: List<LatLng>, cumulative: DoubleArray? = null): PolylineProjection? {
        if (line.isEmpty()) return null
        if (line.size == 1) return PolylineProjection(line[0], 0, 0.0, haversineMeters(p, line[0]), 0.0)
        val cum = cumulative ?: cumulativeDistances(line)
        val cosLat = cos(p.lat.toRadians())
        var best: PolylineProjection? = null
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val ax = (a.lon - p.lon) * cosLat; val ay = a.lat - p.lat
            val bx = (b.lon - p.lon) * cosLat; val by = b.lat - p.lat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx; val py = ay + tc * dy
            val distDeg = sqrt(px * px + py * py)
            val distM = distDeg * METERS_PER_DEGREE_LAT
            if (best == null || distM < best.distanceMeters) {
                val snapped = LatLng(a.lat + tc * (b.lat - a.lat), a.lon + tc * (b.lon - a.lon))
                val along = cum[i] + tc * (cum[i + 1] - cum[i])
                best = PolylineProjection(snapped, i, tc, distM, along)
            }
        }
        return best
    }

    /** Point at [meters] along the polyline (clamped to the ends). */
    fun pointAlong(line: List<LatLng>, meters: Double, cumulative: DoubleArray? = null): LatLng {
        require(line.isNotEmpty())
        val cum = cumulative ?: cumulativeDistances(line)
        if (meters <= 0.0) return line.first()
        if (meters >= cum.last()) return line.last()
        var i = 1
        while (i < cum.size && cum[i] < meters) i++
        val a = line[i - 1]; val b = line[i]
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen == 0.0) 0.0 else (meters - cum[i - 1]) / segLen
        return LatLng(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    /** Ray-casting point-in-polygon. Polygon is an open or closed ring. */
    fun pointInPolygon(p: LatLng, ring: List<LatLng>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].lat; val xi = ring[i].lon
            val yj = ring[j].lat; val xj = ring[j].lon
            val intersects = ((yi > p.lat) != (yj > p.lat)) &&
                (p.lon < (xj - xi) * (p.lat - yi) / (yj - yi + 0.0) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Fraction of [a]'s length that lies within [toleranceMeters] of [b]. Symmetric overlap is the mean of both
     * directions. Sampled every [stepMeters] so it is cheap enough to run on every alternative.
     */
    fun overlapFraction(a: List<LatLng>, b: List<LatLng>, toleranceMeters: Double = 25.0, stepMeters: Double = 50.0): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val cumA = cumulativeDistances(a)
        val cumB = cumulativeDistances(b)
        val lenA = cumA.last()
        if (lenA == 0.0) return 0.0
        var hits = 0; var samples = 0
        var d = 0.0
        while (d <= lenA) {
            val p = pointAlong(a, d, cumA)
            val proj = projectOntoPolyline(p, b, cumB)
            if (proj != null && proj.distanceMeters <= toleranceMeters) hits++
            samples++
            d += stepMeters
        }
        return if (samples == 0) 0.0 else hits.toDouble() / samples
    }

    /** Buffer a polyline into a simple polygon ring (left side forward, right side back). Good enough for avoid zones. */
    fun bufferPolyline(line: List<LatLng>, meters: Double): List<LatLng> {
        if (line.isEmpty()) return emptyList()
        if (line.size == 1) {
            return (0 until 8).map { destination(line[0], it * 45.0, meters) }
        }
        val left = ArrayList<LatLng>(line.size)
        val right = ArrayList<LatLng>(line.size)
        for (i in line.indices) {
            val prev = line.getOrNull(i - 1)
            val next = line.getOrNull(i + 1)
            val bearing = when {
                prev == null -> line[i].bearingTo(next!!)
                next == null -> prev.bearingTo(line[i])
                else -> {
                    val b1 = prev.bearingTo(line[i]); val b2 = line[i].bearingTo(next)
                    // average of unit vectors to avoid wraparound
                    val x = cos(b1.toRadians()) + cos(b2.toRadians()); val y = sin(b1.toRadians()) + sin(b2.toRadians())
                    (atan2(y, x).toDegrees() + 360.0) % 360.0
                }
            }
            left += destination(line[i], (bearing + 270.0) % 360.0, meters)
            right += destination(line[i], (bearing + 90.0) % 360.0, meters)
        }
        return left + right.reversed()
    }

    private fun Double.toRadians() = this * PI / 180.0
    private fun Double.toDegrees() = this * 180.0 / PI
}
