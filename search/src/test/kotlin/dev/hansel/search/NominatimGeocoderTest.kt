package dev.hansel.search

import dev.hansel.model.LatLng
import dev.hansel.model.PlaceSource
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NominatimGeocoderTest {
    private lateinit var server: MockWebServer
    private val errors = mutableListOf<String>()

    private val jackson = LatLng(32.2988, -90.1848)

    @BeforeTest fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest fun tearDown() { server.close() }

    private fun geocoder(): NominatimGeocoder = NominatimGeocoder(
        baseUrl = server.url("/").toString(),
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        onError = { errors += it },
    )

    @Test fun searchSendsExpectedQueryAndMapsResults() = runTest {
        server.enqueue(MockResponse(code = 200, body = HATTIESBURG_SEARCH))

        val places = geocoder().search("Hattiesburg", near = jackson, limit = 5)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val url = recorded.url
        assertEquals("/search", url.encodedPath)
        assertEquals("Hattiesburg", url.queryParameter("q"))
        assertEquals("jsonv2", url.queryParameter("format"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals("1", url.queryParameter("addressdetails"))
        assertEquals("0", url.queryParameter("bounded"))
        assertEquals("Hansel/0.1 (personal)", recorded.headers["User-Agent"])

        // viewbox = west,north,east,south around `near` (50 km default bias radius)
        val box = assertNotNull(url.queryParameter("viewbox")).split(",").map { it.toDouble() }
        assertEquals(4, box.size)
        val (west, north, east, south) = box
        assertTrue(west < jackson.lon && east > jackson.lon, "viewbox lon range $west..$east")
        assertTrue(south < jackson.lat && north > jackson.lat, "viewbox lat range $south..$north")
        assertEquals(0.9, north - south, 0.05) // 100 km tall ~= 0.9 deg

        assertEquals(2, places.size)
        val city = places[0]
        assertEquals("relation/171143", city.id)
        assertEquals("Hattiesburg", city.name)
        assertEquals("Hattiesburg, Forrest County, Mississippi, United States", city.address)
        assertEquals(31.3271189, city.point.lat, 1e-7)
        assertEquals(-89.2903392, city.point.lon, 1e-7)
        assertEquals("boundary/administrative", city.category)
        assertEquals(PlaceSource.SEARCH, city.source)

        // second result has no "name": falls back to the first display_name segment
        val station = places[1]
        assertEquals("node/9876", station.id)
        assertEquals("Hattiesburg Station", station.name)
        assertEquals("railway/station", station.category)
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
    }

    @Test fun searchWithoutNearOmitsViewboxAndHandlesHttpErrors() = runTest {
        server.enqueue(MockResponse(code = 500, body = "boom"))

        val places = geocoder().search("nowhere", near = null)

        val url = server.takeRequest().url
        assertNull(url.queryParameter("viewbox"))
        assertNull(url.queryParameter("bounded"))
        assertEquals("8", url.queryParameter("limit")) // interface default
        assertTrue(places.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("HTTP 500"), errors[0])
    }

    @Test fun reverseSendsLatLonAndMapsSingleObject() = runTest {
        server.enqueue(MockResponse(code = 200, body = JACKSON_REVERSE))

        val place = assertNotNull(geocoder().reverse(jackson))

        val url = server.takeRequest().url
        assertEquals("/reverse", url.encodedPath)
        assertEquals("32.298800", url.queryParameter("lat"))
        assertEquals("-90.184800", url.queryParameter("lon"))
        assertEquals("jsonv2", url.queryParameter("format"))

        assertEquals("way/123456", place.id)
        assertEquals("Mississippi State Capitol", place.name)
        assertEquals("tourism/attraction", place.category)
        assertEquals(32.3038, place.point.lat, 1e-4)
        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
    }

    @Test fun reverseReturnsNullOnUnableToGeocodeAndOnGarbage() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"error":"Unable to geocode"}"""))
        server.enqueue(MockResponse(code = 200, body = "<html>not json</html>"))

        val g = geocoder()
        assertNull(g.reverse(LatLng(0.0, 0.0)))
        assertTrue(errors.isEmpty(), "'Unable to geocode' is not an error: $errors")
        assertNull(g.reverse(jackson))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("invalid JSON"), errors[0])
    }

    private companion object {
        val HATTIESBURG_SEARCH = """
            [
              {
                "place_id": 297615063,
                "licence": "Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright",
                "osm_type": "relation",
                "osm_id": 171143,
                "lat": "31.3271189",
                "lon": "-89.2903392",
                "category": "boundary",
                "type": "administrative",
                "place_rank": 16,
                "importance": 0.52,
                "addresstype": "city",
                "name": "Hattiesburg",
                "display_name": "Hattiesburg, Forrest County, Mississippi, United States",
                "address": {"city": "Hattiesburg", "county": "Forrest County", "state": "Mississippi", "country": "United States", "country_code": "us"},
                "boundingbox": ["31.2524", "31.4126", "-89.4452", "-89.2158"]
              },
              {
                "place_id": 1,
                "osm_type": "node",
                "osm_id": 9876,
                "lat": "31.3240",
                "lon": "-89.2890",
                "category": "railway",
                "type": "station",
                "display_name": "Hattiesburg Station, Newman Street, Hattiesburg, Forrest County, Mississippi, 39401, United States"
              }
            ]
        """.trimIndent()

        val JACKSON_REVERSE = """
            {
              "place_id": 42,
              "osm_type": "way",
              "osm_id": 123456,
              "lat": "32.3038",
              "lon": "-90.1821",
              "category": "tourism",
              "type": "attraction",
              "place_rank": 30,
              "addresstype": "tourism",
              "name": "Mississippi State Capitol",
              "display_name": "Mississippi State Capitol, 400, High Street, Jackson, Hinds County, Mississippi, 39201, United States",
              "address": {"tourism": "Mississippi State Capitol", "house_number": "400", "road": "High Street", "city": "Jackson", "state": "Mississippi", "postcode": "39201", "country_code": "us"},
              "boundingbox": ["32.3030", "32.3046", "-90.1830", "-90.1812"]
            }
        """.trimIndent()
    }
}
