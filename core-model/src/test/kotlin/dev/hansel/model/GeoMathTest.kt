package dev.hansel.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeoMathTest {
    // Jackson, MS -> New Orleans, LA is roughly 265 km great-circle.
    private val jackson = LatLng(32.2988, -90.1848)
    private val nola = LatLng(29.9511, -90.0715)

    @Test fun haversineIsSane() {
        val d = jackson.distanceTo(nola)
        assertTrue(d in 260_000.0..270_000.0, "got $d")
    }

    @Test fun bearingSouth() {
        val b = jackson.bearingTo(nola)
        assertTrue(abs(b - 177.0) < 3.0, "got $b")
    }

    @Test fun projectionSnapsToNearestSegment() {
        val line = listOf(LatLng(30.0, -90.0), LatLng(30.0, -89.0), LatLng(31.0, -89.0))
        val p = LatLng(30.001, -89.5)
        val proj = assertNotNull(GeoMath.projectOntoPolyline(p, line))
        assertEquals(0, proj.segmentIndex)
        assertTrue(abs(proj.fraction - 0.5) < 0.01)
        assertTrue(proj.distanceMeters < 200)
    }

    @Test fun pointAlongRoundTrips() {
        val line = listOf(LatLng(30.0, -90.0), LatLng(30.0, -89.0))
        val len = GeoMath.polylineLengthMeters(line)
        val mid = GeoMath.pointAlong(line, len / 2)
        assertTrue(abs(mid.lon - (-89.5)) < 0.001)
    }

    @Test fun polylineCodecRoundTrip() {
        val pts = listOf(jackson, nola, LatLng(30.4, -89.0))
        val enc = Polyline.encode(pts, 6)
        val dec = Polyline.decode(enc, 6)
        assertEquals(pts.size, dec.size)
        pts.zip(dec).forEach { (a, b) -> assertTrue(a.distanceTo(b) < 0.2) }
    }

    @Test fun polyline5MatchesKnownVector() {
        // Google's documented example at precision 5.
        val dec = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, dec.size)
        assertTrue(abs(dec[0].lat - 38.5) < 1e-5 && abs(dec[0].lon - (-120.2)) < 1e-5)
        assertTrue(abs(dec[2].lat - 43.252) < 1e-5 && abs(dec[2].lon - (-126.453)) < 1e-5)
    }

    @Test fun pointInPolygon() {
        val ring = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0), LatLng(1.0, 0.0))
        assertTrue(GeoMath.pointInPolygon(LatLng(0.5, 0.5), ring))
        assertTrue(!GeoMath.pointInPolygon(LatLng(1.5, 0.5), ring))
    }

    @Test fun overlapOfIdenticalLinesIsOne() {
        val line = (0..10).map { LatLng(30.0 + it * 0.001, -90.0) }
        assertTrue(GeoMath.overlapFraction(line, line) > 0.99)
        val far = line.map { LatLng(it.lat, it.lon + 0.01) }
        assertTrue(GeoMath.overlapFraction(line, far) < 0.01)
    }

    @Test fun bufferProducesClosedRingAroundLine() {
        val line = listOf(LatLng(30.0, -90.0), LatLng(30.0, -89.99))
        val ring = GeoMath.bufferPolyline(line, 20.0)
        assertEquals(4, ring.size)
        assertTrue(GeoMath.pointInPolygon(LatLng(30.0, -89.995), ring))
    }
}
