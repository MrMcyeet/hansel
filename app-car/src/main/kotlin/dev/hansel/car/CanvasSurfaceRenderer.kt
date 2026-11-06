package dev.hansel.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.Surface
import dev.hansel.car.MapProjection.Camera
import dev.hansel.model.BoundingBox
import dev.hansel.model.LatLng
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry
import kotlin.math.abs

/**
 * Canvas-only map for the car Surface (DESIGN §8: MapLibre on the car surface is a known risk, so v1 draws the route
 * itself and leaves the basemap for a later renderer). Draws, in order: background, alternative routes, the active
 * route, intermediate/shaping waypoints, the destination, the upcoming-maneuver ring, the position puck and a scale bar.
 *
 * Camera policy:
 * - Navigating with progress: follow mode, snapped position a bit below center, ~1.5 km across, heading up.
 * - Previewing / Arrived / Navigating without progress: fit the route bounds (all routes when previewing) into the
 *   visible area with padding, north up.
 * - Idle: last known fix if the registry has one, otherwise just the background.
 * - A user pan/zoom overrides the automatic camera until [resetView] (the SurfaceCallback calls it after 8 s).
 */
class CanvasSurfaceRenderer : CarSurfaceRenderer {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible: Rect? = null

    private var userCamera: Camera? = null
    private var lastAutoCamera: Camera? = null
    private var lastHeading = 0.0
    private var lastState: NavState = NavState.Idle
    private var lastRequest: RouteRequest? = null

    private var palette = Palette.DARK

