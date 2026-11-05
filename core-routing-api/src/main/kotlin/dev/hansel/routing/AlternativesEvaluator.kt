package dev.hansel.routing

import dev.hansel.model.GeoMath
import dev.hansel.model.Route
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shared re-scoring and de-duplication of candidate routes (DESIGN §5.3). Works across engines: candidates from
 * Valhalla and GraphHopper can be pooled, trivially identical ones dropped, and the survivors labelled by what
 * makes them different from the primary. ETAs from different engines are *not* assumed comparable; the label
 * reports the engine's own ETA delta and the discrepancy is surfaced separately by [RoutingOrchestrator].
 */
class AlternativesEvaluator(
    /** Two routes with more than this much mutual overlap are considered the same route. */
    private val maxOverlap: Double = 0.85,
    /** Alternatives slower than primary by more than this factor are discarded as not meaningful. */
    private val maxDurationFactor: Double = 1.6,
    private val maxAlternatives: Int = 3,
) {
    data class Evaluated(val primary: Route, val alternatives: List<Route>, val dropped: List<Dropped>)
    data class Dropped(val route: Route, val reason: String)

    fun evaluate(candidates: List<Route>): Evaluated? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.durationSeconds }
        val primary = sorted.first()
        val kept = ArrayList<Route>()
        val dropped = ArrayList<Dropped>()
        for (c in sorted.drop(1)) {
            if (kept.size >= maxAlternatives) { dropped += Dropped(c, "more than $maxAlternatives alternatives"); continue }
            if (c.durationSeconds > primary.durationSeconds * maxDurationFactor) {
                dropped += Dropped(c, "too slow (${pct(c.durationSeconds / primary.durationSeconds)} of primary)"); continue
            }
            val vsPrimary = mutualOverlap(c, primary)
            if (vsPrimary > maxOverlap) { dropped += Dropped(c, "${pct(vsPrimary)} overlap with primary"); continue }
            val dup = kept.firstOrNull { mutualOverlap(c, it) > maxOverlap }
            if (dup != null) { dropped += Dropped(c, "${pct(mutualOverlap(c, dup))} overlap with another alternative"); continue }
            kept += c.copy(label = c.label ?: describeDifference(primary, c))
        }
        return Evaluated(primary.copy(label = primary.label ?: "fastest"), kept, dropped)
    }

    fun mutualOverlap(a: Route, b: Route): Double =
        (GeoMath.overlapFraction(a.geometry, b.geometry) + GeoMath.overlapFraction(b.geometry, a.geometry)) / 2.0

    /** "via US-49 · +6 min · −3.1 mi · no tolls" */
    fun describeDifference(primary: Route, alt: Route): String {
        val parts = ArrayList<String>()
        val newRoads = alt.summary.majorRoads.filter { it !in primary.summary.majorRoads }
        if (newRoads.isNotEmpty()) parts += "via " + newRoads.take(2).joinToString(", ")
        val dMin = ((alt.durationSeconds - primary.durationSeconds) / 60.0).roundToInt()
        if (dMin != 0) parts += (if (dMin > 0) "+" else "−") + "${abs(dMin)} min"
        val dMi = (alt.distanceMeters - primary.distanceMeters) / 1609.344
        if (abs(dMi) >= 0.1) parts += (if (dMi > 0) "+" else "−") + "%.1f mi".format(abs(dMi))
        if (primary.summary.hasTolls && !alt.summary.hasTolls) parts += "no tolls"
        if (!primary.summary.hasTolls && alt.summary.hasTolls) parts += "tolls"
        if (primary.summary.hasHighway && !alt.summary.hasHighway) parts += "avoids highway"
        if (alt.engine != primary.engine) parts += alt.engine.name.lowercase()
        return if (parts.isEmpty()) "alternative" else parts.joinToString(" · ")
    }

    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
}
