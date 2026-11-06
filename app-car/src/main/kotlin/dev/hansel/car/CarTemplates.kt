package dev.hansel.car

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import dev.hansel.navigation.DistanceUnits
import dev.hansel.navigation.NavState
import dev.hansel.navigation.RouteProgress
import java.util.TimeZone

/**
 * Builders for the car-app model objects derived from [NavState]. Kept separate from the Screen so the Trip pushed to
 * `NavigationManager.updateTrip` (cluster / heads-up) and the `RoutingInfo` on the template are built from the same
 * step and estimate.
 */
object CarTemplates {
    init {
        // Guard the Android-free copies of the library constants used by the pure helpers.
        check(CarManeuvers.TYPE_ROUNDABOUT_EXIT_CCW == Maneuver.TYPE_ROUNDABOUT_EXIT_CCW && CarManeuvers.TYPE_DESTINATION == Maneuver.TYPE_DESTINATION) {
            "CarManeuvers constants out of sync with androidx.car.app Maneuver"
        }
        check(CarDistances.UNIT_FEET == Distance.UNIT_FEET && CarDistances.UNIT_KILOMETERS_P1 == Distance.UNIT_KILOMETERS_P1) {
            "CarDistances constants out of sync with androidx.car.app Distance"
        }
    }

    fun distance(meters: Double, units: DistanceUnits): Distance {
        val s = CarDistances.spec(meters, units)
        return Distance.create(s.value, s.unit)
    }

    fun maneuver(m: dev.hansel.model.Maneuver): Maneuver {
        val spec = CarManeuvers.spec(m, CarHooks.drivesOnLeft)
        val b = Maneuver.Builder(spec.type)
        spec.roundaboutExitNumber?.let { b.setRoundaboutExitNumber(it) }
        return b.build()
    }

    fun step(m: dev.hansel.model.Maneuver): Step {
        val b = Step.Builder(CarManeuvers.cue(m)).setManeuver(maneuver(m))
        CarManeuvers.roadName(m)?.let { b.setRoad(it) }
        return b.build()
    }

    /** Current + next step for the template; `null` when there is no progress yet (caller shows loading). */
    fun routingInfo(progress: RouteProgress, units: DistanceUnits): RoutingInfo {
        val b = RoutingInfo.Builder().setCurrentStep(step(progress.upcoming), distance(progress.distanceToUpcomingMeters, units))
        progress.following?.let { b.setNextStep(step(it)) }
        return b.build()
    }

    fun destinationEstimate(progress: RouteProgress, units: DistanceUnits, nowMillis: Long = System.currentTimeMillis()): TravelEstimate {
        val remainingSec = progress.durationRemainingSeconds.coerceAtLeast(0.0)
        return TravelEstimate.Builder(
            distance(progress.distanceRemainingMeters, units),
            DateTimeWithZone.create(nowMillis + (remainingSec * 1000).toLong(), TimeZone.getDefault()),
        ).setRemainingTimeSeconds(remainingSec.toLong()).build()
    }

    /** Estimate for the upcoming step, using the route's average remaining speed to turn distance into time. */
    fun stepEstimate(progress: RouteProgress, units: DistanceUnits, nowMillis: Long = System.currentTimeMillis()): TravelEstimate {
        val avgMps = if (progress.durationRemainingSeconds > 0) progress.distanceRemainingMeters / progress.durationRemainingSeconds else 13.0
        val sec = if (avgMps > 0.1) progress.distanceToUpcomingMeters / avgMps else 0.0
        return TravelEstimate.Builder(
            distance(progress.distanceToUpcomingMeters, units),
            DateTimeWithZone.create(nowMillis + (sec * 1000).toLong(), TimeZone.getDefault()),
        ).setRemainingTimeSeconds(sec.toLong().coerceAtLeast(0)).build()
    }

    /** Trip for `NavigationManager.updateTrip`. While rerouting the trip is marked loading and carries no steps. */
    fun trip(state: NavState.Navigating, units: DistanceUnits, destinationName: String?): Trip {
        val b = Trip.Builder()
        val p = state.progress
        if (state.rerouting || p == null) return b.setLoading(true).build()
        b.addStep(step(p.upcoming), stepEstimate(p, units))
        b.addDestination(Destination.Builder().setName(destinationName?.takeIf { it.isNotBlank() } ?: "Destination").build(), destinationEstimate(p, units))
        CarManeuvers.roadName(p.upcoming)?.let { b.setCurrentRoad(it) }
        return b.build()
    }
}
