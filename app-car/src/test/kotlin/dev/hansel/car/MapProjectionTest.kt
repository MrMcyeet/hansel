package dev.hansel.car

import dev.hansel.model.BoundingBox
import dev.hansel.model.LatLng
import dev.hansel.navigation.DistanceUnits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapProjectionTest {

    private fun assertClose(expected: Double, actual: Double, tol: Double, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected got $actual (tol $tol)")

    @Test
    fun `world projection round-trips and anchors`() {
        val origin = MapProjection.toWorld(LatLng(0.0, 0.0))
        assertClose(0.5, origin[0], 1e-9)
        assertClose(0.5, origin[1], 1e-9)
        val p = LatLng(30.2672, -97.7431) // Austin
        val w = MapProjection.toWorld(p)
        val back = MapProjection.toLatLng(w[0], w[1])
        assertClose(p.lat, back.lat, 1e-7)
        assertClose(p.lon, back.lon, 1e-7)
        // North has smaller y, east larger x.
        val north = MapProjection.toWorld(LatLng(31.0, -97.7431))
        val east = MapProjection.toWorld(LatLng(30.2672, -96.0))
        assertTrue(north[1] < w[1])
        assertTrue(east[0] > w[0])
    }

    @Test
    fun `fit centers the bounds and keeps them inside the padded view`() {
        val bounds = BoundingBox(south = 30.0, west = -98.0, north = 30.5, east = -97.0)
        val cam = MapProjection.fit(bounds, widthPx = 800, heightPx = 480, paddingPx = 40)
        val c = cam.toScreen(LatLng(30.25, -97.5))
        // The geographic mid point is not the Mercator mid point exactly, so allow a few pixels.
        assertClose(400.0, c[0].toDouble(), 1.0, "center x")
        assertClose(240.0, c[1].toDouble(), 3.0, "center y")
        for (corner in listOf(LatLng(30.0, -98.0), LatLng(30.5, -97.0), LatLng(30.0, -97.0), LatLng(30.5, -98.0))) {
            val xy = cam.toScreen(corner)
            assertTrue(xy[0] >= 40f - 0.5f && xy[0] <= 760f + 0.5f, "x in padded view: ${xy[0]}")
            assertTrue(xy[1] >= 40f - 0.5f && xy[1] <= 440f + 0.5f, "y in padded view: ${xy[1]}")
        }
        // One axis must be binding: either the west/east or the south/north edges touch the padding exactly.
        val west = cam.toScreen(LatLng(30.25, -98.0))[0].toDouble()
        val east = cam.toScreen(LatLng(30.25, -97.0))[0].toDouble()
        val south = cam.toScreen(LatLng(30.0, -97.5))[1].toDouble()
        val north = cam.toScreen(LatLng(30.5, -97.5))[1].toDouble()
        val xBinding = abs(west - 40.0) < 0.5 && abs(east - 760.0) < 0.5
        val yBinding = abs(north - 40.0) < 0.5 && abs(south - 440.0) < 0.5
        assertTrue(xBinding || yBinding, "west=$west east=$east north=$north south=$south")
        // 1 deg of longitude x 0.5 deg of latitude at 30N in an 800x480 view: height binds.
        assertTrue(yBinding)
    }

    @Test
    fun `fit of a degenerate box does not blow up`() {
        val cam = MapProjection.fit(BoundingBox(30.0, -97.0, 30.0, -97.0), 800, 480, 0)
        assertTrue(cam.pixelsPerWorldUnit <= MapProjection.MAX_PIXELS_PER_WORLD)
        val xy = cam.toScreen(LatLng(30.0, -97.0))
        assertClose(400.0, xy[0].toDouble(), 0.01)
        assertClose(240.0, xy[1].toDouble(), 0.01)
    }

    @Test
    fun `follow puts the position at center and spans the requested ground distance`() {
        val pos = LatLng(30.2672, -97.7431)
        val cam = MapProjection.follow(pos, headingDeg = 45.0, widthPx = 1000, heightPx = 600, metersAcross = 1500.0)
        val xy = cam.toScreen(pos)
        assertClose(500.0, xy[0].toDouble(), 0.01)
        assertClose(300.0, xy[1].toDouble(), 0.01)
        assertClose(1.5, cam.metersPerPixel, 0.01, "meters per pixel")
        assertClose(45.0, cam.rotationDeg, 1e-9)
        // A point 750 m east should be at the right edge (north-up).
        val east = pos.let { dev.hansel.model.GeoMath.destination(it, 90.0, 750.0) }
        assertClose(1000.0, cam.toScreen(east)[0].toDouble(), 2.0, "east edge")
    }

    @Test
    fun `follow with aheadFraction pushes the puck below center along the heading`() {
        val pos = LatLng(30.2672, -97.7431)
        val h = 1000
        val cam = MapProjection.follow(pos, headingDeg = 90.0, widthPx = 1000, heightPx = h, metersAcross = 1500.0, aheadFraction = 0.2)
        // Heading east: the camera center moved east, so the puck (north-up) sits 200 px left of center;
        // after the canvas rotates by -90 that is 200 px below center.
        val xy = cam.toScreen(pos)
        assertClose(500.0 - 0.2 * h, xy[0].toDouble(), 0.5)
        assertClose(500.0, xy[1].toDouble(), 0.5)
        val rotated = MapProjection.rotateScreenDelta(xy[0] - 500.0, xy[1] - 500.0, -cam.rotationDeg)
        assertClose(0.0, rotated[0], 0.5, "on the vertical axis")
        assertClose(0.2 * h, rotated[1], 0.5, "below center")
    }

    @Test
    fun `pan and zoom keep the focus point fixed`() {
        val pos = LatLng(30.2672, -97.7431)
        val cam = MapProjection.follow(pos, 0.0, 800, 480, 1500.0)
        val panned = cam.panByPixels(100.0, -50.0)
        val xy = panned.toScreen(pos)
        assertClose(300.0, xy[0].toDouble(), 0.01)
        assertClose(290.0, xy[1].toDouble(), 0.01)

        val focus = LatLng(30.27, -97.74)
        val before = cam.toScreen(focus)
        val zoomed = cam.zoomBy(2.0, before[0].toDouble(), before[1].toDouble())
        val after = zoomed.toScreen(focus)
        assertClose(before[0].toDouble(), after[0].toDouble(), 0.01, "focus x")
        assertClose(before[1].toDouble(), after[1].toDouble(), 0.01, "focus y")
        assertClose(cam.pixelsPerWorldUnit * 2, zoomed.pixelsPerWorldUnit, 1e-6)
        assertClose(cam.metersPerPixel / 2, zoomed.metersPerPixel, 1e-4) // center latitude moved a hair
    }

    @Test
    fun `zoom is clamped to the allowed scale range`() {
        val cam = MapProjection.follow(LatLng(0.0, 0.0), 0.0, 800, 480, 1500.0)
        val tooFar = cam.zoomBy(1e-9, 400.0, 240.0)
        assertEquals(MapProjection.MIN_PIXELS_PER_WORLD, tooFar.pixelsPerWorldUnit)
        val tooClose = cam.zoomBy(1e9, 400.0, 240.0)
        assertEquals(MapProjection.MAX_PIXELS_PER_WORLD, tooClose.pixelsPerWorldUnit)
    }

    @Test
    fun `screen deltas rotate into north-up space`() {
        // Heading east (90): a drag "up" on the rotated screen (dy = -10) means moving east in the world (dx = +10).
        val d = MapProjection.rotateScreenDelta(0.0, -10.0, 90.0)
        assertClose(10.0, d[0], 1e-9)
        assertClose(0.0, d[1], 1e-9)
        val none = MapProjection.rotateScreenDelta(3.0, 4.0, 0.0)
        assertClose(3.0, none[0], 1e-9)
        assertClose(4.0, none[1], 1e-9)
    }

    @Test
    fun `normalizeDeg wraps into 0 until 360`() {
        assertEquals(350.0, MapProjection.normalizeDeg(-10.0))
        assertEquals(10.0, MapProjection.normalizeDeg(370.0))
        assertEquals(0.0, MapProjection.normalizeDeg(720.0))
    }

    @Test
    fun `distance spec picks unit like DistanceUnits display`() {
        assertEquals(CarDistances.Spec(500.0, CarDistances.UNIT_FEET), CarDistances.spec(152.4, DistanceUnits.IMPERIAL))
        assertEquals(CarDistances.Spec(0.3, CarDistances.UNIT_MILES_P1), CarDistances.spec(500.0, DistanceUnits.IMPERIAL))
        assertEquals(CarDistances.Spec(12.0, CarDistances.UNIT_MILES), CarDistances.spec(19_312.0, DistanceUnits.IMPERIAL))
        assertEquals(CarDistances.Spec(80.0, CarDistances.UNIT_METERS), CarDistances.spec(84.0, DistanceUnits.METRIC))
        assertEquals(CarDistances.Spec(1.2, CarDistances.UNIT_KILOMETERS_P1), CarDistances.spec(1_240.0, DistanceUnits.METRIC))
        assertEquals(CarDistances.Spec(12.0, CarDistances.UNIT_KILOMETERS), CarDistances.spec(12_400.0, DistanceUnits.METRIC))
        assertEquals(CarDistances.Spec(0.0, CarDistances.UNIT_FEET), CarDistances.spec(-5.0, DistanceUnits.IMPERIAL))
    }
}