    private val routePaint = stroke(10f)
    private val routeCasingPaint = stroke(16f)
    private val altPaint = stroke(6f)
    private val waypointFill = fill()
    private val waypointStroke = stroke(3f)
    private val puckFill = fill()
    private val puckStroke = stroke(3f)
    private val ringPaint = stroke(4f)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }
    private val scalePaint = stroke(3f)
    private val path = Path()

    override fun attach(surface: Surface, widthPx: Int, heightPx: Int) {
        this.surface = surface
        width = widthPx
        height = heightPx
        if (visible == null) visible = Rect(0, 0, widthPx, heightPx)
    }

    override fun detach() {
        surface = null
        userCamera = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        render(lastState, lastRequest)
    }

    override fun onDarkModeChanged(isDark: Boolean) {
        palette = if (isDark) Palette.DARK else Palette.LIGHT
        render(lastState, lastRequest)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val base = userCamera ?: lastAutoCamera ?: return
        val d = MapProjection.rotateScreenDelta(distanceX.toDouble(), distanceY.toDouble(), base.rotationDeg)
        userCamera = base.panByPixels(d[0], d[1])
        render(lastState, lastRequest)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val base = userCamera ?: lastAutoCamera ?: return
        // Focus arrives in rotated screen space; move it into north-up pixel space around the view center.
        val f = MapProjection.rotateScreenDelta(focusX - width / 2.0, focusY - height / 2.0, base.rotationDeg)
        userCamera = base.zoomBy(scaleFactor.toDouble(), width / 2.0 + f[0], height / 2.0 + f[1])
        render(lastState, lastRequest)
    }

    override fun resetView() {
        if (userCamera == null) return
        userCamera = null
        render(lastState, lastRequest)
    }

    override fun render(state: NavState, request: RouteRequest?) {
        lastState = state
        lastRequest = request
        val s = surface ?: return
        if (!s.isValid || width <= 0 || height <= 0) return
        val auto = autoCamera(state)
        lastAutoCamera = auto
        val cam = userCamera ?: auto
        val canvas = try {
            s.lockHardwareCanvas()
        } catch (_: Exception) {
            runCatching { s.lockCanvas(null) }.getOrNull()
        } ?: return
        try {
            draw(canvas, cam, state, request)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    // ---- camera -----------------------------------------------------------------------------------------------

    private fun autoCamera(state: NavState): Camera? {
        val vis = visible ?: Rect(0, 0, width, height)
        return when (state) {
            is NavState.Navigating -> {
                val p = state.progress
                if (p != null) {
                    val heading = headingFor(state, p.snapped.segmentIndex)
                    MapProjection.follow(p.snapped.point, heading, width, height, FOLLOW_METERS_ACROSS, aheadFraction = 0.18)
                } else fitInVisible(listOf(state.route), vis)
            }
            is NavState.Previewing -> fitInVisible(state.routes, vis)
            is NavState.Arrived -> fitInVisible(listOf(state.route), vis)
            NavState.Idle -> NavigationHostRegistry.locationSource?.lastKnown?.let { fix ->
                MapProjection.follow(fix.point, 0.0, width, height, FOLLOW_METERS_ACROSS)
            }
        }
    }

    private fun headingFor(state: NavState.Navigating, segmentIndex: Int): Double {
        val fix = state.lastFix
        val moving = (fix?.speedMps ?: 0f) > 1.5f
        val b = fix?.bearing?.toDouble()
        if (moving && b != null) {
            lastHeading = b
            return b
        }
        // Stationary or no bearing: use the route's direction at the snapped segment so the map doesn't spin.
        val g = state.route.geometry
        if (g.size >= 2) {
            val i = segmentIndex.coerceIn(0, g.size - 2)
            lastHeading = g[i].bearingTo(g[i + 1])
        }
        return lastHeading
    }

    private fun fitInVisible(routes: List<Route>, vis: Rect): Camera? {
        val pts = routes.flatMap { it.geometry }
        if (pts.isEmpty()) return null
        val bounds = BoundingBox.of(pts)
        val cam = MapProjection.fit(bounds, vis.width().coerceAtLeast(1), vis.height().coerceAtLeast(1), FIT_PADDING_PX)
            .copy(widthPx = width, heightPx = height)
        // The fit is for the visible rectangle; move the camera so the bounds' center lands on its center.
        return cam.panByPixels(-(vis.exactCenterX() - width / 2.0), -(vis.exactCenterY() - height / 2.0))
    }

    // ---- drawing ----------------------------------------------------------------------------------------------

    private fun draw(canvas: Canvas, cam: Camera?, state: NavState, request: RouteRequest?) {
        canvas.drawColor(palette.background)
        if (cam == null) return

        canvas.save()
        if (cam.rotationDeg != 0.0) canvas.rotate(-cam.rotationDeg.toFloat(), width / 2f, height / 2f)

        val (active, alternatives) = when (state) {
            is NavState.Navigating -> state.route to state.alternatives
            is NavState.Previewing -> state.selected to state.routes.filterIndexed { i, _ -> i != state.selectedIndex }
            is NavState.Arrived -> state.route to emptyList()
            NavState.Idle -> null to emptyList()
        }

        altPaint.color = palette.alternative
        for (r in alternatives) drawPolyline(canvas, cam, r.geometry, altPaint)

        if (active != null) {
            routeCasingPaint.color = palette.routeCasing
            routePaint.color = palette.route
            drawPolyline(canvas, cam, active.geometry, routeCasingPaint)
            drawPolyline(canvas, cam, active.geometry, routePaint)

            // Lock / shaping / intermediate stop points from the request that produced the route.
            request?.intermediate?.forEach { wp ->
                val xy = cam.toScreen(wp.point)
                waypointFill.color = palette.waypoint
                waypointStroke.color = palette.waypointOutline
                canvas.drawCircle(xy[0], xy[1], 7f, waypointFill)
                canvas.drawCircle(xy[0], xy[1], 7f, waypointStroke)
            }

            // Destination.
            active.geometry.lastOrNull()?.let { dest ->
                val xy = cam.toScreen(dest)
                waypointFill.color = palette.destination
                waypointStroke.color = palette.waypointOutline
                canvas.drawCircle(xy[0], xy[1], 11f, waypointFill)
                canvas.drawCircle(xy[0], xy[1], 11f, waypointStroke)
            }
        }

        if (state is NavState.Navigating) {
            val p = state.progress
            if (p != null) {
                // Upcoming maneuver point: ring.
                val m = p.upcoming
                state.route.geometry.getOrNull(m.beginShapeIndex)?.let { mp ->
                    val xy = cam.toScreen(mp)
                    ringPaint.color = palette.maneuverRing
                    canvas.drawCircle(xy[0], xy[1], 16f, ringPaint)
                }
                drawPuck(canvas, cam, p.snapped.point, lastHeading)
            } else state.lastFix?.let { drawPuck(canvas, cam, it.point, it.bearing?.toDouble() ?: 0.0) }
        } else if (state is NavState.Idle) {
            NavigationHostRegistry.locationSource?.lastKnown?.let { drawPuck(canvas, cam, it.point, it.bearing?.toDouble() ?: 0.0) }
        }

        canvas.restore()
        drawScaleBar(canvas, cam)
    }

    private fun drawPolyline(canvas: Canvas, cam: Camera, line: List<LatLng>, paint: Paint) {
        if (line.size < 2) return
        path.rewind()
        var lastX = Float.NaN
        var lastY = Float.NaN
        var first = true
        for (p in line) {
            val xy = cam.toScreen(p)
            val x = xy[0]
            val y = xy[1]
            if (first) {
                path.moveTo(x, y); first = false
            } else if (abs(x - lastX) >= 1f || abs(y - lastY) >= 1f) {
                path.lineTo(x, y)
            } else continue
            lastX = x; lastY = y
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPuck(canvas: Canvas, cam: Camera, at: LatLng, headingDeg: Double) {
        val xy = cam.toScreen(at)
        puckFill.color = palette.puck
        puckStroke.color = palette.puckOutline
        canvas.save()
        canvas.translate(xy[0], xy[1])
        canvas.rotate(headingDeg.toFloat())
        // Bearing wedge pointing "up" in the puck's own frame (= heading in world frame).
        path.rewind()
        path.moveTo(0f, -30f)
        path.lineTo(-11f, -6f)
        path.lineTo(11f, -6f)
        path.close()
        canvas.drawPath(path, puckFill)
        canvas.drawPath(path, puckStroke)
        canvas.drawCircle(0f, 0f, 12f, puckFill)
        canvas.drawCircle(0f, 0f, 12f, puckStroke)
        canvas.restore()
    }

    private fun drawScaleBar(canvas: Canvas, cam: Camera) {
        val vis = visible ?: return
        val metric = NavigationHostRegistry.units == dev.hansel.navigation.DistanceUnits.METRIC
        val candidates = if (metric) listOf(100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        else listOf(160.934, 402.336, 804.672, 1609.344, 3218.69, 8046.72) // 0.1, 0.25, 0.5, 1, 2, 5 mi
        val maxPx = (vis.width() * 0.25).coerceAtLeast(60.0)
        val meters = candidates.lastOrNull { MapProjection.pixelsForMeters(cam, it) <= maxPx } ?: candidates.first()
        val px = MapProjection.pixelsForMeters(cam, meters).toFloat()
        val x0 = vis.left + 24f
        val y0 = vis.bottom - 28f
        scalePaint.color = palette.text
        textPaint.color = palette.text
        canvas.drawLine(x0, y0, x0 + px, y0, scalePaint)
        canvas.drawLine(x0, y0 - 8f, x0, y0, scalePaint)
        canvas.drawLine(x0 + px, y0 - 8f, x0 + px, y0, scalePaint)
        canvas.drawText(NavigationHostRegistry.units.display(meters), x0, y0 - 14f, textPaint)
    }

    private class Palette(
        val background: Int,
        val route: Int,
        val routeCasing: Int,
        val alternative: Int,
        val waypoint: Int,
        val waypointOutline: Int,
        val destination: Int,
        val puck: Int,
        val puckOutline: Int,
        val maneuverRing: Int,
        val text: Int,
    ) {
        companion object {
            val DARK = Palette(
                background = Color.parseColor("#0E1116"),
                route = Color.parseColor("#3DDCB4"),
                routeCasing = Color.parseColor("#0E1116"),
                alternative = Color.parseColor("#566073"),
                waypoint = Color.WHITE,
                waypointOutline = Color.parseColor("#0E1116"),
                destination = Color.parseColor("#FF6B6B"),
                puck = Color.WHITE,
                puckOutline = Color.parseColor("#0E1116"),
                maneuverRing = Color.WHITE,
                text = Color.parseColor("#E6EAF0"),
            )
            val LIGHT = Palette(
                background = Color.parseColor("#F2F4F7"),
                route = Color.parseColor("#0F9D84"),
                routeCasing = Color.WHITE,
                alternative = Color.parseColor("#9AA3B2"),
                waypoint = Color.parseColor("#1B2330"),
                waypointOutline = Color.WHITE,
                destination = Color.parseColor("#D64545"),
                puck = Color.parseColor("#1B2330"),
                puckOutline = Color.WHITE,
                maneuverRing = Color.parseColor("#1B2330"),
                text = Color.parseColor("#1B2330"),
            )
        }
    }

    private companion object {
        const val FOLLOW_METERS_ACROSS = 1_500.0
        const val FIT_PADDING_PX = 48

        fun stroke(widthPx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun fill() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
}
