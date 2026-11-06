package dev.hansel.osm

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeofabrikIndexTest {

    private val fixture: String =
        GeofabrikIndexTest::class.java.getResourceAsStream("/dev/hansel/osm/geofabrik-index-trimmed.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `parses the nogeom index shape`() {
        val regions = GeofabrikIndex.parse(fixture)
        assertEquals(11, regions.size)
        val ms = regions.first { it.id == "us/mississippi" }
        assertEquals("Mississippi", ms.name)
        assertEquals("north-america", ms.parent) // Geofabrik nests US states directly under the continent
        assertEquals("MS", ms.postal)
        assertEquals(listOf("US-MS"), ms.isoCodes)
        assertEquals("https://download.geofabrik.de/north-america/us/mississippi-latest.osm.pbf", ms.pbfUrl)
        assertEquals(ms.pbfUrl + ".md5", ms.md5Url)
        assertNull(ms.bbox); assertTrue(ms.polygons.isEmpty()); assertTrue(ms.neighbours.isEmpty())

        val bayern = regions.first { it.id == "bayern" }
        assertEquals("Bayern", bayern.name); assertEquals("germany", bayern.parent); assertNull(bayern.postal)
        assertEquals("https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf", bayern.pbfUrl)

        val europe = regions.first { it.id == "europe" }
        assertNull(europe.parent)

        // the two Georgias stay distinct
        assertEquals("Georgia", regions.first { it.id == "georgia" }.name)
        assertEquals("Georgia", regions.first { it.id == "us/georgia" }.name)
        assertEquals("GA", regions.first { it.id == "us/georgia" }.postal)
        assertNull(regions.first { it.id == "georgia" }.postal)

        assertEquals("District of Columbia", regions.first { it.id == "us/district-of-columbia" }.name)
        assertEquals("US Virgin Islands", regions.first { it.id == "us/us-virgin-islands" }.name)
        assertEquals("VI", regions.first { it.id == "us/us-virgin-islands" }.postal)
    }

    @Test
    fun `load uses the injected fetch and nonUs filters states`() {
        val urls = ArrayList<String>()
        val regions = GeofabrikIndex.load { urls += it; fixture }
        assertEquals(listOf("https://download.geofabrik.de/index-v1-nogeom.json"), urls)
        val nonUs = GeofabrikIndex.nonUs(regions).map { it.id }
        assertEquals(listOf("bayern", "europe", "georgia", "germany", "north-america"), nonUs.sorted())
    }

    @Test
    fun `catalog ids and urls match the live index for US states`() {
        val index = GeofabrikIndex.parse(fixture).associateBy { it.id }
        for (id in listOf("us/mississippi", "us/georgia", "us/puerto-rico", "us/district-of-columbia")) {
            val fromCatalog = RegionCatalog.byId(id)!!
            assertEquals(index[id]!!.pbfUrl, fromCatalog.pbfUrl)
            assertEquals(index[id]!!.postal, fromCatalog.postal)
        }
    }
}
