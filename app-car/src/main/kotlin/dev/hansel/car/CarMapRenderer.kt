package dev.hansel.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import dev.hansel.model.RouteRequest
import dev.hansel.navigation.NavState

/**
 * The `SurfaceCallback` registered with the host's `AppManager`. Owns the Surface lifecycle, the visible/stable
 * insets, redraw throttling (<= 10 fps) and the "return to follow mode 8 s after the last gesture" timer. Actual
 * drawing is delegated to a [CarSurfaceRenderer] from [CarHooks.rendererFactory] so the Canvas renderer can be
 * replaced by a MapLibre one without touching the template code.
 *
 * All SurfaceCallback methods are invoked by the library on the main thread.
 */
class CarMapRenderer(
    private val renderer: CarSurfaceRenderer = CarHooks.rendererFactory(),
) : SurfaceCallback {
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    var visibleArea: Rect? = null
        private set
    var stableArea: Rect? = null
        private set

    private var pendingState: NavState = NavState.Idle
    private var pendingRequest: RouteRequest? = null
    private var lastRenderUptime = 0L
    private var renderScheduled = false

    private val renderRunnable = Runnable {
        renderScheduled = false
        renderNow()
    }
    private val resetViewRunnable = Runnable { renderer.resetView() }

    // ---- SurfaceCallback ---------------------------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        renderer.attach(surface, surfaceContainer.width, surfaceContainer.height)
        attached = true
        visibleArea?.let(renderer::onVisibleAreaChanged)
        stableArea?.let(renderer::onStableAreaChanged)
        renderNow()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        attached = false
        handler.removeCallbacks(renderRunnable)
        handler.removeCallbacks(resetViewRunnable)
        renderScheduled = false
        renderer.detach()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        if (attached) renderer.onVisibleAreaChanged(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        if (attached) renderer.onStableAreaChanged(stableArea)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!attached) return
        renderer.onScroll(distanceX, distanceY)
        armResetTimer()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!attached) return
        renderer.onScale(focusX, focusY, scaleFactor)
        armResetTimer()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        // Flings are treated as a plain pan of a fraction of the velocity; good enough for a glance at the route.
        if (!attached) return
        renderer.onScroll(-velocityX * FLING_PAN_FRACTION, -velocityY * FLING_PAN_FRACTION)
        armResetTimer()
    }

    // ---- API for the screen -------------------------------------------------------------------------------------

    /** Latest state to draw. Coalesces bursts so the surface is redrawn at most every [MIN_FRAME_MS]. */
    fun submit(state: NavState, request: RouteRequest?) {
        pendingState = state
        pendingRequest = request
        if (!attached) return
        val since = SystemClock.uptimeMillis() - lastRenderUptime
        if (since >= MIN_FRAME_MS) {
            handler.removeCallbacks(renderRunnable)
            renderScheduled = false
            renderNow()
        } else if (!renderScheduled) {
            renderScheduled = true
            handler.postDelayed(renderRunnable, MIN_FRAME_MS - since)
        }
    }

    fun onDarkModeChanged(isDark: Boolean) {
        if (attached) renderer.onDarkModeChanged(isDark)
    }

    /** Immediately drop any user pan/zoom (e.g. when guidance starts). */
    fun resetView() {
        handler.removeCallbacks(resetViewRunnable)
        renderer.resetView()
    }

    private fun renderNow() {
        if (!attached) return
        lastRenderUptime = SystemClock.uptimeMillis()
        renderer.render(pendingState, pendingRequest)
    }

    private fun armResetTimer() {
        handler.removeCallbacks(resetViewRunnable)
        handler.postDelayed(resetViewRunnable, GESTURE_RESET_MS)
    }

    private companion object {
        const val MIN_FRAME_MS = 100L
        const val GESTURE_RESET_MS = 8_000L
        const val FLING_PAN_FRACTION = 0.15f
    }
}
