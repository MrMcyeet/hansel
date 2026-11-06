package dev.hansel.tiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PMTiles v3 primitives: header layout, directory codec, Hilbert tile ids and entry lookup.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 *
 * Everything here is pure (no I/O); see [PmTilesReader] and [PmTilesWriter] for the archive-level API.
 */
object PmTiles {
    const val HEADER_SIZE = 127
    const val MAGIC = "PMTiles"
    const val VERSION = 3

    /** Header + compressed root directory must fit in the first 16 KiB of the archive. */
    const val ROOT_WINDOW_BYTES = 16_384
    const val MAX_ROOT_BYTES = ROOT_WINDOW_BYTES - HEADER_SIZE

    /** Leaf descent limit used by [PmTilesReader]; the spec discourages more than one leaf level. */
    const val MAX_DIRECTORY_DEPTH = 3

    /** Degrees are stored as int32 E7 (value * 10_000_000). */
    const val E7 = 10_000_000.0

    // ---------------------------------------------------------------- Hilbert tile ids

    /**
     * Cumulative Hilbert tile id: all tiles of zooms `< z` come first (`(4^z - 1) / 3` of them), then the
     * position of (x, y) on the zoom-z Hilbert curve.
     */
    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom out of range: $z" }
        require(x >= 0 && y >= 0 && x < (1L shl z) && y < (1L shl z)) { "tile x/y outside zoom level bounds: $z/$x/$y" }
        var acc = zoomOffset(z)
        var a = z - 1
        var tx = x.toLong()
        var ty = y.toLong()
        var s = if (z == 0) 0L else 1L shl a
        while (s > 0) {
            val rx = tx and s
            val ry = ty and s
            acc += ((3 * rx) xor ry) shl a
            // rotate
            if (ry == 0L) {
                if (rx != 0L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx; tx = ty; ty = t
            }
            a--
            s = s shr 1
        }
        return acc
    }

