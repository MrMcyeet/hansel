package dev.hansel.routing.graphhopper

import dev.hansel.model.AvoidZone
import dev.hansel.model.CorridorLock
import dev.hansel.model.HighwayPreference
import dev.hansel.model.LatLng
import dev.hansel.model.ManeuverType
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingOptions
import dev.hansel.model.Waypoint
import dev.hansel.model.WaypointType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphHopperEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: GraphHopperEngine

    private val origin = Waypoint("o", LatLng(32.2988, -90.1848), name = "Jackson")
    private val stop = Waypoint("s", LatLng(31.3271, -89.2903), name = "Hattiesburg")
    private val dest = Waypoint("d", LatLng(29.9511, -90.0715), name = "New Orleans")

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        engine = GraphHopperEngine(server.url("/").toString(), client, profile = "car", apiKey = "k123")
    }

    @AfterTest fun tearDown() { server.close() }

    private fun takeRequestJson(): JsonObject {
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/route", recorded.url.encodedPath)
        assertEquals("k123", recorded.url.queryParameter("key"))
        return Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
    }

    // ------------------------------------------------------------------ (a) outgoing request

    @Test fun requestMapsPointsPassThroughHeadingsAndAlternatives() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        val request = RouteRequest(
            id = "req-1",
            waypoints = listOf(
                origin.copy(heading = 95.0),
                Waypoint("t1", LatLng(32.0, -90.0), WaypointType.THROUGH),
                stop,
                dest,
            ),
            options = RoutingOptions(alternatives = 2, language = "de-DE"),
        )
        val result = engine.route(request)

        val body = takeRequestJson()
        assertEquals("car", body["profile"]!!.jsonPrimitive.content)
        assertTrue(body["instructions"]!!.jsonPrimitive.boolean)
        assertFalse(body["points_encoded"]!!.jsonPrimitive.boolean)
        assertTrue(body["calc_points"]!!.jsonPrimitive.boolean)
        assertFalse(body["elevation"]!!.jsonPrimitive.boolean)
        assertEquals("de_DE", body["locale"]!!.jsonPrimitive.content)
        assertEquals(listOf("road_class", "toll", "road_environment"), body["details"]!!.jsonArray.map { it.jsonPrimitive.content })

        val points = body["points"]!!.jsonArray.map { it.jsonArray.map { c -> c.jsonPrimitive.double } }
        assertEquals(4, points.size)
        assertEquals(listOf(-90.1848, 32.2988), points[0], "points are [lon, lat]")
        assertTrue(body["pass_through"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(95.0), body["headings"]!!.jsonArray.map { it.jsonPrimitive.double })
        // Only the STOP (index 2) is announced; the THROUGH point (index 1) is silent.
        assertEquals(listOf(2), body["announced_via_points"]!!.jsonArray.map { it.jsonPrimitive.int })
        // Multi-point request: alternatives cannot be requested.
        assertNull(body["algorithm"])
        assertNull(body["custom_model"])
        assertNull(body["ch.disable"])
        assertTrue(result.warnings.any { it.contains("pass_through") }, result.warnings.toString())
        assertTrue(result.warnings.any { it.contains("alternatives") }, result.warnings.toString())
    }

    @Test fun twoPointRequestAsksForNativeAlternatives() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        engine.route(RouteRequest("req-2", listOf(origin, dest), options = RoutingOptions(alternatives = 2)))
        val body = takeRequestJson()
        assertEquals("alternative_route", body["algorithm"]!!.jsonPrimitive.content)
        assertEquals(3, body["alternative_route.max_paths"]!!.jsonPrimitive.int)
        assertEquals(1.4, body["alternative_route.max_weight_factor"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.6, body["alternative_route.max_share_factor"]!!.jsonPrimitive.double, 1e-9)
        assertTrue(body["ch.disable"]!!.jsonPrimitive.boolean)
        assertNull(body["pass_through"])
        assertNull(body["announced_via_points"])
        assertNull(body["headings"])
    }

    @Test fun avoidancesAndZonesBecomeCustomModel() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        val zone = AvoidZone("zone-1", listOf(LatLng(31.0, -90.0), LatLng(31.0, -89.0), LatLng(30.5, -89.5)), label = "flooded")
        engine.route(RouteRequest(
            "req-3", listOf(origin, dest), avoidZones = listOf(zone),
            options = RoutingOptions(avoidTolls = true, avoidFerries = true, avoidUnpaved = true, highways = HighwayPreference.AVOID, alternatives = 0),
        ))
        val body = takeRequestJson()
        assertTrue(body["ch.disable"]!!.jsonPrimitive.boolean, "custom models need flexible mode")
        val model = body["custom_model"]!!.jsonObject
        val priority = model["priority"]!!.jsonArray.map { it.jsonObject }
        val conditions = priority.map { it["if"]!!.jsonPrimitive.content }
        assertTrue("toll == ALL" in conditions, conditions.toString())
        assertTrue("road_environment == FERRY" in conditions, conditions.toString())
        assertTrue("road_class == MOTORWAY" in conditions, conditions.toString())
        assertTrue(conditions.any { it.startsWith("surface == UNPAVED") }, conditions.toString())
        assertTrue("in_hansel_avoid_0" in conditions, conditions.toString())
        val zoneRule = priority.first { it["if"]!!.jsonPrimitive.content == "in_hansel_avoid_0" }
        assertEquals("0", zoneRule["multiply_by"]!!.jsonPrimitive.content)
        val tollRule = priority.first { it["if"]!!.jsonPrimitive.content == "toll == ALL" }
        assertEquals("0.1", tollRule["multiply_by"]!!.jsonPrimitive.content)

        val areas = model["areas"]!!.jsonObject
        assertEquals("FeatureCollection", areas["type"]!!.jsonPrimitive.content)
        val feature = areas["features"]!!.jsonArray.single().jsonObject
        assertEquals("hansel_avoid_0", feature["id"]!!.jsonPrimitive.content)
        val geometry = feature["geometry"]!!.jsonObject
        assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
        val ring = geometry["coordinates"]!!.jsonArray.single().jsonArray.map { it.jsonArray.map { c -> c.jsonPrimitive.double } }
        assertEquals(4, ring.size, "ring must be closed")
        assertEquals(listOf(-90.0, 31.0), ring.first())
        assertEquals(ring.first(), ring.last())
    }

    @Test fun locksExpandIntoOrderedPassThroughPoints() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        val farLock = CorridorLock("far", listOf(LatLng(30.6, -90.1), LatLng(30.4, -90.1)), label = "US-51")
        val nearLock = CorridorLock("near", listOf(LatLng(31.9, -90.15), LatLng(31.7, -90.15)), label = "I-55")
        engine.route(RouteRequest("req-4", listOf(origin, dest), locks = listOf(farLock, nearLock)))
        val body = takeRequestJson()
        val lats = body["points"]!!.jsonArray.map { it.jsonArray[1].jsonPrimitive.double }
        assertEquals(listOf(32.2988, 31.9, 31.7, 30.6, 30.4, 29.9511), lats)
        assertTrue(body["pass_through"]!!.jsonPrimitive.boolean)
        assertEquals(emptyList<Int>(), body["announced_via_points"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    // ------------------------------------------------------------------ (b) response parsing

    @Test fun parsesPathsIntoLegsManeuversAndAlternatives() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        val result = engine.route(RouteRequest("req-5", listOf(origin, stop, dest), options = RoutingOptions(alternatives = 0)))
        server.takeRequest()

        assertNull(result.error, "unexpected error ${result.error}")
        assertTrue(result.isSuccess)
        assertEquals(2, result.routes.size)
        assertTrue(result.latencyMillis >= 0)
        assertFalse(result.onDevice)

        val primary = result.primary!!
        assertEquals(0, primary.alternativeIndex)
        assertEquals(PRIMARY.size, primary.geometry.size)
        assertEquals(PRIMARY, primary.geometry)
        assertEquals(150_000.0, primary.distanceMeters, 1e-6)
        assertEquals(5_860.0, primary.durationSeconds, 1e-6)

        assertEquals(2, primary.legs.size)
        val leg1 = primary.legs[0]; val leg2 = primary.legs[1]
        assertEquals(0, leg1.beginShapeIndex); assertEquals(3, leg1.endShapeIndex)
        assertEquals(3, leg2.beginShapeIndex); assertEquals(6, leg2.endShapeIndex)
        assertEquals(100_500.0, leg1.distanceMeters, 1e-6)
        assertEquals(3_660.0, leg1.durationSeconds, 1e-6)
        assertEquals(49_500.0, leg2.distanceMeters, 1e-6)

        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), leg1.maneuvers.map { it.type })
        assertEquals(listOf(ManeuverType.CONTINUE, ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ARRIVE), leg2.maneuvers.map { it.type })
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 3), leg1.maneuvers.map { it.beginShapeIndex to it.endShapeIndex })
        assertEquals(listOf(3 to 4, 4 to 6, 6 to 6), leg2.maneuvers.map { it.beginShapeIndex to it.endShapeIndex })
        assertEquals("Turn right onto I-55 S", leg1.maneuvers[1].instruction)
        assertEquals(listOf("I-55 S"), leg1.maneuvers[1].streetNames)
        assertEquals(100_000.0, leg1.maneuvers[1].distanceMeters, 1e-6)
        assertEquals(3_600.0, leg1.maneuvers[1].durationSeconds, 1e-6)
        assertEquals(2, leg2.maneuvers[1].roundaboutExitCount)

        assertTrue(primary.summary.hasTolls)
        assertTrue(primary.summary.hasHighway)
        assertFalse(primary.summary.hasFerry)
        assertEquals(listOf("I-55 S", "I-10 E", "US-49"), primary.summary.majorRoads)

        val alt = result.routes[1]
        assertEquals(1, alt.alternativeIndex)
        assertEquals(ALT.size, alt.geometry.size)
        assertEquals(210_000.0, alt.distanceMeters, 1e-6)
        assertEquals(7_200.0, alt.durationSeconds, 1e-6)
        assertEquals(2, alt.legs.size)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), alt.maneuvers.map { it.type })
        assertFalse(alt.summary.hasTolls)
        assertFalse(alt.summary.hasHighway)
        assertEquals("via Hwy 11", alt.label)
    }

    @Test fun throughPointViaIsNotALegBoundary() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoPathResponse()))
        // Same fixture, but the middle point is a THROUGH point. Both via-count interpretations (one per intermediate
        // point, or one per stop) agree there is exactly one REACHED_VIA; it must not split the route.
        val result = engine.route(RouteRequest("req-6", listOf(origin, stop.copy(type = WaypointType.THROUGH), dest), options = RoutingOptions(alternatives = 0)))
        val primary = result.primary!!
        assertEquals(1, primary.legs.size)
        assertEquals(0, primary.legs[0].beginShapeIndex); assertEquals(6, primary.legs[0].endShapeIndex)
        assertEquals(ManeuverType.WAYPOINT_THROUGH, primary.maneuvers[2].type)
        assertTrue(primary.warnings.any { it.contains("pass_through") })
    }

    @Test fun signTableMatchesGraphHopperDocs() {
        assertEquals(ManeuverType.UTURN_LEFT, GraphHopperSigns.toManeuverType(-98))
        assertEquals(ManeuverType.UTURN_LEFT, GraphHopperSigns.toManeuverType(-8))
        assertEquals(ManeuverType.KEEP_LEFT, GraphHopperSigns.toManeuverType(-7))
        assertEquals(ManeuverType.SHARP_LEFT, GraphHopperSigns.toManeuverType(-3))
        assertEquals(ManeuverType.TURN_LEFT, GraphHopperSigns.toManeuverType(-2))
        assertEquals(ManeuverType.SLIGHT_LEFT, GraphHopperSigns.toManeuverType(-1))
        assertEquals(ManeuverType.CONTINUE, GraphHopperSigns.toManeuverType(0))
        assertEquals(ManeuverType.SLIGHT_RIGHT, GraphHopperSigns.toManeuverType(1))
        assertEquals(ManeuverType.TURN_RIGHT, GraphHopperSigns.toManeuverType(2))
        assertEquals(ManeuverType.SHARP_RIGHT, GraphHopperSigns.toManeuverType(3))
        assertEquals(ManeuverType.ARRIVE, GraphHopperSigns.toManeuverType(4))
        assertEquals(ManeuverType.WAYPOINT_STOP, GraphHopperSigns.toManeuverType(5))
        assertEquals(ManeuverType.ROUNDABOUT_ENTER, GraphHopperSigns.toManeuverType(6))
        assertEquals(ManeuverType.KEEP_RIGHT, GraphHopperSigns.toManeuverType(7))
        assertEquals(ManeuverType.UTURN_RIGHT, GraphHopperSigns.toManeuverType(8))
        assertEquals(ManeuverType.FERRY_ENTER, GraphHopperSigns.toManeuverType(9))
        assertEquals(ManeuverType.UNKNOWN, GraphHopperSigns.toManeuverType(-99))
        assertEquals(ManeuverType.UNKNOWN, GraphHopperSigns.toManeuverType(101))
    }

    // ------------------------------------------------------------------ (c) errors

    @Test fun connectionNotFoundMapsToNoRoute() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"message":"Connection between locations not found: [0,1]","hints":[{"message":"Connection between locations not found: [0,1]","details":"com.graphhopper.util.exceptions.ConnectionNotFoundException"}]}"""))
        val result = engine.route(RouteRequest("req-7", listOf(origin, dest)))
        assertFalse(result.isSuccess)
        assertTrue(result.routes.isEmpty())
        val error = assertIs<RoutingError.NoRoute>(result.error)
        assertTrue(error.message.startsWith("Connection between locations not found"), error.message)
    }

    @Test fun cannotFindPointMapsToUnreachableWithWaypointId() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"message":"Cannot find point 1: 31.3271,-89.2903","hints":[{"message":"Cannot find point 1: 31.3271,-89.2903","details":"com.graphhopper.util.exceptions.PointNotFoundException","point_index":1}]}"""))
        val error = assertIs<RoutingError.Unreachable>(engine.route(RouteRequest("req-8", listOf(origin, stop, dest))).error)
        assertEquals("s", error.waypointId)
    }

    @Test fun otherErrorsMapToEngineAndNetworkFailuresToNetwork() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"message":"The requested profile 'hoverboard' does not exist","hints":[{"message":"The requested profile 'hoverboard' does not exist","details":"java.lang.IllegalArgumentException"}]}"""))
        val error = assertIs<RoutingError.Engine>(engine.route(RouteRequest("req-9", listOf(origin, dest))).error)
        assertEquals(400, error.code)
        assertTrue(error.message.contains("hoverboard"))

        server.enqueue(MockResponse(code = 502, body = "<html>bad gateway</html>"))
        assertEquals(502, assertIs<RoutingError.Engine>(engine.route(RouteRequest("req-10", listOf(origin, dest))).error).code)

        val url = server.url("/").toString()
        server.close()
        val offline = GraphHopperEngine(url, OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build())
        assertIs<RoutingError.Network>(offline.route(RouteRequest("req-11", listOf(origin, dest))).error)
    }

    // ------------------------------------------------------------------ (d) health

    @Test fun healthCheckUsesHealthThenFallsBackToInfo() = runTest {
        server.enqueue(MockResponse(code = 200, body = "OK"))
        val health = engine.healthCheck()
        assertTrue(health.reachable)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/health", first.url.encodedPath)

        server.enqueue(MockResponse(code = 404, body = "not found"))
        server.enqueue(MockResponse(code = 200, body = """{"version":"10.0","bbox":[-91,29,-88,33],"profiles":[{"name":"car"}],"import_date":"2026-10-01T00:00"}"""))
        val fallback = engine.healthCheck()
        assertTrue(fallback.reachable)
        assertTrue(fallback.detail.contains("10.0"), fallback.detail)
        server.takeRequest()
        assertEquals("/info", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse(code = 503, body = "down"))
        assertFalse(engine.healthCheck().reachable)
    }

    // ------------------------------------------------------------------ fixture

    companion object {
        val PRIMARY = listOf(
            LatLng(32.2988, -90.1848), LatLng(32.2900, -90.1800), LatLng(31.8000, -89.9000), LatLng(31.3271, -89.2903),
            LatLng(31.3000, -89.3000), LatLng(30.6000, -89.6000), LatLng(29.9511, -90.0715),
        )
        val ALT = listOf(LatLng(32.2988, -90.1848), LatLng(31.3271, -89.2903), LatLng(29.9511, -90.0715))

        private fun coords(points: List<LatLng>) = points.joinToString(",") { "[${it.lon},${it.lat}]" }

        /** Mirrors a real GraphHopper `POST /route` answer with `points_encoded=false`: two paths, one via point. */
        fun twoPathResponse(): String = """
        {
          "hints": {"visited_nodes.sum": 1234, "visited_nodes.average": 617.0},
          "info": {"copyrights": ["GraphHopper", "OpenStreetMap contributors"], "took": 12, "road_data_timestamp": "2026-10-01T00:00:00Z"},
          "paths": [
            {
              "distance": 150000.0,
              "weight": 5860.5,
              "time": 5860000,
              "transfers": 0,
              "points_encoded": false,
              "bbox": [-90.1848, 29.9511, -89.2903, 32.2988],
              "points": {"type": "LineString", "coordinates": [${coords(PRIMARY)}]},
              "instructions": [
                {"distance": 500.0, "heading": 90.0, "sign": 0, "interval": [0, 1], "text": "Continue onto Main St", "time": 60000, "street_name": "Main St"},
                {"distance": 100000.0, "sign": 2, "interval": [1, 3], "text": "Turn right onto I-55 S", "time": 3600000, "street_name": "I-55 S"},
                {"distance": 0.0, "sign": 5, "interval": [3, 3], "text": "Waypoint 1", "time": 0, "street_name": ""},
                {"distance": 2000.0, "sign": 0, "interval": [3, 4], "text": "Continue onto US-49", "time": 100000, "street_name": "US-49"},
                {"distance": 47500.0, "sign": 6, "exit_number": 2, "exited": true, "turn_angle": -3.1, "interval": [4, 6], "text": "At roundabout, take exit 2 onto I-10 E", "time": 2100000, "street_name": "I-10 E"},
                {"distance": 0.0, "sign": 4, "last_heading": 200.0, "interval": [6, 6], "text": "Arrive at destination", "time": 0, "street_name": ""}
              ],
              "legs": [],
              "details": {
                "toll": [[0, 1, "NO"], [1, 3, "ALL"], [3, 6, "NO"]],
                "road_class": [[0, 1, "residential"], [1, 3, "motorway"], [3, 6, "primary"]],
                "road_environment": [[0, 6, "road"]]
              },
              "ascend": 0.0,
              "descend": 0.0,
              "snapped_waypoints": {"type": "LineString", "coordinates": [[-90.1848,32.2988],[-89.2903,31.3271],[-90.0715,29.9511]]}
            },
            {
              "distance": 210000.0,
              "weight": 7200.0,
              "time": 7200000,
              "transfers": 0,
              "points_encoded": false,
              "bbox": [-90.1848, 29.9511, -89.2903, 32.2988],
              "points": {"type": "LineString", "coordinates": [${coords(ALT)}]},
              "instructions": [
                {"distance": 100000.0, "heading": 150.0, "sign": 0, "interval": [0, 1], "text": "Continue onto US-49", "time": 3400000, "street_name": "US-49"},
                {"distance": 0.0, "sign": 5, "interval": [1, 1], "text": "Waypoint 1", "time": 0, "street_name": ""},
                {"distance": 110000.0, "sign": -2, "interval": [1, 2], "text": "Turn left onto Hwy 11", "time": 3800000, "street_name": "Hwy 11"},
                {"distance": 0.0, "sign": 4, "last_heading": 210.0, "interval": [2, 2], "text": "Arrive at destination", "time": 0, "street_name": ""}
              ],
              "legs": [],
              "details": {
                "toll": [[0, 2, "NO"]],
                "road_class": [[0, 2, "primary"]],
                "road_environment": [[0, 2, "road"]]
              },
              "ascend": 0.0,
              "descend": 0.0,
              "snapped_waypoints": {"type": "LineString", "coordinates": [[-90.1848,32.2988],[-89.2903,31.3271],[-90.0715,29.9511]]}
            }
          ]
        }
        """.trimIndent()
    }
}
