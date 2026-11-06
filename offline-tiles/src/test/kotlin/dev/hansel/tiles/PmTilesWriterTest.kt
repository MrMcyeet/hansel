package dev.hansel.tiles

import dev.hansel.model.BoundingBox
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmTilesWriterTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = java.nio.file.Files.createTempDirectory("pmtiles-writer").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private val bbox = BoundingBox(30.0, -91.0, 33.0, -88.0)

    @Test fun `writes a readable archive with dedupe and run lengths`() = runBlocking {
        val out = File(dir, "a.pmtiles")
        val tiles = LinkedHashMap<Long, ByteArray>()
        // z0..z3, distinct contents except: z3 tiles with x >= 4 all share one blob (run lengths + dedupe),
        // and tile (2,1,1) duplicates tile (1,0,0) (non-adjacent dedupe).
        for (z in 0..3) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
            val id = PmTiles.zxyToTileId(z, x, y)
            tiles[id] = when {
                z == 3 && x >= 4 -> byteArrayOf(0x7f, 0x7f, 0x7f)
                z == 2 && x == 1 && y == 1 -> "tile-1-0-0".toByteArray()
                else -> "tile-$z-$x-$y".toByteArray()
            }
        }
        val header = PmTilesWriter(out, PmTiles.TileType.MVT, PmTiles.Compression.GZIP, """{"name":"test","vector_layers":[]}""").use { w ->
            tiles.toSortedMap().forEach { (id, b) -> w.addTile(id, b) }
            w.finish(bbox, 0, 3)
        }
        assertEquals(85, header.addressedTiles)
        val distinctBlobs = tiles.values.map { it.toList() }.toSet().size
        assertEquals(distinctBlobs.toLong(), header.tileContents)
        assertTrue(header.tileEntries < header.addressedTiles, "run-length should collapse the z3 x>=4 block")
        assertEquals(0L, header.leafDirsLength)
        assertEquals(PmTiles.HEADER_SIZE.toLong(), header.rootDirOffset)
        assertEquals(PmTiles.ROOT_WINDOW_BYTES.toLong(), header.tileDataOffset)
        assertTrue(header.clustered)
        assertEquals(-91.0, header.minLon); assertEquals(33.0, header.maxLat)
        assertEquals(tiles.values.map { it.toList() }.toSet().sumOf { it.size }.toLong(), header.tileDataLength)

        PmTilesReader(FileRangeSource(out)).use { r ->
            assertEquals(header, r.header())
            assertEquals("""{"name":"test","vector_layers":[]}""", r.metadata())
            for ((id, bytes) in tiles) {
                val (z, x, y) = PmTiles.tileIdToZxy(id)
                assertContentEquals(bytes, r.getTile(z, x, y), "tile $z/$x/$y")
            }
            assertNull(r.getTile(4, 0, 0))
            val shared = r.findEntry(PmTiles.zxyToTileId(3, 4, 0))!!
            val shared2 = r.findEntry(PmTiles.zxyToTileId(3, 7, 7))!!
            assertEquals(shared.offset, shared2.offset)
            assertEquals(r.findEntry(PmTiles.zxyToTileId(1, 0, 0))!!.offset, r.findEntry(PmTiles.zxyToTileId(2, 1, 1))!!.offset)
        }
    }

    @Test fun `splits into leaf directories when the root would exceed the limit`() = runBlocking {
        val out = File(dir, "leaves.pmtiles")
        val n = 2000
        val header = PmTilesWriter(out, PmTiles.TileType.MVT, PmTiles.Compression.NONE, "{}", internalCompression = PmTiles.Compression.NONE, initialLeafSize = 16, maxRootBytes = 600).use { w ->
            for (i in 0 until n) w.addTile(i.toLong(), "t$i-${i * 31 % 17}".toByteArray())
            w.finish(bbox, 0, 5)
        }
        assertTrue(header.leafDirsLength > 0, "expected leaf directories")
        assertTrue(header.rootDirLength <= 600)
        assertTrue(header.rootDirLength > 100, "root should hold many leaf pointers")
        assertTrue(header.leafDirsOffset >= header.metadataOffset + header.metadataLength)
        PmTilesReader(FileRangeSource(out)).use { r ->
            val root = r.rootDirectory()
            assertTrue(root.all { it.isLeaf })
            assertTrue(root.size in 2..n)
            for (i in 0 until n) {
                val (z, x, y) = PmTiles.tileIdToZxy(i.toLong())
                assertContentEquals("t$i-${i * 31 % 17}".toByteArray(), r.getTile(z, x, y), "tile $i")
            }
            assertNull(r.findEntry(n.toLong()))
        }
    }

    @Test fun `rejects unsorted ids and empty archives`() {
        val out = File(dir, "bad.pmtiles")
        PmTilesWriter(out, PmTiles.TileType.MVT, PmTiles.Compression.GZIP, "{}").use { w ->
            w.addTile(5, byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { w.addTile(5, byteArrayOf(2)) }
            assertFailsWith<IllegalArgumentException> { w.addTile(3, byteArrayOf(2)) }
        }
        PmTilesWriter(File(dir, "empty.pmtiles"), PmTiles.TileType.MVT, PmTiles.Compression.GZIP, "{}").use { w ->
            assertFailsWith<IllegalStateException> { w.finish(bbox, 0, 1) }
        }
    }

    @Test fun `long long map survives growth and collisions`() {
        val m = LongLongMap(4)
        for (i in 0 until 10_000L) m.put(i * 0x9E3779B97F4A7C15uL.toLong(), i)
        for (i in 0 until 10_000L) assertEquals(i, m.get(i * 0x9E3779B97F4A7C15uL.toLong()))
        assertEquals(LongLongMap.MISSING, m.get(12345))
    }
}
