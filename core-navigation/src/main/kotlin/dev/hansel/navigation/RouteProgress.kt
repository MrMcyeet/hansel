package dev.hansel.navigation

import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import dev.hansel.model.Maneuver
import dev.hansel.model.PolylineProjection
import dev.hansel.model.Route
import kotlinx.serialization.Serializable

/** Where the vehicle is on the route and what comes next. Recomputed on every fix. */
data class RouteProgress(
    val route: Route,
    val snapped: PolylineProjection,
    val distanceAlongMeters: Double,
    val distanceRemainingMeters: Double,
    val durationRemainingSeconds: Double,
    val legIndex: Int,
    /** Index into [Route.maneuvers] of the maneuver the driver is *approaching*. */
    val upcomingManeuverIndex: Int,
    val distanceToUpcomingMeters: Double,
    val fractionComplete: Double,
    val offRouteDistanceMeters: Double,
) {
    val upcoming: Maneuver get() = route.maneuvers[upcomingManeuverIndex]
    val following: Maneuver? get() = route.maneuvers.getOrNull(upcomingManeuverIndex + 1)
    /**
     * Approaching the *final* arrival. Intermediate stops also carry ARRIVE maneuvers (Valhalla emits one per leg,
     * the fake engine too), so this must not fire for them or the trip would end at the first stop.
     */
    val isNearArrival: Boolean get() =
        upcoming.type.isArrival && upcomingManeuverIndex == route.maneuvers.lastIndex && distanceToUpcomingMeters < 40.0
}

/**
 * Pure geometry: snaps a fix to the route and derives progress. Keeps the cumulative distance array per route so the
 * per-fix cost is one polyline scan. A sticky window around the previous position prevents jumping to a later
 * overlapping segment (e.g. out-and-back roads) on noisy fixes.
 */
class RouteTracker(val route: Route) {
    private val cum = GeoMath.cumulativeDistances(route.geometry)
    private val maneuverStartAlong: DoubleArray = DoubleArray(route.maneuvers.size) { i -> cum[route.maneuvers[i].beginShapeIndex.coerceIn(0, cum.lastIndex)] }
    val totalMeters: Double get() = cum.last()
    private var lastAlong: Double = 0.0

    fun reset(alongMeters: Double = 0.0) { lastAlong = alongMeters }

    fun update(fix: Fix): RouteProgress {
        val proj = projectSticky(fix.point)
        lastAlong = proj.alongMeters
        val along = proj.alongMeters
        val remaining = (totalMeters - along).coerceAtLeast(0.0)
        // Upcoming maneuver = first maneuver whose start lies ahead of us (with a little slack so we don't flip early).
        var idx = maneuverStartAlong.indexOfFirst { it > along + 5.0 }
        if (idx < 0) idx = route.maneuvers.lastIndex
        val toUpcoming = (maneuverStartAlong[idx] - along).coerceAtLeast(0.0)
        val legIndex = route.legs.indexOfLast { cum[it.beginShapeIndex.coerceIn(0, cum.lastIndex)] <= along }.coerceAtLeast(0)
        val durRemaining = remainingDuration(along, idx)
        return RouteProgress(
            route, proj, along, remaining, durRemaining, legIndex, idx, toUpcoming,
            if (totalMeters == 0.0) 1.0 else (along / totalMeters).coerceIn(0.0, 1.0), proj.distanceMeters,
        )
    }

    /** Remaining time: remainder of the current maneuver pro-rata, plus every later maneuver's duration. */
    private fun remainingDuration(along: Double, upcomingIdx: Int): Double {
        val ms = route.maneuvers
        if (ms.isEmpty()) return 0.0
        val currentIdx = (upcomingIdx - 1).coerceAtLeast(0)
        val cur = ms[currentIdx]
        val curStart = maneuverStartAlong[currentIdx]
        val curLen = cur.distanceMeters.coerceAtLeast(1.0)
        val frac = ((along - curStart) / curLen).coerceIn(0.0, 1.0)
        var t = cur.durationSeconds * (1.0 - frac)
        for (i in currentIdx + 1 until ms.size) t += ms[i].durationSeconds
        return t
    }

    /** Prefer projections near the last known position; fall back to the global best when nothing is close. */
    private fun projectSticky(p: LatLng): PolylineProjection {
        val global = GeoMath.projectOntoPolyline(p, route.geometry, cum)!!
        if (lastAlong <= 0.0) return global
        // Window: 300 m behind, 2 km ahead of the last position.
        val lo = (lastAlong - 300.0).coerceAtLeast(0.0)
        val hi = (lastAlong + 2000.0).coerceAtMost(totalMeters)
        val iLo = cum.indexOfLast { it <= lo }.coerceAtLeast(0)
        val iHi = cum.indexOfFirst { it >= hi }.let { if (it < 0) cum.lastIndex else it }
        if (iHi - iLo < 1) return global
        val sub = route.geometry.subList(iLo, iHi + 1)
        val local = GeoMath.projectOntoPolyline(p, sub, null) ?: return global
        val localAlong = cum[iLo] + local.alongMeters
        // Accept the windowed snap unless the global one is dramatically closer (then we really did jump).
        return if (local.distanceMeters <= global.distanceMeters + 15.0) {
            local.copy(segmentIndex = local.segmentIndex + iLo, alongMeters = localAlong)
        } else global
    }
}

/** Serializable restore point for process death (DESIGN §12 "Crash recovery"). */
@Serializable
data class NavigationSnapshot(
    val route: Route,
    val distanceAlongMeters: Double,
    val startedAtMillis: Long,
)
