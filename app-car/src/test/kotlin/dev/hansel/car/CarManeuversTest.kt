package dev.hansel.car

import dev.hansel.model.Maneuver
import dev.hansel.model.ManeuverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarManeuversTest {

    private fun m(type: ManeuverType, instruction: String = "Turn left onto Main St", streets: List<String> = emptyList(), exits: Int? = null, exitNumber: String? = null) =
        Maneuver(type, instruction, streetNames = streets, distanceMeters = 100.0, durationSeconds = 10.0, beginShapeIndex = 0, endShapeIndex = 1, roundaboutExitCount = exits, exitNumber = exitNumber)

    @Test
    fun `basic turns map to the car turn types`() {
        assertEquals(CarManeuvers.TYPE_TURN_NORMAL_LEFT, CarManeuvers.spec(ManeuverType.TURN_LEFT).type)
        assertEquals(CarManeuvers.TYPE_TURN_NORMAL_RIGHT, CarManeuvers.spec(ManeuverType.TURN_RIGHT).type)
        assertEquals(CarManeuvers.TYPE_TURN_SLIGHT_LEFT, CarManeuvers.spec(ManeuverType.SLIGHT_LEFT).type)
        assertEquals(CarManeuvers.TYPE_TURN_SHARP_RIGHT, CarManeuvers.spec(ManeuverType.SHARP_RIGHT).type)
        assertEquals(CarManeuvers.TYPE_U_TURN_LEFT, CarManeuvers.spec(ManeuverType.UTURN_LEFT).type)
        assertEquals(CarManeuvers.TYPE_KEEP_RIGHT, CarManeuvers.spec(ManeuverType.KEEP_RIGHT).type)
    }

    @Test
    fun `ramps merges exits and straight variants`() {
        assertEquals(CarManeuvers.TYPE_ON_RAMP_NORMAL_RIGHT, CarManeuvers.spec(ManeuverType.RAMP_RIGHT).type)
        assertEquals(CarManeuvers.TYPE_OFF_RAMP_NORMAL_LEFT, CarManeuvers.spec(ManeuverType.EXIT_LEFT).type)
        assertEquals(CarManeuvers.TYPE_MERGE_SIDE_UNSPECIFIED, CarManeuvers.spec(ManeuverType.MERGE).type)
        assertEquals(CarManeuvers.TYPE_MERGE_LEFT, CarManeuvers.spec(ManeuverType.MERGE_LEFT).type)
        for (t in listOf(ManeuverType.CONTINUE, ManeuverType.STRAIGHT, ManeuverType.KEEP_STRAIGHT, ManeuverType.RAMP_STRAIGHT, ManeuverType.WAYPOINT_THROUGH)) {
            assertEquals(CarManeuvers.TYPE_STRAIGHT, CarManeuvers.spec(t).type, "$t")
        }
        assertEquals(CarManeuvers.TYPE_NAME_CHANGE, CarManeuvers.spec(ManeuverType.BECOMES).type)
    }

    @Test
    fun `arrivals depart ferries and unknown`() {
        assertEquals(CarManeuvers.TYPE_DEPART, CarManeuvers.spec(ManeuverType.DEPART).type)
        assertEquals(CarManeuvers.TYPE_DESTINATION, CarManeuvers.spec(ManeuverType.ARRIVE).type)
        assertEquals(CarManeuvers.TYPE_DESTINATION_LEFT, CarManeuvers.spec(ManeuverType.ARRIVE_LEFT).type)
        assertEquals(CarManeuvers.TYPE_DESTINATION_RIGHT, CarManeuvers.spec(ManeuverType.ARRIVE_RIGHT).type)
        assertEquals(CarManeuvers.TYPE_DESTINATION, CarManeuvers.spec(ManeuverType.WAYPOINT_STOP).type)
        assertEquals(CarManeuvers.TYPE_FERRY_BOAT, CarManeuvers.spec(ManeuverType.FERRY_ENTER).type)
        assertEquals(CarManeuvers.TYPE_UNKNOWN, CarManeuvers.spec(ManeuverType.UNKNOWN).type)
    }

    @Test
    fun `roundabout with exit count uses enter-and-exit type with the exit number`() {
        val s = CarManeuvers.spec(m(ManeuverType.ROUNDABOUT_ENTER, exits = 3))
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, s.type)
        assertEquals(3, s.roundaboutExitNumber)
        assertTrue(s.isRoundaboutEnterAndExit)

        val left = CarManeuvers.spec(m(ManeuverType.ROUNDABOUT_ENTER, exits = 2), drivesOnLeft = true)
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, left.type)
        assertEquals(2, left.roundaboutExitNumber)
    }

    @Test
    fun `roundabout without exit count uses plain enter and exit types`() {
        val enter = CarManeuvers.spec(m(ManeuverType.ROUNDABOUT_ENTER))
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_ENTER_CCW, enter.type)
        assertNull(enter.roundaboutExitNumber)
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_ENTER_CCW, CarManeuvers.spec(m(ManeuverType.ROUNDABOUT_ENTER, exits = 0)).type)
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_EXIT_CCW, CarManeuvers.spec(ManeuverType.ROUNDABOUT_EXIT).type)
        assertEquals(CarManeuvers.TYPE_ROUNDABOUT_EXIT_CW, CarManeuvers.spec(ManeuverType.ROUNDABOUT_EXIT, drivesOnLeft = true).type)
    }

    @Test
    fun `every maneuver type has a mapping`() {
        for (t in ManeuverType.entries) {
            val s = CarManeuvers.spec(t)
            assertTrue(s.type >= 0, "$t")
        }
    }

    @Test
    fun `road name prefers street then exit number`() {
        assertEquals("Main St", CarManeuvers.roadName(m(ManeuverType.TURN_LEFT, streets = listOf("Main St", "US-90"))))
        assertEquals("Exit 42B", CarManeuvers.roadName(m(ManeuverType.EXIT_RIGHT, exitNumber = "42B")))
        assertNull(CarManeuvers.roadName(m(ManeuverType.TURN_LEFT, streets = listOf(" "))))
    }

    @Test
    fun `cue strips a leading distance phrase`() {
        assertEquals("Turn left onto Main St", CarManeuvers.cue(m(ManeuverType.TURN_LEFT, "In 0.3 miles, Turn left onto Main St")))
        assertEquals("Turn left onto Main St", CarManeuvers.cue(m(ManeuverType.TURN_LEFT)))
        assertEquals("turn left", CarManeuvers.cue(m(ManeuverType.TURN_LEFT, "")))
    }
}
