package dev.hansel.navigation

import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import kotlin.math.max

/**
 * Decides when the driver has actually left the route (DESIGN §12: "don't oscillate on GPS noise").
 * Off-route requires [requiredStrikes] consecutive fixes that are (a) farther than a distance threshold scaled by
 * GPS accuracy, or (b) moving and heading more than [maxBearingDelta]° away from the route's local direction.
 */
class OffRouteDetector(
    private val baseThresholdMeters: Double = 35.0,
    private val accuracyMultiplier: Double = 2.0,
    private val requiredStrikes: Int = 3,
    private val minStrikeSpanMillis: Long = 4_000,
    private val maxBearingDelta: Double = 100.0,
    private val minMovingSpeedMps: Double = 3.0,
) {
    private var strikes = 0
    private var firstStrikeAt = 0L

    fun reset() { strikes = 0; firstStrikeAt = 0L }

    /** @return true when a reroute should be triggered. */
    fun observe(fix: Fix, progress: RouteProgress): Boolean {
        val threshold = max(baseThresholdMeters, (fix.accuracyMeters ?: 10f) * accuracyMultiplier)
        val tooFar = progress.offRouteDistanceMeters > threshold
        val wrongWay = run {
            val speed = fix.speedMps ?: 0f
            val bearing = fix.bearing
            if (speed < minMovingSpeedMps || bearing == null) return@run false
            val g = progress.route.geometry
            val i = progress.snapped.segmentIndex.coerceIn(0, g.size - 2)
            val routeBearing = g[i].bearingTo(g[i + 1])
            GeoMath.bearingDelta(bearing.toDouble(), routeBearing) > maxBearingDelta
        }
        if (tooFar || wrongWay) {
            if (strikes == 0) firstStrikeAt = fix.timeMillis
            strikes++
            return strikes >= requiredStrikes && fix.timeMillis - firstStrikeAt >= minStrikeSpanMillis
        }
        reset()
        return false
    }
}
