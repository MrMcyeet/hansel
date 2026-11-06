package dev.hansel.routing.graphhopper

import dev.hansel.model.AvoidZone
import dev.hansel.model.LatLng
import dev.hansel.model.ManeuverType
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingOptions
import dev.hansel.model.RoutingResult
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType
import dev.hansel.routing.RouteValidator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every request here runs through [HanselGraphHopper] with `strictInterpreter = true`: Janino is never used. */
class LocalGraphHopperEngineTest {
    private val engine by lazy { TestGraph.engine() }

    private fun request(
        vararg points: Pair<LatLng, WaypointType>,
        options: RoutingOptions = RoutingOptions(alternatives = 0),
        zones: List<AvoidZone> = emptyList(),
    ) = RouteRequest(
        id = "req", waypoints = points.mapIndexed { i, (p, t) -> Waypoint("wp$i", p, t, name = "wp$i") },
        avoidZones = zones, options = options,
    )

    private fun stop(row: Int, col: Int) = TestGraph.node(row, col) to WaypointType.STOP

    private fun ok(r: RoutingResult): RoutingResult {
        assertNull(r.error, "unexpected error ${r.error} warnings=${r.warnings}")
        assertTrue(r.routes.isNotEmpty())
        assertTrue(r.onDevice)
        assertTrue(r.latencyMillis >= 0)
        return r
    }

    private fun route(req: RouteRequest) = runBlocking { engine.route(req) }

    @Test
    fun routesAcrossTheGridWithDepartAndArrive() {
        val r = ok(route(request(stop(0, 0), stop(4, 2))))
        val route = r.primary!!
        assertTrue(route.geometry.size > 2, "geometry ${route.geometry.size}")
        assertEquals(1, route.legs.size)
        val m = route.maneuvers
        assertEquals(ManeuverType.DEPART, m.first().type)
        assertEquals(ManeuverType.ARRIVE, m.last().type)
        assertTrue(m.first().instruction.isNotBlank())
        assertTrue(route.distanceMeters > 1_000 && route.distanceMeters < 1_500, "distance ${route.distanceMeters}")
        assertTrue(route.durationSeconds > 0)
        // Shape indices monotonic and inside the geometry.
        var last = -1
        for (x in m) {
            assertTrue(x.beginShapeIndex >= last, "begin ${x.beginShapeIndex} after $last")
            assertTrue(x.endShapeIndex >= x.beginShapeIndex)
            assertTrue(x.endShapeIndex <= route.geometry.lastIndex)
            last = x.beginShapeIndex
        }
        assertEquals(route.geometry.lastIndex, m.last().endShapeIndex)
        assertEquals(0, route.legs.first().beginShapeIndex)
        assertEquals(route.geometry.lastIndex, route.legs.last().endShapeIndex)
        assertTrue(route.summary.majorRoads.isNotEmpty(), "major roads")
        assertTrue(route.summary.majorRoads.all { it.contains("Street") || it.contains("Avenue") }, "${route.summary.majorRoads}")
        assertEquals(0, route.alternativeIndex)
    }

    @Test
    fun avoidZoneForcesDetourAndValidatorIsHappy() {
        val direct = ok(route(request(stop(2, 0), stop(2, 4)))).primary!!
        assertTrue(direct.distanceMeters < 900, "direct ${direct.distanceMeters}")
        val c = TestGraph.node(2, 2)
        val zone = AvoidZone("z1", listOf(
            LatLng(c.lat - 0.0006, c.lon - 0.0012), LatLng(c.lat - 0.0006, c.lon + 0.0012),
            LatLng(c.lat + 0.0006, c.lon + 0.0012), LatLng(c.lat + 0.0006, c.lon - 0.0012),
        ), label = "center")
        val req = request(stop(2, 0), stop(2, 4), zones = listOf(zone))
        val r = ok(route(req))
        val detour = r.primary!!
        assertTrue(detour.distanceMeters > direct.distanceMeters + 300, "detour ${detour.distanceMeters} vs direct ${direct.distanceMeters}")
        val violations = RouteValidator().validate(req, detour)
        assertTrue(violations.isEmpty(), "violations: $violations")
        assertTrue(detour.geometry.none { dev.hansel.model.GeoMath.pointInPolygon(it, zone.ring) })
    }

