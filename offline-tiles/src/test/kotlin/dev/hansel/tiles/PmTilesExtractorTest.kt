package dev.hansel.tiles

import dev.hansel.model.BoundingBox
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmTilesExtractorTest {
    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sourceBytes: ByteArray
    private lateinit var sourceTiles: Map<Long, ByteArray>
    private lateinit var sourceHeader: PmTiles.Header

    /** Serves [sourceBytes] honouring `Range`; optionally fails a given byte range with 500. */
    private inner class RangeDispatcher : Dispatcher() {
        @Volatile var failRangeStart: Long = -1
        val requests = AtomicInteger()
        val rangeRequests = ArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            val range = request.headers["Range"]
            val etagHeaders = mapOf("ETag" to "\"abc\"", "Accept-Ranges" to "bytes")
            if (range == null) {
                return MockResponse.Builder().code(200).body(Buffer().write(sourceBytes)).apply { etagHeaders.forEach { (k, v) -> addHeader(k, v) } }.build()
            }
            synchronized(rangeRequests) { rangeRequests += range }
            val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range) ?: return MockResponse.Builder().code(400).build()
            val start = m.groupValues[1].toLong()
            if (start == failRangeStart) return MockResponse.Builder().code(500).build()
            val end = minOf(m.groupValues[2].toLong(), sourceBytes.size - 1L)
            if (start >= sourceBytes.size) return MockResponse.Builder().code(416).build()
            val slice = sourceBytes.copyOfRange(start.toInt(), (end + 1).toInt())
            return MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $start-$end/${sourceBytes.size}")
                .apply { etagHeaders.forEach { (k, v) -> addHeader(k, v) } }
                .body(Buffer().write(slice)).build()
        }
    }

    private lateinit var dispatcher: RangeDispatcher

    private val metadata = """{"name":"planet-test","vector_layers":[{"id":"roads"}],"attribution":"OSM"}"""

    @BeforeTest fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("pmtiles-extract").toFile()
        // Source: z0..3 (85 tiles). Distinct 1-byte contents except z3 x>=6 (shared blob -> run lengths/dedupe).
        val tiles = sortedMapOf<Long, ByteArray>()
        var next = 1
        for (z in 0..3) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
            val id = PmTiles.zxyToTileId(z, x, y)
            tiles[id] = if (z == 3 && x >= 6) byteArrayOf(0xEE.toByte()) else byteArrayOf((next++).toByte())
        }
        sourceTiles = tiles
        val src = File(dir, "source.pmtiles")
        // tiny root threshold -> leaf directories, like the real planet build
        sourceHeader = PmTilesWriter(src, PmTiles.TileType.MVT, PmTiles.Compression.GZIP, metadata, forceLeafSize = 8).use { w ->
            tiles.forEach { (id, b) -> w.addTile(id, b) }
            w.finish(BoundingBox(-85.0, -180.0, 85.0, 180.0), 0, 3)
        }
        assertTrue(sourceHeader.leafDirsLength > 0, "test source should have leaf directories")
        sourceBytes = src.readBytes()
        dispatcher = RangeDispatcher()
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    }

    @AfterTest fun tearDown() { server.close(); dir.deleteRecursively() }

    private val sourceUrl get() = server.url("/20261105.pmtiles").toString()

    /** Roughly the Mississippi / Louisiana / Alabama corner: a small bbox in the north-west quadrant. */
    private val bbox = BoundingBox(29.0, -94.0, 35.0, -85.0)

    @Test fun `tile enumeration covers bbox and all parents`() {
        val ids = TileMath.tileIdsCovering(bbox, 0, 3)
        assertTrue(ids.toList() == ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        val set = ids.toSet()
        for (id in ids) {
            val (z, x, y) = PmTiles.tileIdToZxy(id)
            if (z > 0) assertTrue(PmTiles.zxyToTileId(z - 1, x / 2, y / 2) in set, "parent of $z/$x/$y missing")
        }
        assertTrue(PmTiles.zxyToTileId(0, 0, 0) in set)
        // bbox lies at lon -94..-85, lat 29..35 -> z1 (0,0); z2 (0,1),(1,1); z3 (1,3),(2,3)
        assertTrue(PmTiles.zxyToTileId(1, 0, 0) in set)
        assertFalse(PmTiles.zxyToTileId(1, 1, 1) in set)
        assertEquals(TileMath.TileRange(2, 0, 1, 1, 1), TileMath.tileRange(bbox, 2))
        assertEquals(TileMath.TileRange(3, 1, 2, 3, 3), TileMath.tileRange(bbox, 3))
        assertEquals(6, ids.size)
    }

    @Test fun `plan resolves through leaf directories and estimates sizes`() = runBlocking {
        val plan = PmTilesExtractor.plan(sourceUrl, bbox, 3, client)
        val expected = TileMath.tileIdsCovering(bbox, 0, 3)
        assertEquals(expected.size.toLong(), plan.addressedTiles)
        assertEquals(expected.size, plan.presentTiles)
        assertContentEquals(expected, plan.tileIds)
        assertEquals(expected.size.toLong(), plan.uniqueTileBytes) // all distinct 1-byte tiles inside this bbox
        assertTrue(plan.downloadBytes >= plan.uniqueTileBytes)
        assertEquals("\"abc\"", plan.sourceEtag)
        assertEquals(sourceBytes.size.toLong(), plan.sourceSize)
        assertEquals(metadata, plan.metadataJson)
        assertEquals(plan.downloadBytes, PmTilesExtractor.estimateBytes(sourceUrl, bbox, 3, client))
        val est = PmTilesExtractor.estimate(sourceUrl, bbox, 3, client)
        assertEquals(plan.batches.size, est.batches)
        // the whole-world bbox dedupes the shared z3 blob and caps zoom to the source's max
        val world = PmTilesExtractor.plan(sourceUrl, BoundingBox(-85.0, -180.0, 85.0, 180.0), 9, client)
        assertEquals(3, world.maxZoom)
        assertEquals(85, world.presentTiles)
        assertEquals(sourceHeader.tileContents, world.uniqueTileBytes)
    }

    @Test fun `extract writes a valid archive with exactly the bbox tiles`() = runBlocking {
        val out = File(dir, "region.pmtiles")
        val progress = ArrayList<ExtractProgress>()
        // small batches + zero gap -> many range requests, exercising coalescing/mapping
        val plan = PmTilesExtractor.plan(sourceUrl, bbox, 3, client, batchBytes = 4, maxGapBytes = 0)
        assertTrue(plan.batches.size > 1)
        val header = PmTilesExtractor.extract(plan, out, client, { progress += it }, concurrency = 2)

        assertEquals(ExtractPhase.DONE, progress.last().phase)
        assertTrue(progress.any { it.phase == ExtractPhase.DOWNLOADING })
        assertFalse(PmTilesExtractor.scratchFile(out).exists())
        assertFalse(PmTilesExtractor.stateFile(out).exists())

        val expected = TileMath.tileIdsCovering(bbox, 0, 3).toSet()
        assertEquals(expected.size.toLong(), header.addressedTiles)
        assertEquals(PmTiles.TileType.MVT, header.tileType)
        assertEquals(PmTiles.Compression.GZIP, header.tileCompression)
        assertEquals(0, header.minZoom); assertEquals(3, header.maxZoom)
        assertEquals(bbox.west, header.minLon); assertEquals(bbox.north, header.maxLat)

        PmTilesReader(FileRangeSource(out)).use { r ->
            assertEquals(header, r.header())
            assertEquals(metadata, r.metadata())
            for ((id, bytes) in sourceTiles) {
                val (z, x, y) = PmTiles.tileIdToZxy(id)
                if (id in expected) assertContentEquals(bytes, r.getTile(z, x, y), "tile $z/$x/$y")
                else assertNull(r.getTile(z, x, y), "tile $z/$x/$y should not be in the extract")
            }
        }
        // every request was a range request (the planet file must never be streamed whole)
        assertEquals(dispatcher.requests.get(), dispatcher.rangeRequests.size)
    }

    @Test fun `extract uses default batching end to end`() = runBlocking {
        val out = File(dir, "region2.pmtiles")
        val header = PmTilesExtractor.extract(sourceUrl, bbox, 2, out, client)
        assertEquals(2, header.maxZoom)
        PmTilesReader(FileRangeSource(out)).use { r ->
            assertNotNull(r.getTile(2, 0, 1))
            assertNull(r.getTile(3, 1, 2))
        }
    }

    @Test fun `resume continues after a failed batch`() = runBlocking {
        val out = File(dir, "resume.pmtiles")
        val plan = PmTilesExtractor.plan(sourceUrl, bbox, 3, client, batchBytes = 4, maxGapBytes = 0)
        assertTrue(plan.batches.size >= 3)
        val failing = plan.batches[plan.batches.size / 2]
        dispatcher.failRangeStart = failing.sourceOffset

        // first attempt: batch N answers 500 (3 retries) -> extraction fails, state persists
        assertFailsWith<IOException> { PmTilesExtractor.extract(plan, out, client, concurrency = 1) }
        val state = PmTilesExtractor.stateFile(out)
        assertTrue(state.exists() && PmTilesExtractor.scratchFile(out).exists())
        val completedBefore = Regex("\"completed\":\\[([^\\]]*)\\]").find(state.readText())!!.groupValues[1].split(',').filter { it.isNotBlank() }.size
        assertTrue(completedBefore in 1 until plan.batches.size)
        assertFalse(out.exists())

        // second attempt: server healthy; only the missing batches are fetched
        dispatcher.failRangeStart = -1
        val before = dispatcher.rangeRequests.size
        val header = PmTilesExtractor.extract(plan, out, client, concurrency = 1)
        val fetched = dispatcher.rangeRequests.size - before
        assertEquals(plan.batches.size - completedBefore, fetched)
        assertFalse(state.exists())

        val expected = TileMath.tileIdsCovering(bbox, 0, 3).toSet()
        assertEquals(expected.size.toLong(), header.addressedTiles)
        PmTilesReader(FileRangeSource(out)).use { r ->
            for (id in expected) {
                val (z, x, y) = PmTiles.tileIdToZxy(id)
                assertContentEquals(sourceTiles.getValue(id), r.getTile(z, x, y), "tile $z/$x/$y")
            }
        }
    }

    @Test fun `stale state is discarded when the plan differs`() = runBlocking {
        val out = File(dir, "stale.pmtiles")
        PmTilesExtractor.stateFile(out).writeText("""{"sourceUrl":"x","sourceSize":1,"sourceEtag":null,"bbox":[0,0,0,0],"minZoom":0,"maxZoom":1,"batchCount":1,"downloadBytes":1,"completed":[0]}""")
        PmTilesExtractor.scratchFile(out).writeBytes(ByteArray(10))
        val plan = PmTilesExtractor.plan(sourceUrl, bbox, 1, client)
        val before = dispatcher.rangeRequests.size
        PmTilesExtractor.extract(plan, out, client)
        assertEquals(plan.batches.size, dispatcher.rangeRequests.size - before)
    }

    @Test fun `coalesce merges adjacent and gapped ranges within limits`() {
        val offs = longArrayOf(0, 10, 10, 25, 100, 120)
        val lens = intArrayOf(10, 5, 5, 5, 10, 10)
        val b0 = PmTilesExtractor.coalesce(offs, lens, batchBytes = 1000, maxGap = 0)
        assertEquals(listOf(ExtractBatch(0, 15, 0), ExtractBatch(25, 5, 15), ExtractBatch(100, 10, 20), ExtractBatch(120, 10, 30)), b0)
        val b1 = PmTilesExtractor.coalesce(offs, lens, batchBytes = 1000, maxGap = 20)
        assertEquals(listOf(ExtractBatch(0, 30, 0), ExtractBatch(100, 30, 30)), b1)
        val b2 = PmTilesExtractor.coalesce(offs, lens, batchBytes = 12, maxGap = 100)
        assertEquals(listOf(ExtractBatch(0, 10, 0), ExtractBatch(10, 5, 10), ExtractBatch(25, 5, 15), ExtractBatch(100, 10, 20), ExtractBatch(120, 10, 30)), b2)
    }

    @Test fun `http range source rejects non-range answers and retries 5xx`() = runBlocking {
        val src = HttpRangeSource(sourceUrl, client, maxAttempts = 2, backoffMillis = 1)
        assertContentEquals(sourceBytes.copyOfRange(0, 7), src.read(0, 7))
        assertEquals(sourceBytes.size.toLong(), src.size())
        dispatcher.failRangeStart = 100
        assertFailsWith<IOException> { src.read(100, 10) }
        dispatcher.failRangeStart = -1
        assertContentEquals(sourceBytes.copyOfRange(100, 110), src.read(100, 10))
    }
}