    fun tileIdToZxy(id: Long): Zxy {
        require(id >= 0) { "negative tile id" }
        val z = tileIdToZoom(id)
        val acc = zoomOffset(z)
        var t = id - acc
        var x = 0L
        var y = 0L
        val n = 1L shl z
        var s = 1L
        while (s < n) {
            val rx = s and (t / 2)
            val ry = s and (t xor rx)
            if (ry == 0L) {
                if (rx != 0L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x; x = y; y = tmp
            }
            x += rx
            y += ry
            t /= 2
            s = s shl 1
        }
        return Zxy(z, x.toInt(), y.toInt())
    }

    /** Number of tiles in zooms `0 until z`: `(4^z - 1) / 3`. */
    fun zoomOffset(z: Int): Long = ((1L shl (2 * z)) - 1) / 3

    fun tileIdToZoom(id: Long): Int {
        val c = 3 * id + 1
        return (63 - java.lang.Long.numberOfLeadingZeros(c)) shr 1
    }

    data class Zxy(val z: Int, val x: Int, val y: Int)

    // ---------------------------------------------------------------- enums

    enum class Compression(val code: Int) {
        UNKNOWN(0), NONE(1), GZIP(2), BROTLI(3), ZSTD(4);

        companion object {
            fun of(code: Int): Compression = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    enum class TileType(val code: Int) {
        UNKNOWN(0), MVT(1), PNG(2), JPEG(3), WEBP(4), AVIF(5), MLT(6);

        companion object {
            fun of(code: Int): TileType = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    // ---------------------------------------------------------------- header

    data class Header(
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirsOffset: Long,
        val leafDirsLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val addressedTiles: Long,
        val tileEntries: Long,
        val tileContents: Long,
        val clustered: Boolean,
        val internalCompression: Compression,
        val tileCompression: Compression,
        val tileType: TileType,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
        val centerZoom: Int,
        val centerLon: Double,
        val centerLat: Double,
    ) {
        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
            buf.put(VERSION.toByte())
            buf.putLong(rootDirOffset)
            buf.putLong(rootDirLength)
            buf.putLong(metadataOffset)
            buf.putLong(metadataLength)
            buf.putLong(leafDirsOffset)
            buf.putLong(leafDirsLength)
            buf.putLong(tileDataOffset)
            buf.putLong(tileDataLength)
            buf.putLong(addressedTiles)
            buf.putLong(tileEntries)
            buf.putLong(tileContents)
            buf.put(if (clustered) 1 else 0)
            buf.put(internalCompression.code.toByte())
            buf.put(tileCompression.code.toByte())
            buf.put(tileType.code.toByte())
            buf.put(minZoom.toByte())
            buf.put(maxZoom.toByte())
            buf.putInt(toE7(minLon))
            buf.putInt(toE7(minLat))
            buf.putInt(toE7(maxLon))
            buf.putInt(toE7(maxLat))
            buf.put(centerZoom.toByte())
            buf.putInt(toE7(centerLon))
            buf.putInt(toE7(centerLat))
            check(buf.position() == HEADER_SIZE)
            return buf.array()
        }

        companion object {
            fun parse(bytes: ByteArray): Header {
                require(bytes.size >= HEADER_SIZE) { "header too short: ${bytes.size} bytes" }
                val buf = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(7).also { buf.get(it) }
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "not a PMTiles archive (bad magic)" }
                val version = buf.get().toInt()
                require(version == VERSION) { "unsupported PMTiles version $version (want 3)" }
                return Header(
                    rootDirOffset = buf.long,
                    rootDirLength = buf.long,
                    metadataOffset = buf.long,
                    metadataLength = buf.long,
                    leafDirsOffset = buf.long,
                    leafDirsLength = buf.long,
                    tileDataOffset = buf.long,
                    tileDataLength = buf.long,
                    addressedTiles = buf.long,
                    tileEntries = buf.long,
                    tileContents = buf.long,
                    clustered = buf.get().toInt() == 1,
                    internalCompression = Compression.of(buf.get().toInt() and 0xff),
                    tileCompression = Compression.of(buf.get().toInt() and 0xff),
                    tileType = TileType.of(buf.get().toInt() and 0xff),
                    minZoom = buf.get().toInt() and 0xff,
                    maxZoom = buf.get().toInt() and 0xff,
                    minLon = fromE7(buf.int),
                    minLat = fromE7(buf.int),
                    maxLon = fromE7(buf.int),
                    maxLat = fromE7(buf.int),
                    centerZoom = buf.get().toInt() and 0xff,
                    centerLon = fromE7(buf.int),
                    centerLat = fromE7(buf.int),
                )
            }

            fun toE7(deg: Double): Int = Math.round(deg * E7).toInt()
            fun fromE7(v: Int): Double = v / E7
        }
    }

    // ---------------------------------------------------------------- directories

    /**
     * A directory entry. `runLength > 0`: tiles `tileId until tileId + runLength` all share the bytes at
     * `offset`/`length` inside the tile data section. `runLength == 0`: leaf directory pointer, `offset`
     * relative to the leaf directories section.
     */
    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int) {
        val isLeaf: Boolean get() = runLength == 0
    }

    /** Decode an (already decompressed) directory. */
    fun decodeDirectory(bytes: ByteArray): List<Entry> {
        val r = VarintReader(bytes)
        val n = r.read().toInt()
        require(n > 0) { "directory with zero entries" }
        val ids = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.read(); ids[i] = last }
        val runs = IntArray(n) { r.read().toInt() }
        val lengths = IntArray(n) { r.read().toInt() }
        val offsets = LongArray(n)
        for (i in 0 until n) {
            val v = r.read()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    /** Encode a directory (entries must be sorted by tileId). Not compressed; see [compress]. */
    fun encodeDirectory(entries: List<Entry>): ByteArray {
        require(entries.isNotEmpty()) { "directory with zero entries" }
        val w = VarintWriter(entries.size * 8 + 8)
        w.write(entries.size.toLong())
        var last = 0L
        for (e in entries) {
            require(e.tileId >= last) { "entries not sorted by tileId" }
            w.write(e.tileId - last); last = e.tileId
        }
        for (e in entries) w.write(e.runLength.toLong())
        for (e in entries) { require(e.length > 0) { "entry length must be > 0" }; w.write(e.length.toLong()) }
        for (i in entries.indices) {
            val e = entries[i]
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) w.write(0) else w.write(e.offset + 1)
        }
        return w.toByteArray()
    }

    /**
     * Binary search for [tileId] in a sorted directory. Returns the tile entry whose run covers the id, the
     * leaf entry whose range may contain it, or null.
     */
    fun findTile(entries: List<Entry>, tileId: Long): Entry? {
        var m = 0
        var n = entries.size - 1
        while (m <= n) {
            val k = (n + m) ushr 1
            val cmp = tileId - entries[k].tileId
            when {
                cmp > 0 -> m = k + 1
                cmp < 0 -> n = k - 1
                else -> return entries[k]
            }
        }
        if (n >= 0) {
            val e = entries[n]
            if (e.runLength == 0) return e
            if (tileId - e.tileId < e.runLength) return e
        }
        return null
    }

    // ---------------------------------------------------------------- compression helpers

    fun compress(bytes: ByteArray, c: Compression): ByteArray = when (c) {
        Compression.NONE, Compression.UNKNOWN -> bytes
        Compression.GZIP -> ByteArrayOutputStream(bytes.size / 2 + 16).also { bos ->
            GZIPOutputStream(bos).use { it.write(bytes) }
        }.toByteArray()
        else -> throw UnsupportedOperationException("compression $c not supported")
    }

    fun decompress(bytes: ByteArray, c: Compression): ByteArray = when (c) {
        Compression.NONE, Compression.UNKNOWN -> bytes
        Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw UnsupportedOperationException("compression $c not supported")
    }

    // ---------------------------------------------------------------- varints

    internal class VarintReader(private val buf: ByteArray, var pos: Int = 0) {
        fun read(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < buf.size) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift <= 63) { "varint too long" }
            }
        }
    }

    internal class VarintWriter(initial: Int = 64) {
        private val out = ByteArrayOutputStream(initial)
        fun write(value: Long) {
            require(value >= 0) { "negative varint" }
            var v = value
            while (v >= 0x80) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
