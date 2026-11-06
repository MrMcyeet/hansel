package dev.hansel.osm

import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.osm.OSMInputFile
import dev.hansel.model.BoundingBox
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PbfMergerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** "Mississippi": nodes 1..6, ways 10,11, relation 100. Way 11 crosses the border and is shared with "Louisiana". */
    private val stateA = listOf(
        OsmEntity.Node(1, 32.0, -90.0, mapOf("name" to "A1")),
        OsmEntity.Node(2, 32.1, -90.1),
        OsmEntity.Node(3, 32.2, -90.2),
        OsmEntity.Node(5, 31.0, -91.5, mapOf("name" to "border node from A", "place" to "hamlet")),
        OsmEntity.Node(6, 31.0, -91.6),
        OsmEntity.Way(10, longArrayOf(1, 2, 3), mapOf("highway" to "primary", "name" to "US 49")),
        OsmEntity.Way(11, longArrayOf(5, 6), mapOf("highway" to "motorway", "name" to "I-20", "from" to "A")),
        OsmEntity.Relation(100, listOf(OsmEntity.Member(EntityType.WAY, 11, "")), mapOf("type" to "route", "ref" to "I 20")),
    )

    /** "Louisiana": nodes 4..8 (5 and 6 duplicated with different tags), ways 11 (dup) and 12, relations 100 (dup) and 101. */
    private val stateB = listOf(
        OsmEntity.Node(4, 30.5, -91.0),
        OsmEntity.Node(5, 31.0, -91.5, mapOf("name" to "border node from B")),
        OsmEntity.Node(6, 31.0, -91.6, mapOf("note" to "B only tag")),
        OsmEntity.Node(7, 30.4, -91.2),
        OsmEntity.Node(8, 30.3, -91.3),
        OsmEntity.Way(11, longArrayOf(5, 6), mapOf("highway" to "motorway", "name" to "I-20", "from" to "B")),
        OsmEntity.Way(12, longArrayOf(6, 7, 8), mapOf("highway" to "secondary")),
        OsmEntity.Relation(100, listOf(OsmEntity.Member(EntityType.WAY, 11, "")), mapOf("type" to "route", "from" to "B")),
        OsmEntity.Relation(101, listOf(OsmEntity.Member(EntityType.NODE, 7, "label")), mapOf("type" to "site")),
    )

    private fun file(name: String, entities: List<OsmEntity>, options: PbfWriter.Options = PbfWriter.Options(blockSize = 4)): File {
        val f = tmp.newFile(name)
        PbfWriter(f, options).use { it.writeAll(entities) }
        return f
    }

    @Test
    fun `merge unions by id, first input wins, output sorted`() {
        val a = file("a.osm.pbf", stateA, PbfWriter.Options(blockSize = 4, bbox = BoundingBox(30.0, -91.7, 35.0, -88.0), replicationTimestamp = 2000))
        val b = file("b.osm.pbf", stateB, PbfWriter.Options(blockSize = 4, bbox = BoundingBox(28.9, -94.1, 33.1, -88.8), replicationTimestamp = 1000))
        val out = File(tmp.root, "merged.osm.pbf")
        val progress = ArrayList<MergeProgress>()
        val result = PbfMerger.merge(listOf(a, b), out, progress::add)

        assertEquals(8, result.nodes)
        assertEquals(3, result.ways)
        assertEquals(2, result.relations)
        assertEquals(4, result.duplicatesSkipped) // nodes 5,6; way 11; relation 100
        assertEquals(out.length(), result.bytesWritten)
        assertEquals(a.length() + b.length(), result.bytesRead)
        assertTrue(progress.isNotEmpty())
        assertEquals(EntityType.RELATION, progress.last().phase)
        assertEquals(13, progress.last().entitiesWritten)
        assertEquals(1.0, progress.last().fraction, 1e-9)
        assertFalse(File(tmp.root, "merged.osm.pbf.part").exists())

        val merged = PbfReader.open(out).use { r ->
            assertEquals(1000L, r.header.replicationTimestamp) // oldest extract
            val bb = r.header.bbox!!
            assertEquals(28.9, bb.south, 1e-9); assertEquals(-94.1, bb.west, 1e-9); assertEquals(35.0, bb.north, 1e-9); assertEquals(-88.0, bb.east, 1e-9)
            r.entities().toList()
        }
        assertEquals(13, merged.size)
        // type-then-id order
        val keys = merged.map { it.type.ordinal to it.id }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertEquals(keys.toSet().size, keys.size, "ids unique within type")
        assertEquals(listOf(1L, 2, 3, 4, 5, 6, 7, 8), merged.filterIsInstance<OsmEntity.Node>().map { it.id })
        assertEquals(listOf(10L, 11, 12), merged.filterIsInstance<OsmEntity.Way>().map { it.id })
        assertEquals(listOf(100L, 101), merged.filterIsInstance<OsmEntity.Relation>().map { it.id })

        // first wins on the duplicates, other tags preserved
        val byNode = merged.filterIsInstance<OsmEntity.Node>().associateBy { it.id }
        assertEquals(mapOf("name" to "border node from A", "place" to "hamlet"), byNode[5]!!.tags)
        assertEquals(emptyMap(), byNode[6]!!.tags)
        assertEquals("A1", byNode[1]!!.tags["name"])
        assertEquals("A", merged.filterIsInstance<OsmEntity.Way>().first { it.id == 11L }.tags["from"])
        assertEquals("I 20", merged.filterIsInstance<OsmEntity.Relation>().first { it.id == 100L }.tags["ref"])
        assertEquals(listOf(6L, 7, 8), merged.filterIsInstance<OsmEntity.Way>().first { it.id == 12L }.refs.toList())
        // coordinates
        for (n in (stateA + stateB).filterIsInstance<OsmEntity.Node>()) {
            val m = byNode[n.id]!!
            assertTrue(abs(m.lat - n.lat) <= 1e-7 && abs(m.lon - n.lon) <= 1e-7)
        }
    }

    @Test
    fun `merge of one file is a copy`() {
        val a = file("a.osm.pbf", stateA)
        val out = File(tmp.root, "one.osm.pbf")
        val r = PbfMerger.merge(listOf(a), out)
        assertEquals(0, r.duplicatesSkipped)
        assertEquals(stateA.size.toLong(), r.entities)
        assertEquals(stateA.map { it.id }, PbfReader.open(out).use { it.entities().map { e -> e.id }.toList() })
    }

    @Test
    fun `unsorted input is rejected and no partial output is left`() {
        val bad = file("bad.osm.pbf", listOf(OsmEntity.Node(2, 1.0, 1.0), OsmEntity.Node(1, 1.0, 1.0)))
        val out = File(tmp.root, "x.osm.pbf")
        assertFailsWith<IOException> { PbfMerger.merge(listOf(bad), out) }
        assertFalse(out.exists())
        assertFalse(File(tmp.root, "x.osm.pbf.part").exists())
    }

    @Test
    fun `graphhopper reads the merged file`() {
        val a = file("a.osm.pbf", stateA)
        val b = file("b.osm.pbf", stateB)
        val out = File(tmp.root, "gh.osm.pbf")
        val result = PbfMerger.merge(listOf(a, b), out)

        var nodes = 0; var ways = 0; var relations = 0
        val nodeIds = ArrayList<Long>()
        OSMInputFile(out).setWorkerThreads(1).open().use { input ->
            while (true) {
                val e = input.next ?: break
                when (e.type) {
                    ReaderElement.Type.NODE -> {
                        nodes++
                        nodeIds += e.id
                        if (e.id == 5L) {
                            val n = e as ReaderNode
                            assertEquals(31.0, n.lat, 1e-7); assertEquals(-91.5, n.lon, 1e-7)
                            assertEquals("border node from A", n.getTag("name"))
                        }
                    }
                    ReaderElement.Type.WAY -> ways++
                    ReaderElement.Type.RELATION -> relations++
                    else -> {}
                }
            }
        }
        assertEquals(result.nodes, nodes.toLong())
        assertEquals(result.ways, ways.toLong())
        assertEquals(result.relations, relations.toLong())
        assertEquals(listOf(1L, 2, 3, 4, 5, 6, 7, 8), nodeIds)
    }
}
