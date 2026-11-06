package dev.hansel.routing.graphhopper

import dev.hansel.model.ManeuverType

object GraphHopperSigns {
    const val UNKNOWN = -99
    const val U_TURN_UNKNOWN = -98
    const val U_TURN_LEFT = -8
    const val KEEP_LEFT = -7
    const val ROUNDABOUT_EXIT = -6
    const val TURN_SHARP_LEFT = -3
    const val TURN_LEFT = -2
    const val TURN_SLIGHT_LEFT = -1
    const val CONTINUE_ON_STREET = 0
    const val TURN_SLIGHT_RIGHT = 1
    const val TURN_RIGHT = 2
    const val TURN_SHARP_RIGHT = 3
    const val FINISH = 4
    const val REACHED_VIA = 5
    const val USE_ROUNDABOUT = 6
    const val KEEP_RIGHT = 7
    const val U_TURN_RIGHT = 8
    const val FERRY = 9

    /** Context-free mapping; the engine special-cases the first CONTINUE (→ DEPART) and REACHED_VIA (stop vs through). */
    fun toManeuverType(sign: Int): ManeuverType = when (sign) {
        U_TURN_UNKNOWN, U_TURN_LEFT -> ManeuverType.UTURN_LEFT // side unknown: assume right-hand traffic
        KEEP_LEFT -> ManeuverType.KEEP_LEFT
        ROUNDABOUT_EXIT -> ManeuverType.ROUNDABOUT_EXIT
        TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        TURN_LEFT -> ManeuverType.TURN_LEFT
        TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT
        CONTINUE_ON_STREET -> ManeuverType.CONTINUE
        TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT
        TURN_RIGHT -> ManeuverType.TURN_RIGHT
        TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
        FINISH -> ManeuverType.ARRIVE
        REACHED_VIA -> ManeuverType.WAYPOINT_STOP
        USE_ROUNDABOUT -> ManeuverType.ROUNDABOUT_ENTER
        KEEP_RIGHT -> ManeuverType.KEEP_RIGHT
        U_TURN_RIGHT -> ManeuverType.UTURN_RIGHT
        FERRY -> ManeuverType.FERRY_ENTER
        else -> ManeuverType.UNKNOWN
    }
}
