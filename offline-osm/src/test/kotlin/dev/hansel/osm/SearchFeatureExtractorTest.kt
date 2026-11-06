package dev.hansel.osm

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchFeatureExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun pbf(entities: List<OsmEntity>): File {
        val f = tmp.newFile("search.osm.pbf")
        PbfWriter(f, PbfWriter.Options(blockSize = 5)).use { it.writeAll(entities) }
        return f
    }

    private fun run(entities: List<OsmEntity>): Pair<List<SearchFeature>, SearchFeatureExtractor.Stats> {
        val out = ArrayList<SearchFeature>()
        val progress = ArrayList<Long>()
        val stats = SearchFeatureExtractor().extract(pbf(entities), { out += it }, progress::add)
        assertEquals(stats.features, out.size.toLong())
        assertTrue(progress.isNotEmpty())
        return out to stats
    }

    @Test
    fun `nodes places pois and addresses`() {
        val (features, stats) = run(
            listOf(
                OsmEntity.Node(1, 32.2988, -90.1848, mapOf("place" to "city", "name" to "Jackson", "alt_name" to "Jxn;Jackson", "name:en" to "Jackson")),
                OsmEntity.Node(2, 32.5, -90.5, mapOf("place" to "village", "name" to "Flora")),
                OsmEntity.Node(3, 32.3, -90.2, mapOf("amenity" to "cafe", "name" to "Cups", "addr:housenumber" to "2757", "addr:street" to "Old Canton Rd", "addr:city" to "Jackson", "addr:postcode" to "39216")),
                OsmEntity.Node(4, 32.31, -90.21, mapOf("addr:housenumber" to "100", "addr:street" to "State St", "addr:city" to "Jackson")),
                OsmEntity.Node(5, 32.32, -90.22, mapOf("shop" to "yes", "name" to "Generic Shop")), // shop=yes is not a category
                OsmEntity.Node(6, 32.33, -90.23, mapOf("highway" to "crossing")), // not searchable
                OsmEntity.Node(7, 32.34, -90.24, mapOf("amenity" to "bench")), // unnamed POI: skipped
                OsmEntity.Node(8, 32.35, -90.25, mapOf("place" to "locality", "name" to "Somewhere")), // unsupported place
            ),
        )
        assertEquals(4, features.size)
        assertEquals(8, stats.nodes)
        val jackson = features.first { it.name == "Jackson" }
        assertEquals("city", jackson.category); assertEquals(0, jackson.rank); assertEquals('n', jackson.osmType); assertEquals(1L, jackson.osmId)
        assertEquals(listOf("Jxn"), jackson.altNames)
        assertEquals(32.2988, jackson.lat, 1e-7); assertEquals(-90.1848, jackson.lon, 1e-7)
        assertEquals(2, features.first { it.name == "Flora" }.rank)
        val cups = features.first { it.name == "Cups" }
        assertEquals("poi:cafe", cups.category); assertEquals(7, cups.rank)
        assertEquals("2757", cups.houseNumber); assertEquals("Old Canton Rd", cups.street); assertEquals("Jackson", cups.city); assertEquals("39216", cups.postcode)
        val addr = features.first { it.category == "address" }
        assertEquals("100 State St", addr.name); assertEquals(8, addr.rank); assertEquals("Jackson", addr.city)
        assertNull(features.firstOrNull { it.name == "Generic Shop" })
    }

    @Test
    fun `ways resolve through node ids and streets collapse per name and scope`() {
        val nodes = (1L..12L).map { OsmEntity.Node(it, 32.0 + it * 0.001, -90.0 + it * 0.001) } +
            listOf(OsmEntity.Node(50, 40.0, -100.0), OsmEntity.Node(51, 40.01, -100.01)) // far away cell
        val ways = listOf(
            // three segments of Main St in the same 0.05 degree cell -> one feature
            OsmEntity.Way(100, longArrayOf(1, 2, 3), mapOf("highway" to "residential", "name" to "Main St")),
            OsmEntity.Way(101, longArrayOf(3, 4, 5), mapOf("highway" to "residential", "name" to "Main St", "alt_name" to "Main Street")),
            OsmEntity.Way(102, longArrayOf(5, 6), mapOf("highway" to "residential", "name" to "Main St")),
            // same name far away -> separate feature
            OsmEntity.Way(103, longArrayOf(50, 51), mapOf("highway" to "residential", "name" to "Main St")),
            // same name but tagged with a city -> separate feature keyed by city
            OsmEntity.Way(104, longArrayOf(7, 8), mapOf("highway" to "residential", "name" to "Main St", "addr:city" to "Flora")),
            OsmEntity.Way(105, longArrayOf(8, 9), mapOf("highway" to "proposed", "name" to "Future Rd")), // skipped
            OsmEntity.Way(106, longArrayOf(9, 10), mapOf("highway" to "service")), // unnamed: skipped
            // building POI and address way
            OsmEntity.Way(107, longArrayOf(10, 11, 12, 10), mapOf("building" to "yes", "amenity" to "library", "name" to "Eudora Welty Library")),
            OsmEntity.Way(108, longArrayOf(11, 12, 10, 11), mapOf("building" to "house", "addr:housenumber" to "5", "addr:street" to "Elm St", "addr:postcode" to "39201")),
            OsmEntity.Way(109, longArrayOf(12, 11), mapOf("building" to "yes", "name" to "Named Building")),
            OsmEntity.Way(110, longArrayOf(999), mapOf("highway" to "residential", "name" to "Dangling")), // node missing
        )
        val (features, stats) = run(nodes + ways)

        val streets = features.filter { it.category == "street" }
        assertEquals(3, streets.size, streets.toString())
        val mainJackson = streets.first { it.name == "Main St" && it.city == null && it.lat < 35 }
        assertEquals(6, mainJackson.rank)
        assertEquals(100L, mainJackson.osmId)
        assertEquals(listOf("Main Street"), mainJackson.altNames)
        // mean of (first, middle) node pairs of ways 100 (1,2), 101 (3,4), 102 (5,6): nodes 1..6 -> mean offset 3.5e-3
        assertEquals(32.0035, mainJackson.lat, 1e-6); assertEquals(-89.9965, mainJackson.lon, 1e-6)
        val mainFar = streets.first { it.lat > 35 }
        assertEquals(40.005, mainFar.lat, 1e-6)
        assertEquals("Flora", streets.first { it.city == "Flora" }.city)

        val lib = features.first { it.name == "Eudora Welty Library" }
        assertEquals("poi:library", lib.category); assertEquals('w', lib.osmType); assertEquals(107L, lib.osmId)
        assertEquals(32.010, lib.lat, 1e-6) // first node (10)
        val addr = features.first { it.category == "address" }
        assertEquals("5 Elm St", addr.name); assertEquals("39201", addr.postcode); assertEquals(32.011, addr.lat, 1e-6)
        assertEquals("poi:building", features.first { it.name == "Named Building" }.category)
        assertNull(features.firstOrNull { it.name == "Dangling" })
        assertNull(features.firstOrNull { it.name == "Future Rd" })
        assertEquals(1, stats.unresolved)
        assertEquals(14, stats.nodes); assertEquals(11, stats.ways)
    }

    @Test
    fun `place relations use their label node unless the node already is the place`() {
        val (features, _) = run(
            listOf(
                OsmEntity.Node(1, 33.0, -90.0, mapOf("place" to "town", "name" to "Canton")),
                OsmEntity.Node(2, 33.5, -90.5),
                OsmEntity.Node(3, 33.6, -90.6),
                OsmEntity.Way(10, longArrayOf(2, 3, 2)),
                // label node already emitted as the town -> relation skipped
                OsmEntity.Relation(100, listOf(OsmEntity.Member(EntityType.WAY, 10, "outer"), OsmEntity.Member(EntityType.NODE, 1, "label")), mapOf("type" to "boundary", "place" to "town", "name" to "Canton")),
                // admin_centre node is a plain node -> relation emitted at that node
                OsmEntity.Relation(101, listOf(OsmEntity.Member(EntityType.WAY, 10, "outer"), OsmEntity.Member(EntityType.NODE, 2, "admin_centre")), mapOf("type" to "boundary", "place" to "suburb", "name" to "Northside")),
                // no label/admin_centre -> skipped (multipolygon geometry not resolved)
                OsmEntity.Relation(102, listOf(OsmEntity.Member(EntityType.WAY, 10, "outer")), mapOf("type" to "multipolygon", "place" to "village", "name" to "Nowhere")),
            ),
        )
        assertEquals(2, features.size)
        val canton = features.first { it.name == "Canton" }
        assertEquals('n', canton.osmType)
        val north = features.first { it.name == "Northside" }
        assertEquals('r', north.osmType); assertEquals(101L, north.osmId); assertEquals("suburb", north.category); assertEquals(4, north.rank)
        assertEquals(33.5, north.lat, 1e-7); assertEquals(-90.5, north.lon, 1e-7)
        assertNotNull(north)
    }

    @Test
    fun `ranks are ordered as documented`() {
        val order = listOf("city", "town", "village", "hamlet", "suburb", "neighbourhood", "street", "poi:cafe", "address")
        assertEquals(order.indices.toList(), order.map { SearchFeatureExtractor.rankOf(it) })
    }
}
