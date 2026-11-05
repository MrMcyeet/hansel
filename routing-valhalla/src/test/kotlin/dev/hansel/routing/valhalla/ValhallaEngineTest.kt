package dev.hansel.routing.valhalla

import dev.hansel.model.AvoidZone
import dev.hansel.model.CorridorLock
import dev.hansel.model.HighwayPreference
import dev.hansel.model.LatLng
import dev.hansel.model.ManeuverType
import dev.hansel.model.Polyline
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValhallaEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: ValhallaEngine

    private val origin = Waypoint("o", LatLng(32.2988, -90.1848), name = "Jackson")
    private val stop = Waypoint("s", LatLng(31.3271, -89.2903), name = "Hattiesburg")
    private val dest = Waypoint("d", LatLng(29.9511, -90.0715), name = "New Orleans")

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        engine = ValhallaEngine(server.url("/").toString(), client)
    }

    @AfterTest fun tearDown() { server.close() }

    private fun takeRequestJson(): JsonObject {
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/route", recorded.url.encodedPath)
        return Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
    }

    // ------------------------------------------------------------------ (a) outgoing request

    @Test fun requestMapsWaypointTypesOptionsAndPolygons() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoLegTripWithAlternate()))
        val request = RouteRequest(
            id = "req-1",
            waypoints = listOf(
                origin.copy(heading = 95.0, radiusMeters = 40),
                Waypoint("t1", LatLng(32.0, -90.0), WaypointType.THROUGH),
                Waypoint("v1", LatLng(31.8, -89.9), WaypointType.VIA),
                Waypoint("bt", LatLng(31.5, -89.6), WaypointType.STOP_THROUGH),
                stop,
                dest,
            ),
            avoidZones = listOf(AvoidZone("z1", listOf(LatLng(31.0, -90.0), LatLng(31.0, -89.0), LatLng(30.5, -89.5)))),
            options = RoutingOptions(avoidTolls = true, avoidFerries = true, avoidUnpaved = true, highways = HighwayPreference.AVOID, alternatives = 2, language = "de-DE"),
        )
        engine.route(request)

        val body = takeRequestJson()
        assertEquals("auto", body["costing"]!!.jsonPrimitive.content)
        assertEquals("json", body["format"]!!.jsonPrimitive.content)
        assertEquals("polyline6", body["shape_format"]!!.jsonPrimitive.content)
        assertEquals("kilometers", body["units"]!!.jsonPrimitive.content)
        assertEquals("de-DE", body["language"]!!.jsonPrimitive.content)
        assertEquals("de-DE", body["directions_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals(2, body["alternates"]!!.jsonPrimitive.int)

        val locations = body["locations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("break", "through", "via", "break_through", "break", "break"), locations.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(95, locations[0]["heading"]!!.jsonPrimitive.int)
        assertEquals(40, locations[0]["radius"]!!.jsonPrimitive.int)
        assertNull(locations[1]["heading"])
        assertEquals(32.2988, locations[0]["lat"]!!.jsonPrimitive.double, 1e-9)

        val auto = body["costing_options"]!!.jsonObject["auto"]!!.jsonObject
        assertEquals(0.0, auto["use_tolls"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.0, auto["use_ferry"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.1, auto["use_highways"]!!.jsonPrimitive.double, 1e-9)
        assertTrue(auto["exclude_unpaved"]!!.jsonPrimitive.boolean)

        val polygons = body["exclude_polygons"]!!.jsonArray
        assertEquals(1, polygons.size)
        val ring = polygons[0].jsonArray.map { it.jsonArray.map { c -> c.jsonPrimitive.double } }
        assertEquals(4, ring.size, "ring must be closed")
        assertEquals(listOf(-90.0, 31.0), ring.first())
        assertEquals(ring.first(), ring.last())
    }

    @Test fun requestOmitsOptionalFieldsWhenNeutral() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoLegTripWithAlternate()))
        engine.route(RouteRequest("req-2", listOf(origin, dest), options = RoutingOptions(alternatives = 0)))
        val body = takeRequestJson()
        assertNull(body["costing_options"])
        assertNull(body["alternates"])
        assertNull(body["exclude_polygons"])
        assertEquals("en-US", body["language"]!!.jsonPrimitive.content)
    }

    @Test fun locksExpandIntoOrderedThroughPoints() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoLegTripWithAlternate()))
        // Two locks; the second lies closer to the origin along the chord, so it must come first.
        val farLock = CorridorLock("far", listOf(LatLng(30.6, -90.1), LatLng(30.4, -90.1)), label = "US-51")
        val nearLock = CorridorLock("near", listOf(LatLng(31.9, -90.15), LatLng(31.7, -90.15)), label = "I-55")
        val request = RouteRequest("req-3", listOf(origin, dest), locks = listOf(farLock, nearLock))
        engine.route(request)

        val locations = takeRequestJson()["locations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(6, locations.size)
        assertEquals(listOf("break", "through", "through", "through", "through", "break"), locations.map { it["type"]!!.jsonPrimitive.content })
        val lats = locations.map { it["lat"]!!.jsonPrimitive.double }
        assertEquals(listOf(32.2988, 31.9, 31.7, 30.6, 30.4, 29.9511), lats)
        assertEquals("I-55", locations[1]["name"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ (b) response parsing

    @Test fun parsesTwoLegTripWithAlternate() = runTest {
        server.enqueue(MockResponse(code = 200, body = twoLegTripWithAlternate()))
        val result = engine.route(RouteRequest("req-4", listOf(origin, stop, dest)))
        server.takeRequest()

        assertNull(result.error, "unexpected error ${result.error}")
        assertTrue(result.isSuccess)
        assertEquals(2, result.routes.size)
        assertTrue(result.latencyMillis >= 0)
        assertFalse(result.onDevice)

        val primary = result.primary!!
        assertEquals(0, primary.alternativeIndex)
        assertEquals("req-4", primary.requestId)
        // leg 1 has 4 vertices, leg 2 has 3; the shared vertex is dropped once → 6.
        assertEquals(LEG1.size + LEG2.size - 1, primary.geometry.size)
        assertEquals(LEG1 + LEG2.drop(1), primary.geometry)
        assertEquals(2, primary.legs.size)

        val leg1 = primary.legs[0]; val leg2 = primary.legs[1]
        assertEquals(0, leg1.beginShapeIndex); assertEquals(3, leg1.endShapeIndex)
        assertEquals(3, leg2.beginShapeIndex); assertEquals(5, leg2.endShapeIndex)
        assertEquals(120_500.0, leg1.distanceMeters, 1e-6)
        assertEquals(4_100.0, leg1.durationSeconds, 1e-6)

        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE_RIGHT), leg1.maneuvers.map { it.type })
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT, ManeuverType.ARRIVE), leg2.maneuvers.map { it.type })
        // leg 2 maneuvers are offset by 3 (the shared vertex index).
        assertEquals(listOf(3 to 4, 4 to 4, 4 to 5, 5 to 5), leg2.maneuvers.map { it.beginShapeIndex to it.endShapeIndex })
        assertEquals(listOf(0 to 1, 1 to 3, 3 to 3), leg1.maneuvers.map { it.beginShapeIndex to it.endShapeIndex })
        assertEquals(2, leg2.maneuvers[1].roundaboutExitCount)
        assertEquals("Turn right onto I-55 South.", leg1.maneuvers[1].instruction)
        assertEquals("Turn right onto I-55 South.", leg1.maneuvers[1].verbalPreTransition)
        assertEquals("Continue for 100 kilometers.", leg1.maneuvers[1].verbalPostTransition)
        assertEquals(listOf("I-55 South"), leg1.maneuvers[1].streetNames)
        assertEquals(100_000.0, leg1.maneuvers[1].distanceMeters, 1e-6)
        assertTrue(leg1.maneuvers[1].hasHighway)
        assertTrue(leg1.maneuvers[1].hasToll)

        assertEquals(170_000.0, primary.distanceMeters, 1e-6)
        assertEquals(6_300.0, primary.durationSeconds, 1e-6)
        assertTrue(primary.summary.hasTolls)
        assertTrue(primary.summary.hasHighway)
        assertFalse(primary.summary.hasFerry)
        assertEquals(listOf("I-55 South", "I-10 East", "US-49"), primary.summary.majorRoads)

        val alt = result.routes[1]
        assertEquals(1, alt.alternativeIndex)
        assertEquals(1, alt.legs.size)
        assertEquals(ALT.size, alt.geometry.size)
        assertEquals(210_000.0, alt.distanceMeters, 1e-6)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.ARRIVE), alt.maneuvers.map { it.type })
        assertEquals("via US-49", alt.label)
    }

    @Test fun maneuverCodeTableMatchesValhallaDocs() {
        assertEquals(ManeuverType.DEPART, ValhallaManeuverTypes.toManeuverType(1))
        assertEquals(ManeuverType.ARRIVE_LEFT, ValhallaManeuverTypes.toManeuverType(6))
        assertEquals(ManeuverType.BECOMES, ValhallaManeuverTypes.toManeuverType(7))
        assertEquals(ManeuverType.UTURN_RIGHT, ValhallaManeuverTypes.toManeuverType(12))
        assertEquals(ManeuverType.UTURN_LEFT, ValhallaManeuverTypes.toManeuverType(13))
        assertEquals(ManeuverType.SLIGHT_LEFT, ValhallaManeuverTypes.toManeuverType(16))
        assertEquals(ManeuverType.RAMP_STRAIGHT, ValhallaManeuverTypes.toManeuverType(17))
        assertEquals(ManeuverType.EXIT_LEFT, ValhallaManeuverTypes.toManeuverType(21))
        assertEquals(ManeuverType.KEEP_STRAIGHT, ValhallaManeuverTypes.toManeuverType(22))
        assertEquals(ManeuverType.KEEP_LEFT, ValhallaManeuverTypes.toManeuverType(24))
        assertEquals(ManeuverType.MERGE, ValhallaManeuverTypes.toManeuverType(25))
        assertEquals(ManeuverType.FERRY_EXIT, ValhallaManeuverTypes.toManeuverType(29))
        assertEquals(ManeuverType.MERGE_LEFT, ValhallaManeuverTypes.toManeuverType(38))
        assertEquals(ManeuverType.UNKNOWN, ValhallaManeuverTypes.toManeuverType(0))
        assertEquals(ManeuverType.UNKNOWN, ValhallaManeuverTypes.toManeuverType(30))
        assertEquals(ManeuverType.UNKNOWN, ValhallaManeuverTypes.toManeuverType(99))
    }

    // ------------------------------------------------------------------ (c) errors

    @Test fun noPathErrorMapsToNoRoute() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error_code":442,"error":"No path could be found for input","status_code":400,"status":"Bad Request"}"""))
        val result = engine.route(RouteRequest("req-5", listOf(origin, dest)))
        assertFalse(result.isSuccess)
        assertTrue(result.routes.isEmpty())
        val error = assertIs<RoutingError.NoRoute>(result.error)
        assertEquals("No path could be found for input", error.message)
    }

    @Test fun noEdgesNearLocationMapsToUnreachable() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error_code":171,"error":"No suitable edges near location","status_code":400,"status":"Bad Request"}"""))
        val result = engine.route(RouteRequest("req-6", listOf(origin, dest)))
        assertIs<RoutingError.Unreachable>(result.error)
    }

    @Test fun otherErrorsMapToEngineWithCode() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error_code":125,"error":"No costing method found for 'hoverboard'","status_code":400,"status":"Bad Request"}"""))
        val error = assertIs<RoutingError.Engine>(engine.route(RouteRequest("req-7", listOf(origin, dest))).error)
        assertEquals(125, error.code)

        server.enqueue(MockResponse(code = 500, body = "<html>gateway exploded</html>"))
        val error2 = assertIs<RoutingError.Engine>(engine.route(RouteRequest("req-8", listOf(origin, dest))).error)
        assertEquals(500, error2.code)
    }

    @Test fun networkFailureMapsToNetwork() = runTest {
        val url = server.url("/").toString()
        server.close()
        val offline = ValhallaEngine(url, OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build())
        val result = offline.route(RouteRequest("req-9", listOf(origin, dest)))
        assertIs<RoutingError.Network>(result.error)
    }

    // ------------------------------------------------------------------ (d) health

    @Test fun healthCheckHitsStatus() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"version":"3.5.1","tileset_last_modified":1730000000}"""))
        val health = engine.healthCheck()
        assertTrue(health.reachable)
        assertTrue(health.detail.contains("3.5.1"), health.detail)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/status", recorded.url.encodedPath)

        server.enqueue(MockResponse(code = 503, body = "nope"))
        assertFalse(engine.healthCheck().reachable)
    }

    // ------------------------------------------------------------------ fixture

    companion object {
        val LEG1 = listOf(LatLng(32.2988, -90.1848), LatLng(32.2900, -90.1800), LatLng(31.8000, -89.9000), LatLng(31.3271, -89.2903))
        val LEG2 = listOf(LatLng(31.3271, -89.2903), LatLng(30.6000, -89.6000), LatLng(29.9511, -90.0715))
        val ALT = listOf(LatLng(32.2988, -90.1848), LatLng(31.0000, -89.0000), LatLng(29.9511, -90.0715))

        private fun String.jsonEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        /** Structurally mirrors a real Valhalla `/route` JSON answer: trip with two legs plus one alternate trip. */
        fun twoLegTripWithAlternate(): String {
            // Encoded polylines may contain backslashes and quotes, so JSON-escape them like a real server would.
            val leg1 = Polyline.encode(LEG1, 6).jsonEscaped()
            val leg2 = Polyline.encode(LEG2, 6).jsonEscaped()
            val alt = Polyline.encode(ALT, 6).jsonEscaped()
            return """
            {
              "trip": {
                "locations": [
                  {"type":"break","lat":32.2988,"lon":-90.1848,"original_index":0},
                  {"type":"break","lat":31.3271,"lon":-89.2903,"original_index":1},
                  {"type":"break","lat":29.9511,"lon":-90.0715,"original_index":2}
                ],
                "legs": [
                  {
                    "maneuvers": [
                      {"type":1,"instruction":"Drive east on Main St.","verbal_succinct_transition_instruction":"Drive east.",
                       "verbal_pre_transition_instruction":"Drive east on Main St.","verbal_post_transition_instruction":"Continue for 500 meters.",
                       "street_names":["Main St"],"time":60.0,"length":0.5,"cost":60.0,"begin_shape_index":0,"end_shape_index":1,
                       "travel_mode":"drive","travel_type":"car"},
                      {"type":10,"instruction":"Turn right onto I-55 South.","verbal_transition_alert_instruction":"Turn right onto I-55 South.",
                       "verbal_pre_transition_instruction":"Turn right onto I-55 South.","verbal_post_transition_instruction":"Continue for 100 kilometers.",
                       "street_names":["I-55 South"],"time":3600.0,"length":100.0,"cost":3600.0,"begin_shape_index":1,"end_shape_index":3,
                       "toll":true,"highway":true,"bearing_before":90,"bearing_after":180,"travel_mode":"drive","travel_type":"car"},
                      {"type":5,"instruction":"Your destination is on the right.","verbal_transition_alert_instruction":"Your destination will be on the right.",
                       "verbal_pre_transition_instruction":"Your destination is on the right.","time":0.0,"length":0.0,"cost":0.0,
                       "begin_shape_index":3,"end_shape_index":3,"travel_mode":"drive","travel_type":"car"}
                    ],
                    "summary": {"has_time_restrictions":false,"has_toll":true,"has_highway":true,"has_ferry":false,
                                "min_lat":31.3,"min_lon":-90.2,"max_lat":32.3,"max_lon":-89.2,"time":4100.0,"length":120.5,"cost":4100.0},
                    "shape": "$leg1"
                  },
                  {
                    "maneuvers": [
                      {"type":1,"instruction":"Drive south on US-49.","verbal_pre_transition_instruction":"Drive south on US-49.",
                       "street_names":["US-49"],"time":100.0,"length":2.0,"begin_shape_index":0,"end_shape_index":1,"travel_mode":"drive","travel_type":"car"},
                      {"type":26,"instruction":"Enter the roundabout and take the 2nd exit onto I-10 East.","roundabout_exit_count":2,
                       "street_names":["I-10 East"],"time":10.0,"length":0.1,"begin_shape_index":1,"end_shape_index":1,"travel_mode":"drive","travel_type":"car"},
                      {"type":27,"instruction":"Exit the roundabout onto I-10 East.","street_names":["I-10 East"],"time":2090.0,"length":47.4,
                       "begin_shape_index":1,"end_shape_index":2,"highway":true,"travel_mode":"drive","travel_type":"car"},
                      {"type":4,"instruction":"You have arrived at your destination.","verbal_pre_transition_instruction":"You have arrived at your destination.",
                       "time":0.0,"length":0.0,"begin_shape_index":2,"end_shape_index":2,"travel_mode":"drive","travel_type":"car"}
                    ],
                    "summary": {"has_time_restrictions":false,"has_toll":false,"has_highway":true,"has_ferry":false,
                                "min_lat":29.9,"min_lon":-90.1,"max_lat":31.4,"max_lon":-89.2,"time":2200.0,"length":49.5,"cost":2200.0},
                    "shape": "$leg2"
                  }
                ],
                "summary": {"has_time_restrictions":false,"has_toll":true,"has_highway":true,"has_ferry":false,
                            "min_lat":29.9,"min_lon":-90.2,"max_lat":32.3,"max_lon":-89.2,"time":6300.0,"length":170.0,"cost":6300.0},
                "status_message":"Found route between points","status":0,"units":"kilometers","language":"en-US"
              },
              "alternates": [
                {
                  "trip": {
                    "locations": [
                      {"type":"break","lat":32.2988,"lon":-90.1848,"original_index":0},
                      {"type":"break","lat":29.9511,"lon":-90.0715,"original_index":1}
                    ],
                    "legs": [
                      {
                        "maneuvers": [
                          {"type":1,"instruction":"Drive south on US-49.","street_names":["US-49"],"time":10.0,"length":1.0,"begin_shape_index":0,"end_shape_index":1,"travel_mode":"drive","travel_type":"car"},
                          {"type":8,"instruction":"Continue on US-49.","street_names":["US-49"],"time":7190.0,"length":209.0,"begin_shape_index":1,"end_shape_index":2,"travel_mode":"drive","travel_type":"car"},
                          {"type":4,"instruction":"You have arrived at your destination.","time":0.0,"length":0.0,"begin_shape_index":2,"end_shape_index":2,"travel_mode":"drive","travel_type":"car"}
                        ],
                        "summary": {"has_time_restrictions":false,"has_toll":false,"has_highway":false,"has_ferry":false,
                                    "min_lat":29.9,"min_lon":-90.2,"max_lat":32.3,"max_lon":-89.0,"time":7200.0,"length":210.0,"cost":7200.0},
                        "shape": "$alt"
                      }
                    ],
                    "summary": {"has_time_restrictions":false,"has_toll":false,"has_highway":false,"has_ferry":false,
                                "min_lat":29.9,"min_lon":-90.2,"max_lat":32.3,"max_lon":-89.0,"time":7200.0,"length":210.0,"cost":7200.0},
                    "status_message":"Found route between points","status":0,"units":"kilometers","language":"en-US"
                  }
                }
              ],
              "id": "req-4"
            }
            """.trimIndent()
        }
    }
}