    @Test
    fun avoidTollsSkipsTheTollRoad() {
        val direct = ok(route(request(stop(1, 0), stop(1, 4)))).primary!!
        assertTrue(direct.summary.hasTolls, "direct route uses Row 1 Street (toll)")
        assertTrue(direct.maneuvers.any { it.hasToll })
        val r = ok(route(request(stop(1, 0), stop(1, 4), options = RoutingOptions(alternatives = 0, avoidTolls = true))))
        val avoided = r.primary!!
        assertTrue(!avoided.summary.hasTolls, "toll avoided: roads ${avoided.summary.majorRoads}")
        assertTrue(avoided.distanceMeters > direct.distanceMeters + 300, "detour ${avoided.distanceMeters}")
    }

    @Test
    fun avoidUnpavedSkipsTheGravelRoad() {
        val direct = ok(route(request(stop(3, 0), stop(3, 4)))).primary!!
        assertTrue(direct.summary.hasUnpaved, "direct route uses Row 3 Street (gravel)")
        val r = ok(route(request(stop(3, 0), stop(3, 4), options = RoutingOptions(alternatives = 0, avoidUnpaved = true))))
        val avoided = r.primary!!
        assertTrue(!avoided.summary.hasUnpaved, "gravel avoided: roads ${avoided.summary.majorRoads}")
        assertTrue(avoided.distanceMeters > direct.distanceMeters + 300, "detour ${avoided.distanceMeters}")
    }

    @Test
    fun highwayPreferenceUsesOrAvoidsTheBypass() {
        val neutral = ok(route(request(stop(0, 0), stop(4, 4)))).primary!!
        assertTrue(neutral.summary.hasHighway, "motorway bypass is the fastest SW→NE: ${neutral.summary.majorRoads}")
        val avoid = ok(route(request(stop(0, 0), stop(4, 4), options = RoutingOptions(alternatives = 0, highways = dev.hansel.model.HighwayPreference.AVOID)))).primary!!
        assertTrue(!avoid.summary.hasHighway, "avoided: ${avoid.summary.majorRoads}")
        assertTrue(avoid.maneuvers.none { it.hasHighway })
    }

    @Test
    fun onewayIsRespected() {
        val forward = ok(route(request(stop(0, 4), stop(4, 4)))).primary!!
        val backward = ok(route(request(stop(4, 4), stop(0, 4)))).primary!!
        assertTrue(forward.distanceMeters < 900, "forward along Col 4 Avenue ${forward.distanceMeters}")
        assertTrue(backward.distanceMeters > forward.distanceMeters + 300, "backward must detour: ${backward.distanceMeters}")
    }

    @Test
    fun alternativesForTwoPoints() {
        val r = ok(route(request(stop(0, 0), stop(4, 4), options = RoutingOptions(alternatives = 2))))
        assertTrue(r.routes.isNotEmpty())
        r.routes.forEachIndexed { i, route ->
            assertEquals(i, route.alternativeIndex)
            if (i > 0) assertNotNull(route.label)
        }
        assertTrue(r.warnings.none { "alternatives skipped" in it }, "${r.warnings}")
    }

    @Test
    fun alternativesSkippedBeyondTheFlexibleCap() {
        val capped = TestGraph.engine(flexibleMaxStraightLineMeters = 100.0)
        val r = ok(runBlocking { capped.route(request(stop(0, 0), stop(4, 4), options = RoutingOptions(alternatives = 2))) })
        assertEquals(1, r.routes.size)
        assertTrue(r.warnings.any { "alternatives skipped" in it }, "${r.warnings}")
    }

