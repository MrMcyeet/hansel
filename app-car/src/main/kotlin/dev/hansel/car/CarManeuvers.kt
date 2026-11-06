package dev.hansel.car

import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType

/**
 * Pure mapping from Hansel's engine-neutral [ManeuverType] to the integer `androidx.car.app.navigation.model.Maneuver.TYPE_*`
 * constants. The constants are copied here (they are `static final int` compile-time values in the library, checked
 * against androidx.car.app:app 1.7.0) so this object has no Android or car-app imports and runs in a plain JVM test.
 * [CarManeuversGuard] in the Android source set asserts at class-load time that the copies still match the library.
 */
object CarManeuvers {
    // androidx.car.app.navigation.model.Maneuver.TYPE_* (1.7.0)
    const val TYPE_UNKNOWN = 0
    const val TYPE_DEPART = 1
    const val TYPE_NAME_CHANGE = 2
    const val TYPE_KEEP_LEFT = 3
    const val TYPE_KEEP_RIGHT = 4
    const val TYPE_TURN_SLIGHT_LEFT = 5
    const val TYPE_TURN_SLIGHT_RIGHT = 6
    const val TYPE_TURN_NORMAL_LEFT = 7
    const val TYPE_TURN_NORMAL_RIGHT = 8
    const val TYPE_TURN_SHARP_LEFT = 9
    const val TYPE_TURN_SHARP_RIGHT = 10
    const val TYPE_U_TURN_LEFT = 11
    const val TYPE_U_TURN_RIGHT = 12
    const val TYPE_ON_RAMP_SLIGHT_LEFT = 13
    const val TYPE_ON_RAMP_SLIGHT_RIGHT = 14
    const val TYPE_ON_RAMP_NORMAL_LEFT = 15
    const val TYPE_ON_RAMP_NORMAL_RIGHT = 16
    const val TYPE_ON_RAMP_SHARP_LEFT = 17
    const val TYPE_ON_RAMP_SHARP_RIGHT = 18
    const val TYPE_ON_RAMP_U_TURN_LEFT = 19
    const val TYPE_ON_RAMP_U_TURN_RIGHT = 20
    const val TYPE_OFF_RAMP_SLIGHT_LEFT = 21
    const val TYPE_OFF_RAMP_SLIGHT_RIGHT = 22
    const val TYPE_OFF_RAMP_NORMAL_LEFT = 23
    const val TYPE_OFF_RAMP_NORMAL_RIGHT = 24
    const val TYPE_FORK_LEFT = 25
    const val TYPE_FORK_RIGHT = 26
    const val TYPE_MERGE_LEFT = 27
    const val TYPE_MERGE_RIGHT = 28
    const val TYPE_MERGE_SIDE_UNSPECIFIED = 29
    const val TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW = 32
    const val TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE = 33
    const val TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW = 34
    const val TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE = 35
    const val TYPE_STRAIGHT = 36
    const val TYPE_FERRY_BOAT = 37
    const val TYPE_FERRY_TRAIN = 38
    const val TYPE_DESTINATION = 39
    const val TYPE_DESTINATION_STRAIGHT = 40
    const val TYPE_DESTINATION_LEFT = 41
    const val TYPE_DESTINATION_RIGHT = 42
    const val TYPE_ROUNDABOUT_ENTER_CW = 43
    const val TYPE_ROUNDABOUT_EXIT_CW = 44
    const val TYPE_ROUNDABOUT_ENTER_CCW = 45
    const val TYPE_ROUNDABOUT_EXIT_CCW = 46

    /** What the car-side `Maneuver.Builder` needs: a type plus the roundabout exit number when the type requires one. */
    data class Spec(val type: Int, val roundaboutExitNumber: Int? = null) {
        val isRoundaboutEnterAndExit: Boolean get() = roundaboutExitNumber != null
    }

    /**
     * @param drivesOnLeft right-hand traffic (US) circulates roundabouts counter-clockwise; left-hand traffic clockwise.
     */
    fun spec(m: Maneuver, drivesOnLeft: Boolean = false): Spec = spec(m.type, m.roundaboutExitCount, drivesOnLeft)

