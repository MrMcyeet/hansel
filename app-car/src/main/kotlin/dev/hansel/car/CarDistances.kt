package dev.hansel.car

import dev.hansel.navigation.DistanceUnits
import kotlin.math.roundToInt

/**
 * Pure helper that picks the display value + `androidx.car.app.model.Distance.UNIT_*` constant for a distance, in the
 * same spirit as [DistanceUnits.display] (feet under 0.1 mi, one-decimal miles under 10 mi, whole miles after;
 * metric: meters under 500 m, one-decimal km under 10 km, whole km after). Constants copied from car-app 1.7.0 so this
 * file stays Android-free for JVM tests; [CarTemplates] asserts they still match the library.
 */
object CarDistances {
    const val UNIT_METERS = 1
    const val UNIT_KILOMETERS = 2
    const val UNIT_KILOMETERS_P1 = 3
    const val UNIT_MILES = 4
    const val UNIT_MILES_P1 = 5
    const val UNIT_FEET = 6
    const val UNIT_YARDS = 7

    data class Spec(val value: Double, val unit: Int)

    fun spec(meters: Double, units: DistanceUnits): Spec {
        val m = meters.coerceAtLeast(0.0)
        return when (units) {
            DistanceUnits.IMPERIAL -> {
                val mi = m / 1609.344
                when {
                    mi >= 10 -> Spec(mi.roundToInt().toDouble(), UNIT_MILES)
                    mi >= 0.1 -> Spec((mi * 10).roundToInt() / 10.0, UNIT_MILES_P1)
                    else -> Spec((((m * 3.28084) / 10).roundToInt() * 10).toDouble(), UNIT_FEET)
                }
            }
            DistanceUnits.METRIC -> when {
                m >= 10_000 -> Spec((m / 1000).roundToInt().toDouble(), UNIT_KILOMETERS)
                m >= 500 -> Spec((m / 100).roundToInt() / 10.0, UNIT_KILOMETERS_P1)
                else -> Spec(((m / 10).roundToInt() * 10).toDouble(), UNIT_METERS)
            }
        }
    }
}