    @Test
    fun avoidZonesHonouredBeyondTheFlexibleCapWithWarning() {
        val capped = TestGraph.engine(flexibleMaxStraightLineMeters = 100.0)
        val c = TestGraph.node(2, 2)
        val zone = AvoidZone("z1", listOf(
            LatLng(c.lat - 0.0006, c.lon - 0.0012), LatLng(c.lat - 0.0006, c.lon + 0.0012),
            LatLng(c.lat + 0.0006, c.lon + 0.0012), LatLng(c.lat + 0.0006, c.lon - 0.0012),
        ))
        val req = request(stop(2, 0), stop(2, 4), zones = listOf(zone), options = RoutingOptions(alternatives = 0, avoidTolls = true))
        val r = ok(runBlocking { capped.route(req) })
        assertTrue(r.primary!!.distanceMeters > 1_100, "detour ${r.primary!!.distanceMeters}")
        assertTrue(r.warnings.any { "avoid zones honoured" in it }, "${r.warnings}")
        assertTrue(RouteValidator().validate(req, r.primary!!).isEmpty())

        // Options only (no zones) beyond the cap are dropped with a warning and the CH route is returned.
        val r2 = ok(runBlocking { capped.route(request(stop(1, 0), stop(1, 4), options = RoutingOptions(alternatives = 0, avoidTolls = true))) })
        assertTrue(r2.warnings.any { "avoid options ignored" in it }, "${r2.warnings}")
        assertTrue(r2.primary!!.summary.hasTolls)
    }

    @Test
    fun threePointsYieldTwoLegs() {
        val req = request(stop(0, 0), stop(2, 2), stop(4, 0))
        val r = ok(route(req))
        val route = r.primary!!
        assertEquals(2, route.legs.size, "legs: ${route.legs.map { it.maneuvers.map { m -> m.type } }}")
        assertEquals(ManeuverType.WAYPOINT_STOP, route.legs[0].maneuvers.last().type)
        assertEquals(ManeuverType.ARRIVE, route.legs[1].maneuvers.last().type)
        assertEquals(route.legs[0].endShapeIndex, route.legs[1].beginShapeIndex)
        assertEquals(route.geometry.lastIndex, route.legs[1].endShapeIndex)
        val mid = route.geometry[route.legs[0].endShapeIndex]
        assertTrue(mid.distanceTo(TestGraph.node(2, 2)) < 20, "leg boundary at the stop: $mid")
        assertEquals(route.distanceMeters, route.legs.sumOf { it.distanceMeters }, 1.0)
        val violations = RouteValidator().validate(req, route)
        assertTrue(violations.isEmpty(), "$violations")
    }

    @Test
    fun throughPointDoesNotSplitLegsAndWarns() {
        val r = ok(route(request(stop(0, 0), TestGraph.node(2, 2) to WaypointType.THROUGH, stop(4, 0))))
        val route = r.primary!!
        assertEquals(1, route.legs.size)
        assertTrue(route.maneuvers.any { it.type == ManeuverType.WAYPOINT_THROUGH })
        assertTrue(r.warnings.any { "pass_through" in it }, "${r.warnings}")
    }

    @Test
    fun unreachablePointFarOutside() {
        val r = route(request(stop(0, 0), LatLng(31.5, -88.0) to WaypointType.STOP))
        val err = r.error
        assertTrue(err is RoutingError.Unreachable, "expected Unreachable but was $err")
        assertEquals("wp1", err.waypointId)
        assertTrue(r.routes.isEmpty())
    }

    @Test
    fun requestCustomModelMatchesTheOldAdapterRules() {
        val model = engine.buildCustomModel(RoutingOptions(avoidTolls = true, avoidFerries = true, avoidUnpaved = true, highways = dev.hansel.model.HighwayPreference.PREFER),
            listOf(AvoidZone("z", listOf(LatLng(30.0, -89.0), LatLng(30.0, -88.9), LatLng(30.1, -88.9)))))!!
        val conditions = model.priority.map { it.condition() to it.value() }
        assertTrue(("toll == ALL" to "0.1") in conditions, "$conditions")
        assertTrue(("road_environment == FERRY" to "0.1") in conditions)
        assertTrue(("road_class != MOTORWAY" to "0.8") in conditions)
        assertTrue(("in_hansel_avoid_0" to "0") in conditions)
        assertTrue(conditions.any { it.first.startsWith("surface == ") && it.second == "0.1" })
        assertEquals("hansel_avoid_0", model.areas.features.single().id)
        assertNull(engine.buildCustomModel(RoutingOptions(), emptyList()))
    }
}
