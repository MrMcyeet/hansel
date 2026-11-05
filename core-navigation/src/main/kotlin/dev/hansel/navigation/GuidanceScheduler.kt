package dev.hansel.navigation

import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import kotlin.math.roundToInt

enum class AnnouncementStage { FAR, APPROACH, NEAR, NOW, ARRIVED }

data class Announcement(val text: String, val maneuverIndex: Int, val stage: AnnouncementStage)

/**
 * Turns progress into spoken/banner announcements at distance thresholds, once per (maneuver, stage).
 * Thresholds scale with the maneuver's approach speed class: long highway legs get an early "in 2 miles" call.
 */
class GuidanceScheduler(private val units: DistanceUnits = DistanceUnits.IMPERIAL) {
    private val announced = HashSet<Pair<Int, AnnouncementStage>>()
    private var lastManeuverIndex = -1

    fun reset() { announced.clear(); lastManeuverIndex = -1 }

    fun next(progress: RouteProgress, speedMps: Float?): Announcement? {
        val idx = progress.upcomingManeuverIndex
        if (idx != lastManeuverIndex) lastManeuverIndex = idx
        val m = progress.upcoming
        val d = progress.distanceToUpcomingMeters
        val fast = (speedMps ?: 0f) > 22f // ~50 mph
        val stage = when {
            m.type.isArrival && d < 25.0 -> AnnouncementStage.ARRIVED
            d < 50.0 -> AnnouncementStage.NOW
            d < 300.0 -> AnnouncementStage.NEAR
            d < (if (fast) 1_600.0 else 800.0) -> AnnouncementStage.APPROACH
            fast && d < 3_300.0 -> AnnouncementStage.FAR
            else -> return null
        }
        // Skip stages we've blown past; only announce the current one once.
        val key = idx to stage
        if (!announced.add(key)) return null
        AnnouncementStage.entries.filter { it.ordinal < stage.ordinal }.forEach { announced.add(idx to it) }
        return Announcement(phrase(m, d, stage), idx, stage)
    }

    fun phrase(m: Maneuver, distanceMeters: Double, stage: AnnouncementStage): String {
        val core = m.verbalPreTransition ?: m.instruction
        return when (stage) {
            AnnouncementStage.NOW -> m.verbalPreTransition?.let { stripLeadingDistance(it) } ?: m.instruction
            AnnouncementStage.ARRIVED -> m.instruction
            else -> "In ${units.spoken(distanceMeters)}, ${lowerFirst(stripLeadingDistance(core))}"
        }
    }

    private fun stripLeadingDistance(s: String) = s.replace(Regex("^In [0-9.,]+ (miles?|feet|kilometers?|meters), ", RegexOption.IGNORE_CASE), "")
    private fun lowerFirst(s: String) = if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)
}

enum class DistanceUnits {
    IMPERIAL, METRIC;

    /** Short display form: "0.3 mi", "450 ft", "1.2 km", "80 m". */
    fun display(meters: Double): String = when (this) {
        IMPERIAL -> {
            val mi = meters / 1609.344
            when {
                mi >= 10 -> "${mi.roundToInt()} mi"
                mi >= 0.2 -> "%.1f mi".format(mi)
                else -> "${((meters * 3.28084) / 10).roundToInt() * 10} ft"
            }
        }
        METRIC -> when {
            meters >= 10_000 -> "${(meters / 1000).roundToInt()} km"
            meters >= 500 -> "%.1f km".format(meters / 1000)
            else -> "${(meters / 10).roundToInt() * 10} m"
        }
    }

    /** Spoken form, rounded to what a person would say. */
    fun spoken(meters: Double): String = when (this) {
        IMPERIAL -> {
            val mi = meters / 1609.344
            when {
                mi >= 1.75 -> "${mi.roundToInt()} miles"
                mi >= 0.9 -> "1 mile"
                mi >= 0.4 -> "half a mile"
                mi >= 0.2 -> "a quarter mile"
                else -> "${((meters * 3.28084) / 50).roundToInt() * 50} feet"
            }
        }
        METRIC -> when {
            meters >= 1_750 -> "${(meters / 1000).roundToInt()} kilometers"
            meters >= 900 -> "1 kilometer"
            meters >= 400 -> "500 meters"
            else -> "${(meters / 50).roundToInt() * 50} meters"
        }
    }

    fun eta(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}

/** Icon-ish glyphs for the next-turn card; the app maps these to drawables. */
fun ManeuverType.glyph(): String = when (this) {
    ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT -> "↰"
    ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT -> "↱"
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.MERGE_LEFT -> "↖"
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.EXIT_RIGHT, ManeuverType.MERGE_RIGHT -> "↗"
    ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT -> "↩"
    ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT -> "↻"
    ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> "⚑"
    ManeuverType.DEPART -> "●"
    ManeuverType.FERRY_ENTER, ManeuverType.FERRY_EXIT -> "⛴"
    else -> "↑"
}
