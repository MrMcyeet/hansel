package dev.hansel.tiles

import dev.hansel.model.BoundingBox
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Streaming PMTiles v3 writer.
 *
 * Tiles are appended to the output file as they arrive ([addTile], strictly increasing tile ids); only the
 * directory entries (a few primitive arrays) and a content-hash table for de-duplication stay in memory.
 *
 * Layout written (sections other than the header may be relocated per spec section 2; the Protomaps planet
 * builds use this same order so the tile section can start at a fixed offset without a second pass):
 *
 * ```
 * [0, 127)           header
 * [127, 16384)       root directory (gzip), zero padded
 * [16384, ...)       tile data
 * then               metadata JSON (gzip)
 * then               leaf directories (gzip, each separately)
 * ```
 *
 * De-duplication: identical tile contents share one blob. A 64-bit content hash is used for the lookup and a
 * hit is confirmed by reading the candidate blob back and comparing bytes, so collisions cannot corrupt the
 * archive. Consecutive tile ids sharing a blob collapse into a run-length entry.
 */
class PmTilesWriter(
    private val output: File,
    private val tileType: PmTiles.TileType,
    private val tileCompression: PmTiles.Compression,
    private val metadataJson: String,
    private val internalCompression: PmTiles.Compression = PmTiles.Compression.GZIP,
    /** Entries per leaf directory to start with when the root does not fit; doubled until it does. */
    private val initialLeafSize: Int = 4096,
    /** Testing hook: maximum compressed root directory size. Defaults to the spec's 16 257 bytes. */
    private val maxRootBytes: Int = PmTiles.MAX_ROOT_BYTES,
) : AutoCloseable {
    private val raf = RandomAccessFile(output, "rw")
    private val tileDataStart: Long = PmTiles.ROOT_WINDOW_BYTES.toLong()
    private var writePos: Long = tileDataStart

    // directory entries (parallel arrays)
    private var ids = LongArray(1024)
    private var offsets = LongArray(1024)
    private var lengths = IntArray(1024)
    private var runs = IntArray(1024)
    private var count = 0

    private var lastTileId = -1L
    private var addressed = 0L
    private var contents = 0L
    private val dedupe = LongLongMap()
    private val md5 = MessageDigest.getInstance("MD5")
    private var finished = false

    init {
        raf.setLength(0)
        raf.setLength(tileDataStart)
        writePos = tileDataStart
    }

    val tileCount: Long get() = addressed
    val entryCount: Int get() = count
    val bytesWritten: Long get() = writePos - tileDataStart

    /** Append a tile. [tileId] must be greater than the previous one; empty tiles are skipped (length must be > 0). */
    fun addTile(tileId: Long, bytes: ByteArray) {
        check(!finished) { "writer already finished" }
        require(tileId > lastTileId) { "tile ids must be strictly increasing ($tileId after $lastTileId)" }
        if (bytes.isEmpty()) { lastTileId = tileId; return }
        lastTileId = tileId
        addressed++

        val hash = hash64(bytes)
        var offset = -1L
        val existing = dedupe.get(hash)
        if (existing != LongLongMap.MISSING && sameBytes(existing, bytes)) offset = existing
        if (offset < 0) {
            offset = writePos - tileDataStart
            raf.seek(writePos)
            raf.write(bytes)
            writePos += bytes.size
            contents++
            if (existing == LongLongMap.MISSING) dedupe.put(hash, offset)
        }

        // run-length collapse: consecutive id with identical blob
        if (count > 0 && ids[count - 1] + runs[count - 1] == tileId && offsets[count - 1] == offset && lengths[count - 1] == bytes.size) {
            runs[count - 1]++
            return
        }
        ensureCapacity()
        ids[count] = tileId; offsets[count] = offset; lengths[count] = bytes.size; runs[count] = 1
        count++
    }

    /** Write directories, metadata and header. Returns the final header. */
    fun finish(bounds: BoundingBox, minZoom: Int, maxZoom: Int, center: dev.hansel.model.LatLng? = null, centerZoom: Int? = null): PmTiles.Header {
        check(!finished) { "writer already finished" }
        check(count > 0) { "no tiles written" }
        finished = true

        val tileDataLength = writePos - tileDataStart
        val entries = List(count) { PmTiles.Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        val (rootBytes, leafBlobs) = buildDirectories(entries)

        // metadata after tile data, leaves after metadata
        val metadataBytes = PmTiles.compress(metadataJson.toByteArray(Charsets.UTF_8), internalCompression)
        val metadataOffset = writePos
        raf.seek(metadataOffset)
        raf.write(metadataBytes)
        val leafOffset = metadataOffset + metadataBytes.size
        raf.seek(leafOffset)
        var leafLen = 0L
        for (b in leafBlobs) { raf.write(b); leafLen += b.size }

        val header = PmTiles.Header(
            rootDirOffset = PmTiles.HEADER_SIZE.toLong(),
            rootDirLength = rootBytes.size.toLong(),
            metadataOffset = metadataOffset,
            metadataLength = metadataBytes.size.toLong(),
            leafDirsOffset = leafOffset,
            leafDirsLength = leafLen,
            tileDataOffset = tileDataStart,
            tileDataLength = tileDataLength,
            addressedTiles = addressed,
            tileEntries = count.toLong(),
            tileContents = contents,
            clustered = true,
            internalCompression = internalCompression,
            tileCompression = tileCompression,
            tileType = tileType,
            minZoom = minZoom,
            maxZoom = maxZoom,
            minLon = bounds.west, minLat = bounds.south, maxLon = bounds.east, maxLat = bounds.north,
            centerZoom = centerZoom ?: minZoom,
            centerLon = center?.lon ?: bounds.center.lon,
            centerLat = center?.lat ?: bounds.center.lat,
        )
        raf.seek(0)
        raf.write(header.serialize())
        raf.write(rootBytes)
        raf.fd.sync()
        raf.close()
        return header
    }

    override fun close() {
        if (!finished) raf.close()
    }

    /**
     * Root-first strategy, like the reference `pmtiles` CLI: if all entries compress under the root limit, no
     * leaves; otherwise split into leaves of N entries (N doubling until the root of leaf pointers fits).
     */
    private fun buildDirectories(entries: List<PmTiles.Entry>): Pair<ByteArray, List<ByteArray>> {
        val direct = PmTiles.compress(PmTiles.encodeDirectory(entries), internalCompression)
        if (direct.size <= maxRootBytes) return direct to emptyList()
        var leafSize = initialLeafSize.coerceAtLeast(1)
        while (true) {
            val leaves = ArrayList<ByteArray>()
            val rootEntries = ArrayList<PmTiles.Entry>()
            var leafOffset = 0L
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, minOf(i + leafSize, entries.size))
                val blob = PmTiles.compress(PmTiles.encodeDirectory(chunk), internalCompression)
                rootEntries += PmTiles.Entry(chunk.first().tileId, leafOffset, blob.size, 0)
                leaves += blob
                leafOffset += blob.size
                i += leafSize
            }
            val root = PmTiles.compress(PmTiles.encodeDirectory(rootEntries), internalCompression)
            if (root.size <= maxRootBytes) return root to leaves
            check(leafSize < entries.size) { "cannot fit root directory within $maxRootBytes bytes" }
            leafSize *= 2
        }
    }

    private fun sameBytes(offset: Long, bytes: ByteArray): Boolean {
        val buf = ByteArray(bytes.size)
        raf.seek(tileDataStart + offset)
        raf.readFully(buf)
        return buf.contentEquals(bytes)
    }

    private fun hash64(bytes: ByteArray): Long {
        md5.reset()
        val d = md5.digest(bytes)
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xff)
        return h
    }

    private fun ensureCapacity() {
        if (count < ids.size) return
        val n = ids.size * 2
        ids = ids.copyOf(n); offsets = offsets.copyOf(n); lengths = lengths.copyOf(n); runs = runs.copyOf(n)
    }
}

/** Minimal open-addressing long -> long map (keys are 64-bit hashes; `MISSING` is the empty value). */
internal class LongLongMap(initialCapacity: Int = 1 shl 12) {
    private var keys = LongArray(initialCapacity)
    private var values = LongArray(initialCapacity) { MISSING }
    private var size = 0

    fun get(key: Long): Long {
        var i = index(key)
        while (values[i] != MISSING) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and (keys.size - 1)
        }
        return MISSING
    }

    fun put(key: Long, value: Long) {
        if (size * 2 >= keys.size) grow()
        var i = index(key)
        while (values[i] != MISSING) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and (keys.size - 1)
        }
        keys[i] = key; values[i] = value; size++
    }

    private fun index(key: Long): Int {
        val h = key * -0x61c8864680b583ebL
        return (h ushr 32).toInt() and (keys.size - 1)
    }

    private fun grow() {
        val oldK = keys; val oldV = values
        keys = LongArray(oldK.size * 2); values = LongArray(oldK.size * 2) { MISSING }; size = 0
        for (i in oldK.indices) if (oldV[i] != MISSING) put(oldK[i], oldV[i])
    }

    companion object { const val MISSING = -1L }
}
