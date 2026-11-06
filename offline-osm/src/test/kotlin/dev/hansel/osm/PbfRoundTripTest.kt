package dev.hansel.osm

import dev.hansel.model.BoundingBox
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PbfRoundTripTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(file: File, entities: List<OsmEntity>, options: PbfWriter.Options = PbfWriter.Options()) {
        PbfWriter(file, options).use { it.writeAll(entities) }
    }

    private fun read(file: File): List<OsmEntity> = PbfReader.open(file).use { it.entities().toList() }

    @Test
    fun `header is written and read back`() {
        val f = tmp.newFile("h.osm.pbf")
        val bbox = BoundingBox(30.0, -91.7, 35.0, -88.1)
        write(f, emptyList(), PbfWriter.Options(bbox = bbox, source = "test", replicationTimestamp = 1_700_000_000L))
        PbfReader.open(f).use { r ->
            assertEquals(listOf("OsmSchema-V0.6", "DenseNodes"), r.header.requiredFeatures)
            assertTrue("Sort.Type_then_ID" in r.header.optionalFeatures)
            assertEquals("hansel", r.header.writingProgram)
            assertEquals("test", r.header.source)
            assertEquals(1_700_000_000L, r.header.replicationTimestamp)
            val b = r.header.bbox!!
            assertEquals(bbox.south, b.south, 1e-9); assertEquals(bbox.west, b.west, 1e-9)
            assertEquals(bbox.north, b.north, 1e-9); assertEquals(bbox.east, b.east, 1e-9)
            assertNull(r.nextPrimitiveBlock())
        }
    }

    @Test
    fun `nodes ways relations with tags survive a round trip`() {
        val entities = listOf(
            OsmEntity.Node(1, 32.2988, -90.1848, mapOf("name" to "Jackson", "place" to "city")),
            OsmEntity.Node(2, 32.3, -90.19),
            OsmEntity.Node(3, 32.31, -90.2, mapOf("amenity" to "cafe", "name" to "Café Ünïcode ☕")),
            OsmEntity.Node(10_000_000_000L, -33.0, 151.0, mapOf("k" to "")),
            OsmEntity.Way(100, longArrayOf(1, 2, 3), mapOf("highway" to "residential", "name" to "Main St")),
            OsmEntity.Way(101, longArrayOf(3, 2, 10_000_000_000L)),
            OsmEntity.Relation(
                200,
                listOf(
                    OsmEntity.Member(EntityType.NODE, 1, "label"),
                    OsmEntity.Member(EntityType.WAY, 100, "outer"),
                    OsmEntity.Member(EntityType.RELATION, 199, ""),
                ),
                mapOf("type" to "multipolygon", "place" to "city"),
            ),
        )
        val f = tmp.newFile("rt.osm.pbf")
        write(f, entities)
        val back = read(f)
        assertEquals(entities.size, back.size)
        for ((a, b) in entities.zip(back)) assertNodeOrEqual(a, b)
    }

    @Test
    fun `20k dense nodes keep ids and coordinates through delta coding`() {
        val rnd = Random(42)
        val nodes = ArrayList<OsmEntity.Node>()
        var id = 5L
        repeat(20_000) { i ->
            id += 1 + rnd.nextInt(0, 1000) // ascending but gappy, like real extracts
            val tags = when (i % 7) {
                0 -> mapOf("highway" to "crossing")
                3 -> mapOf("name" to "n$i", "shop" to "bakery")
                else -> emptyMap()
            }
            nodes += OsmEntity.Node(id, rnd.nextDouble(-89.9, 89.9), rnd.nextDouble(-179.9, 179.9), tags)
        }
        val f = tmp.newFile("dense.osm.pbf")
        write(f, nodes, PbfWriter.Options(blockSize = 3000)) // several blocks -> several string tables
        val back = read(f)
        assertEquals(nodes.size, back.size)
        for ((a, b) in nodes.zip(back)) assertNodeOrEqual(a, b)
        // delta coding must restart per block: ids keep increasing across block boundaries
        assertTrue(back.zipWithNext().all { (x, y) -> y.id > x.id })
    }

    @Test
    fun `raw blobs are readable too`() {
        val f = tmp.newFile("raw.osm.pbf")
        val nodes = (1L..50L).map { OsmEntity.Node(it, it * 0.5, -it * 0.25) }
        write(f, nodes, PbfWriter.Options(compressionLevel = 0))
        assertEquals(nodes, read(f))
    }

    @Test
    fun `block size splits types and respects the limit`() {
        val f = tmp.newFile("blocks.osm.pbf")
        val entities = (1L..20L).map { OsmEntity.Node(it, 1.0, 1.0) } + (1L..5L).map { OsmEntity.Way(it, longArrayOf(1, 2)) }
        write(f, entities, PbfWriter.Options(blockSize = 8))
        PbfReader.open(f).use { r ->
            var blocks = 0
            while (true) {
                val b = r.nextPrimitiveBlock() ?: break
                blocks++
                for (g in b.primitivegroupList) {
                    val kinds = listOf(g.hasDense() && g.dense.idCount > 0, g.waysCount > 0, g.relationsCount > 0).count { it }
                    assertEquals(1, kinds, "a group must hold one entity type")
                    assertTrue(g.dense.idCount <= 8 && g.waysCount <= 8)
                }
            }
            assertEquals(3 + 1, blocks) // 8+8+4 nodes, 5 ways
        }
    }

    @Test
    fun `garbage is rejected not misread`() {
        val f = tmp.newFile("bad.osm.pbf")
        f.writeBytes(byteArrayOf(0, 0, 0, 5, 1, 2, 3, 4, 5))
        assertFailsWith<IOException> { PbfReader.open(f).use { it.entities().toList() } }
    }

    private fun assertNodeOrEqual(a: OsmEntity, b: OsmEntity) {
        if (a is OsmEntity.Node && b is OsmEntity.Node) {
            assertEquals(a.id, b.id)
            assertTrue(abs(a.lat - b.lat) <= 1e-7, "lat ${a.lat} vs ${b.lat}")
            assertTrue(abs(a.lon - b.lon) <= 1e-7, "lon ${a.lon} vs ${b.lon}")
            assertEquals(a.tags, b.tags)
        } else {
            assertEquals(a, b)
        }
    }
}