    fun spec(type: ManeuverType, roundaboutExitCount: Int? = null, drivesOnLeft: Boolean = false): Spec = when (type) {
        ManeuverType.DEPART -> Spec(TYPE_DEPART)
        ManeuverType.ARRIVE, ManeuverType.WAYPOINT_STOP -> Spec(TYPE_DESTINATION)
        ManeuverType.ARRIVE_LEFT -> Spec(TYPE_DESTINATION_LEFT)
        ManeuverType.ARRIVE_RIGHT -> Spec(TYPE_DESTINATION_RIGHT)
        ManeuverType.CONTINUE, ManeuverType.STRAIGHT, ManeuverType.KEEP_STRAIGHT, ManeuverType.RAMP_STRAIGHT,
        ManeuverType.WAYPOINT_THROUGH -> Spec(TYPE_STRAIGHT)
        ManeuverType.BECOMES -> Spec(TYPE_NAME_CHANGE)
        ManeuverType.SLIGHT_LEFT -> Spec(TYPE_TURN_SLIGHT_LEFT)
        ManeuverType.SLIGHT_RIGHT -> Spec(TYPE_TURN_SLIGHT_RIGHT)
        ManeuverType.TURN_LEFT -> Spec(TYPE_TURN_NORMAL_LEFT)
        ManeuverType.TURN_RIGHT -> Spec(TYPE_TURN_NORMAL_RIGHT)
        ManeuverType.SHARP_LEFT -> Spec(TYPE_TURN_SHARP_LEFT)
        ManeuverType.SHARP_RIGHT -> Spec(TYPE_TURN_SHARP_RIGHT)
        ManeuverType.UTURN_LEFT -> Spec(TYPE_U_TURN_LEFT)
        ManeuverType.UTURN_RIGHT -> Spec(TYPE_U_TURN_RIGHT)
        ManeuverType.KEEP_LEFT -> Spec(TYPE_KEEP_LEFT)
        ManeuverType.KEEP_RIGHT -> Spec(TYPE_KEEP_RIGHT)
        ManeuverType.MERGE -> Spec(TYPE_MERGE_SIDE_UNSPECIFIED)
        ManeuverType.MERGE_LEFT -> Spec(TYPE_MERGE_LEFT)
        ManeuverType.MERGE_RIGHT -> Spec(TYPE_MERGE_RIGHT)
        ManeuverType.RAMP_LEFT -> Spec(TYPE_ON_RAMP_NORMAL_LEFT)
        ManeuverType.RAMP_RIGHT -> Spec(TYPE_ON_RAMP_NORMAL_RIGHT)
        ManeuverType.EXIT_LEFT -> Spec(TYPE_OFF_RAMP_NORMAL_LEFT)
        ManeuverType.EXIT_RIGHT -> Spec(TYPE_OFF_RAMP_NORMAL_RIGHT)
        ManeuverType.ROUNDABOUT_ENTER -> {
            val exit = roundaboutExitCount?.takeIf { it > 0 }
            when {
                exit != null && drivesOnLeft -> Spec(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, exit)
                exit != null -> Spec(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, exit)
                drivesOnLeft -> Spec(TYPE_ROUNDABOUT_ENTER_CW)
                else -> Spec(TYPE_ROUNDABOUT_ENTER_CCW)
            }
        }
        ManeuverType.ROUNDABOUT_EXIT -> Spec(if (drivesOnLeft) TYPE_ROUNDABOUT_EXIT_CW else TYPE_ROUNDABOUT_EXIT_CCW)
        ManeuverType.FERRY_ENTER, ManeuverType.FERRY_EXIT -> Spec(TYPE_FERRY_BOAT)
        ManeuverType.UNKNOWN -> Spec(TYPE_UNKNOWN)
    }

    /** Road name for the Step: first street name, else the exit number, else null. */
    fun roadName(m: Maneuver): String? = m.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: m.exitNumber?.takeIf { it.isNotBlank() }?.let { "Exit $it" }

    /** Short cue for the step card: the instruction without a leading "In 0.3 miles, " prefix. */
    fun cue(m: Maneuver): String = m.instruction.replace(LEADING_DISTANCE, "").ifBlank { m.type.name.lowercase().replace('_', ' ') }

    private val LEADING_DISTANCE = Regex("^In [0-9.,]+ (miles?|feet|kilometers?|meters), ", RegexOption.IGNORE_CASE)
}
