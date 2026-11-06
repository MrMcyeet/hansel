package dev.hansel.car

import android.graphics.Rect
import android.view.Surface
import dev.hansel.model.RouteRequest
import dev.hansel.navigation.NavState

/**
 * Draws the map onto the Android Auto [Surface]. [CarMapRenderer] (the `SurfaceCallback`) owns the surface lifecycle,
 * throttling and the gesture timeout, and calls into one of these. Two implementations are intended:
 *
 * - [CanvasSurfaceRenderer] (this version): plain `android.graphics.Canvas`, route lines + puck, no basemap.
 * - A MapLibre-backed renderer (future, DESIGN §8 "known risk"): see README "Swapping in MapLibre".
 *
 * All methods are called on the main thread.
 */
interface CarSurfaceRenderer {
    fun attach(surface: Surface, widthPx: Int, heightPx: Int)
    fun detach()

    /** Draw the given state. [request] is the active route request (for intermediate/shaping points), may be null. */
    fun render(state: NavState, request: RouteRequest?)

    /** Host UI overlays part of the surface; this is the rectangle that stays visible. Default ignores it. */
    fun onVisibleAreaChanged(visibleArea: Rect) {}
    fun onStableAreaChanged(stableArea: Rect) {}

    /** Temporary user pan in screen pixels (as reported by the host). */
    fun onScroll(distanceX: Float, distanceY: Float) {}

    /** Temporary user zoom; [scaleFactor] > 1 zooms in around the focus point. */
    fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}

    /** Drop any temporary pan/zoom and go back to the automatic camera. */
    fun resetView() {}

    /** Host switched between light and dark palettes. Default renderers are dark-only. */
    fun onDarkModeChanged(isDark: Boolean) {}
}
