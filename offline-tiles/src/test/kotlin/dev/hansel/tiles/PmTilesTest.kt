package dev.hansel.tiles

import dev.hansel.tiles.PmTiles.Entry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PmTilesTest {
    // Vectors from spec/v3/spec.md and the JS/Go reference test suites.
    private val vectors = listOf(
        Triple(0, 0, 0) to 0L,
        Triple(1, 0, 0) to 1L,
        Triple(1, 0, 1) to 2L,
        Triple(1, 1, 1) to 3L,
        Triple(1, 1, 0) to 4L,
        Triple(2, 0, 0) to 5L,
        Triple(12, 3423, 1763) to 19078479L,
    )

    @Test fun `zxy to tile id matches spec vectors`() {
        for ((zxy, id) in vectors) {
            val (z, x, y) = zxy
            assertEquals(id, PmTiles.zxyToTileId(z, x, y), "tileId($z,$x,$y)")
            assertEquals(PmTiles.Zxy(z, x, y), PmTiles.tileIdToZxy(id), "zxy($id)")
        }
    }

    @Test fun `tile ids round trip and are dense per zoom`() {
        for (z in 0..6) {
            val n = 1 shl z
            val seen = HashSet<Long>()
            for (x in 0 until n) for (y in 0 until n) {
                val id = PmTiles.zxyToTileId(z, x, y)
                assertTrue(seen.add(id), "duplicate id $id")
                assertTrue(id >= PmTiles.zoomOffset(z) && id < PmTiles.zoomOffset(z + 1), "id $id outside zoom $z block")
                assertEquals(PmTiles.Zxy(z, x, y), PmTiles.tileIdToZxy(id))
            }
        }
        // high zoom sanity (z=15 is the planet build's max; z=20 for good measure)
        assertEquals(PmTiles.Zxy(15, 7813, 13000), PmTiles.tileIdToZxy(PmTiles.zxyToTileId(15, 7813, 13000)))
        assertEquals(PmTiles.Zxy(20, 1_000_000, 3), PmTiles.tileIdToZxy(PmTiles.zxyToTileId(20, 1_000_000, 3)))
        assertEquals(PmTiles.zoomOffset(15), PmTiles.zxyToTileId(15, 0, 0))
        assertEquals(1431655765L, PmTiles.zoomOffset(16)) // "addressed tiles" of a full z0-15 planet
    }

    @Test fun `zxy bounds are validated`() {
        assertFailsWith<IllegalArgumentException> { PmTiles.zxyToTileId(1, 2, 0) }
        assertFailsWith<IllegalArgumentException> { PmTiles.zxyToTileId(3, 0, 8) }
    }

    @Test fun `header round trips through 127 bytes`() {
        val h = PmTiles.Header(
            rootDirOffset = 127, rootDirLength = 1000, metadataOffset = 5000, metadataLength = 300,
            leafDirsOffset = 5300, leafDirsLength = 0, tileDataOffset = 16384, tileDataLength = 123456789012L,
            addressedTiles = 1431655765, tileEntries = 178552352, tileContents = 136646434,
            clustered = true, internalCompression = PmTiles.Compression.GZIP, tileCompression = PmTiles.Compression.GZIP,
            tileType = PmTiles.TileType.MVT, minZoom = 0, maxZoom = 15,
            minLon = -180.0, minLat = -85.0511287, maxLon = 180.0, maxLat = 85.0511287,
            centerZoom = 7, centerLon = -89.5, centerLat = 32.25,
        )
        val bytes = h.serialize()
        assertEquals(PmTiles.HEADER_SIZE, bytes.size)
        assertEquals("PMTiles", String(bytes, 0, 7, Charsets.US_ASCII))
        assertEquals(3, bytes[7].toInt())
        assertEquals(h, PmTiles.Header.parse(bytes))
        assertFailsWith<IllegalArgumentException> { PmTiles.Header.parse("notpmtiles".toByteArray() + ByteArray(120)) }
    }

    @Test fun `directory encoding uses delta ids and the zero offset rule`() {
        // spec example: ids 5, 42, 69 -> deltas 5, 37, 27; contiguous offsets encode as 0, others as offset+1
        val entries = listOf(
            Entry(5, 0, 10, 1),
            Entry(42, 10, 20, 3),   // contiguous with previous -> 0
            Entry(69, 1337, 42, 1), // jump -> 1338
        )
        val enc = PmTiles.encodeDirectory(entries)
        // count, ids, runs, lengths, offsets
        val expected = byteArrayOf(3, 5, 37, 27, 1, 3, 1, 10, 20, 42, 1, 0) + varint(1338)
        assertContentEquals(expected, enc)
        assertEquals(entries, PmTiles.decodeDirectory(enc))
    }

    @Test fun `directory round trips with large values and leaf entries`() {
        val entries = ArrayList<Entry>()
        var id = 0L
        var off = 0L
        for (i in 0 until 5000) {
            id += 1 + (i % 7)
            val len = 1 + (i * 7919) % 100_000
            entries += Entry(id, off, len, if (i % 97 == 0) 0 else 1 + i % 3)
            off += if (i % 5 == 0) len + 123_456_789_000L else len.toLong()
        }
        val enc = PmTiles.encodeDirectory(entries)
        assertEquals(entries, PmTiles.decodeDirectory(enc))
        val gz = PmTiles.compress(enc, PmTiles.Compression.GZIP)
        assertEquals(entries, PmTiles.decodeDirectory(PmTiles.decompress(gz, PmTiles.Compression.GZIP)))
    }

    @Test fun `findTile honours run length and leaf semantics`() {
        val dir = listOf(
            Entry(5, 1337, 42, 1),
            Entry(10, 2000, 7, 3),  // covers 10, 11, 12
            Entry(100, 0, 500, 0),  // leaf: anything >= 100 until the next entry
            Entry(200, 9000, 1, 1),
        )
        assertNull(PmTiles.findTile(dir, 4))
        assertSame(dir[0], PmTiles.findTile(dir, 5))
        assertNull(PmTiles.findTile(dir, 6))
        assertSame(dir[1], PmTiles.findTile(dir, 10))
        assertSame(dir[1], PmTiles.findTile(dir, 12))
        assertNull(PmTiles.findTile(dir, 13))
        assertSame(dir[2], PmTiles.findTile(dir, 100))
        assertSame(dir[2], PmTiles.findTile(dir, 150))
        assertSame(dir[3], PmTiles.findTile(dir, 200))
        assertNull(PmTiles.findTile(dir, 201))
        assertNull(PmTiles.findTile(emptyList(), 0))
    }

    @Test fun `varints round trip`() {
        val values = listOf(0L, 1L, 127L, 128L, 300L, 16383L, 16384L, Int.MAX_VALUE.toLong(), 1L shl 40, Long.MAX_VALUE)
        val w = PmTiles.VarintWriter()
        values.forEach { w.write(it) }
        val r = PmTiles.VarintReader(w.toByteArray())
        values.forEach { assertEquals(it, r.read()) }
    }

    private fun varint(v: Long): ByteArray = PmTiles.VarintWriter().also { it.write(v) }.toByteArray()
}
