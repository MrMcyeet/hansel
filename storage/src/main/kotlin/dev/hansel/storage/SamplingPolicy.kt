package dev.hansel.storage

import dev.hansel.model.Fix
import dev.hansel.model.GeoMath

/**
 * Adaptive GPS sampling (DESIGN §12 "adaptive GPS sampling to limit battery, bounded raw trace retention").
 * Pure function so it is unit-testable without Android. The *content* of a persisted fix is never altered;
 * this only decides whether a fix is worth a row.
 */
class SamplingPolicy(
    val minIntervalMillis: Long = 3_000,
    val minDistanceMeters: Double = 15.0,
    val minBearingDeltaDegrees: Double = 20.0,
) {
    /** Persist [next] when there is no previous persisted fix, or enough time/distance/heading has changed since it. */
    fun shouldPersist(prev: Fix?, next: Fix): Boolean {
        if (prev == null) return true
        if (next.timeMillis - prev.timeMillis >= minIntervalMillis) return true
        if (GeoMath.haversineMeters(prev.point, next.point) >= minDistanceMeters) return true
        val pb = prev.bearing
        val nb = next.bearing
        if (pb != null && nb != null && GeoMath.bearingDelta(pb.toDouble(), nb.toDouble()) >= minBearingDeltaDegrees) return true
        return false
    }

    companion object {
        val DEFAULT = SamplingPolicy()
    }
}
