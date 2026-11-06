package dev.hansel.car

import dev.hansel.model.BoundingBox
import dev.hansel.model.LatLng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Web Mercator camera math for the car map surface. Pure Kotlin, no Android types, so the fit/follow logic is covered
 * by JVM tests. World coordinates are the unit square ([0,1] x [0,1]) of EPSG:3857; the camera turns that into
 * north-up pixel coordinates, and the renderer applies heading rotation with `Canvas.rotate` around the screen center
 * (see [Camera.rotationDeg]).
 */
object MapProjection {
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val MAX_LAT = 85.05112878

    /** Lat/lon -> world unit square. x grows east, y grows south. */
    fun toWorld(p: LatLng): DoubleArray {
        val lat = p.lat.coerceIn(-MAX_LAT, MAX_LAT)
        val x = (p.lon + 180.0) / 360.0
        val s = sin(lat * PI / 180.0)
        val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        return doubleArrayOf(x, y)
    }

    fun toLatLng(wx: Double, wy: Double): LatLng {
        val lon = wx * 360.0 - 180.0
        val lat = (2 * atan(exp((0.5 - wy) * 2 * PI)) - PI / 2) * 180.0 / PI
        return LatLng(lat, lon)
    }

    /** Ground meters covered by one world unit at a latitude (Mercator scale factor). */
    fun metersPerWorldUnit(lat: Double): Double = EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180.0)

    /**
     * Camera: a world-space center, a scale (pixels per world unit) and the view size. [rotationDeg] is the heading
     * that should point up; `toScreen` does NOT apply it (north-up output), the renderer rotates the canvas instead so
     * every drawn primitive, including the puck wedge, rotates consistently.
     */
    data class Camera(
        val centerX: Double,
        val centerY: Double,
        val pixelsPerWorldUnit: Double,
        val widthPx: Int,
        val heightPx: Int,
        val rotationDeg: Double = 0.0,
    ) {
        val center: LatLng get() = toLatLng(centerX, centerY)

        /** North-up screen position of a point, relative to the view's top-left corner. */
        fun toScreen(p: LatLng): FloatArray {
            val w = toWorld(p)
            return floatArrayOf(
                (widthPx / 2.0 + (w[0] - centerX) * pixelsPerWorldUnit).toFloat(),
                (heightPx / 2.0 + (w[1] - centerY) * pixelsPerWorldUnit).toFloat(),
            )
        }

        /** Ground meters per pixel at the camera's latitude. */
        val metersPerPixel: Double get() = metersPerWorldUnit(center.lat) / pixelsPerWorldUnit

        /** Shift the camera by a north-up pixel delta (positive dx moves the view east, dy south). */
        fun panByPixels(dxPx: Double, dyPx: Double): Camera =
            copy(centerX = centerX + dxPx / pixelsPerWorldUnit, centerY = centerY + dyPx / pixelsPerWorldUnit)

        /** Zoom by [factor] (>1 zooms in) keeping the world point under screen ([fxPx],[fyPx]) fixed. */
        fun zoomBy(factor: Double, fxPx: Double, fyPx: Double): Camera {
            val f = if (factor.isNaN() || factor <= 0.0) 1.0 else factor
            val newScale = (pixelsPerWorldUnit * f).coerceIn(MIN_PIXELS_PER_WORLD, MAX_PIXELS_PER_WORLD)
            val actual = newScale / pixelsPerWorldUnit
            // World point under the focus must map to the same pixel after scaling.
            val wx = centerX + (fxPx - widthPx / 2.0) / pixelsPerWorldUnit
            val wy = centerY + (fyPx - heightPx / 2.0) / pixelsPerWorldUnit
            val cx = wx - (fxPx - widthPx / 2.0) / (pixelsPerWorldUnit * actual)
            val cy = wy - (fyPx - heightPx / 2.0) / (pixelsPerWorldUnit * actual)
            return copy(centerX = cx, centerY = cy, pixelsPerWorldUnit = newScale)
        }
    }

    /** Roughly zoom 2 .. zoom 22 at 256 px tiles. */
    const val MIN_PIXELS_PER_WORLD = 1_024.0
    const val MAX_PIXELS_PER_WORLD = 1_073_741_824.0

    /** Fit [bounds] into a [widthPx] x [heightPx] view with [paddingPx] on every side. North-up. */
    fun fit(bounds: BoundingBox, widthPx: Int, heightPx: Int, paddingPx: Int = 0): Camera {
        val sw = toWorld(LatLng(bounds.south, bounds.west))
        val ne = toWorld(LatLng(bounds.north, bounds.east))
        val wWidth = max(ne[0] - sw[0], 1e-9)
        val wHeight = max(sw[1] - ne[1], 1e-9)
        val usableW = max(widthPx - 2.0 * paddingPx, 1.0)
        val usableH = max(heightPx - 2.0 * paddingPx, 1.0)
        val scale = min(usableW / wWidth, usableH / wHeight).coerceIn(MIN_PIXELS_PER_WORLD, MAX_PIXELS_PER_WORLD)
        return Camera((sw[0] + ne[0]) / 2, (sw[1] + ne[1]) / 2, scale, widthPx, heightPx)
    }

    /**
     * Follow camera: [position] sits at the center (or, when [aheadFraction] > 0, that fraction of the height below
     * center so more road is visible ahead), the view spans [metersAcross] ground meters horizontally and
     * [headingDeg] points up.
     */
    fun follow(
        position: LatLng,
        headingDeg: Double,
        widthPx: Int,
        heightPx: Int,
        metersAcross: Double = 1_500.0,
        aheadFraction: Double = 0.0,
    ): Camera {
        val mpw = metersPerWorldUnit(position.lat)
        val scale = (widthPx / (metersAcross / mpw)).coerceIn(MIN_PIXELS_PER_WORLD, MAX_PIXELS_PER_WORLD)
        val w = toWorld(position)
        var cam = Camera(w[0], w[1], scale, widthPx, heightPx, rotationDeg = normalizeDeg(headingDeg))
        if (aheadFraction > 0.0) {
            // Shift the center forward along the heading so the puck sits lower on screen.
            val shiftPx = heightPx * aheadFraction
            val rad = headingDeg * PI / 180.0
            cam = cam.panByPixels(shiftPx * sin(rad), -shiftPx * cos(rad))
        }
        return cam
    }

    /**
     * Convert a screen-space gesture delta (as the host reports it, in the rotated view) into the north-up pixel delta
     * `Camera.panByPixels` expects. The canvas is rotated by -heading, so a screen vector is rotated by +heading.
     */
    fun rotateScreenDelta(dxPx: Double, dyPx: Double, rotationDeg: Double): DoubleArray {
        val rad = rotationDeg * PI / 180.0
        val c = cos(rad)
        val s = sin(rad)
        return doubleArrayOf(dxPx * c - dyPx * s, dxPx * s + dyPx * c)
    }

    fun normalizeDeg(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    /** Scale bar helper: how many pixels [meters] covers at the camera's latitude. */
    fun pixelsForMeters(cam: Camera, meters: Double): Double = meters / cam.metersPerPixel
}
