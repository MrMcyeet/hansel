package dev.hansel.osm

import dev.hansel.model.LatLng
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionCatalogTest {

    @Test
    fun `catalog has 50 states plus DC and PR with Geofabrik urls`() {
        val states = RegionCatalog.usStates()
        assertEquals(52, states.size)
        assertEquals(states.map { it.name }.sorted(), states.map { it.name })
        assertEquals(52, states.map { it.postal }.toSet().size)
        for (s in states) {
            assertTrue(s.id.startsWith("us/"), s.id)
            assertEquals("https://download.geofabrik.de/north-america/${s.id}-latest.osm.pbf", s.pbfUrl)
            assertEquals(s.pbfUrl + ".md5", s.md5Url)
            assertNotNull(s.bbox); assertTrue(s.polygons.isNotEmpty())
            assertTrue(s.polygons.all { it.size >= 4 })
            assertEquals("north-america", s.parent)
        }
        // slugs follow Geofabrik's lowercase-hyphen convention
        assertEquals("us/new-york", RegionCatalog.byPostal("NY")!!.id)
        assertEquals("us/north-carolina", RegionCatalog.byPostal("nc")!!.id)
        assertEquals("us/district-of-columbia", RegionCatalog.byPostal("DC")!!.id)
        assertEquals("us/puerto-rico", RegionCatalog.byPostal("PR")!!.id)
        assertEquals("Mississippi", RegionCatalog.byId("us/mississippi")!!.name)
        assertEquals(listOf("US-MS"), RegionCatalog.byId("us/mississippi")!!.isoCodes)
    }

    @Test
    fun `bundled outline resource stays small`() {
        val bytes = RegionCatalog::class.java.getResourceAsStream("/dev/hansel/osm/us_states.geojson")!!.use { it.readBytes() }
        assertTrue(bytes.size <= 250 * 1024, "us_states.geojson is ${bytes.size} bytes")
    }

    @Test
    fun `regionAt resolves points`() {
        assertEquals("us/mississippi", RegionCatalog.regionAt(LatLng(32.2988, -90.1848))!!.id) // Jackson
        assertEquals("us/louisiana", RegionCatalog.regionAt(LatLng(30.4515, -91.1871))!!.id) // Baton Rouge
        assertEquals("us/alabama", RegionCatalog.regionAt(LatLng(30.6954, -88.0399))!!.id) // Mobile
        assertEquals("us/alaska", RegionCatalog.regionAt(LatLng(61.2181, -149.9003))!!.id) // Anchorage
        assertEquals("us/hawaii", RegionCatalog.regionAt(LatLng(21.3069, -157.8583))!!.id) // Honolulu
        assertEquals("us/michigan", RegionCatalog.regionAt(LatLng(46.5476, -87.3956))!!.id) // Marquette, Upper Peninsula
        assertEquals("us/puerto-rico", RegionCatalog.regionAt(LatLng(18.4655, -66.1057))!!.id) // San Juan
        assertEquals("us/district-of-columbia", RegionCatalog.regionAt(LatLng(38.8977, -77.0365))!!.id) // White House
        assertNull(RegionCatalog.regionAt(LatLng(27.0, -88.0))) // Gulf of Mexico
        assertNull(RegionCatalog.regionAt(LatLng(48.8566, 2.3522))) // Paris
    }

    @Test
    fun `neighbours come from shared boundaries`() {
        fun n(postal: String) = RegionCatalog.byPostal(postal)!!.neighbours.map { RegionCatalog.byId(it)!!.postal!! }.sorted()
        assertEquals(listOf("AL", "AR", "LA", "TN"), n("MS"))
        assertEquals(listOf("AR", "MS", "TX"), n("LA"))
        assertEquals(listOf("FL", "GA", "MS", "TN"), n("AL"))
        assertEquals(listOf("AR", "LA", "NM", "OK"), n("TX"))
        assertEquals(listOf("ID", "OR"), n("WA"))
        assertEquals(listOf("MD", "VA"), n("DC"))
        assertEquals(emptyList(), n("AK"))
        assertEquals(emptyList(), n("HI"))
        assertEquals(emptyList(), n("PR"))
        // symmetric
        for (r in RegionCatalog.usStates()) for (o in r.neighbours) assertTrue(r.id in RegionCatalog.byId(o)!!.neighbours, "${r.id} <-> $o")
    }

    @Test
    fun `distanceToRing is metric`() {
        val square = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0), LatLng(1.0, 1.0), LatLng(1.0, 0.0))
        val d = RegionCatalog.distanceToRingMeters(LatLng(0.5, -0.01), square)
        assertEquals(0.01 * 111_320.0, d, 50.0)
        assertEquals(0.0, RegionCatalog.distanceToRingMeters(LatLng(0.0, 0.5), square), 1e-6)
    }
}
