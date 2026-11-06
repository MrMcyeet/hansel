package dev.hansel.tiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Random-access byte source: a local file or an HTTP URL supporting `Range` requests. */
interface RangeSource : AutoCloseable {
    /** Read exactly [length] bytes at [offset]. May return fewer bytes only if the source ends. */
    suspend fun read(offset: Long, length: Int): ByteArray

    /** Total size in bytes if known. */
    suspend fun size(): Long?

    override fun close() {}
}

class FileRangeSource(file: File) : RangeSource {
    private val raf = RandomAccessFile(file, "r")

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        synchronized(raf) {
            val available = (raf.length() - offset).coerceAtLeast(0).coerceAtMost(length.toLong()).toInt()
            val out = ByteArray(available)
            raf.seek(offset)
            raf.readFully(out)
            out
        }
    }

    override suspend fun size(): Long = withContext(Dispatchers.IO) { raf.length() }
    override fun close() = raf.close()
}

/**
 * HTTP range reader. Every request is `GET` with `Range: bytes=a-b`; non-206 answers are treated as errors
 * (a 200 would mean the server ignored the range, which for a 139 GB planet file must never be streamed).
 * Retries [maxAttempts] times with exponential backoff on I/O errors and 5xx/429.
 */
class HttpRangeSource(
    val url: String,
    private val client: OkHttpClient,
    private val maxAttempts: Int = 3,
    private val backoffMillis: Long = 1_000,
) : RangeSource {
    @Volatile var etag: String? = null; private set
    @Volatile var totalSize: Long? = null; private set
    @Volatile var acceptsRanges: Boolean? = null; private set

    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(length > 0)
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < maxAttempts) {
            attempt++
            try {
                return withContext(Dispatchers.IO) { readOnce(offset, length) }
            } catch (e: RetryableHttpException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) delay(backoffMillis shl (attempt - 1))
        }
        throw IOException("range request failed after $maxAttempts attempts: $url bytes=$offset-${offset + length - 1}", lastError)
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        val end = offset + length - 1
        val req = Request.Builder().url(url).header("Range", "bytes=$offset-$end").build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 206 -> Unit
                resp.code == 416 -> return ByteArray(0)
                resp.code == 429 || resp.code >= 500 -> throw RetryableHttpException(resp.code, url)
                resp.code == 200 -> throw IOException("server ignored Range header (HTTP 200) for $url")
                else -> throw IOException("HTTP ${resp.code} for $url bytes=$offset-$end")
            }
            resp.header("ETag")?.let { etag = it }
            resp.header("Accept-Ranges")?.let { acceptsRanges = it.equals("bytes", ignoreCase = true) }
            resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { totalSize = it }
            val body = resp.body ?: throw IOException("empty body")
            val bytes = body.bytes()
            if (bytes.size > length) throw IOException("server returned ${bytes.size} bytes for a $length-byte range")
            return bytes
        }
    }

    override suspend fun size(): Long? {
        totalSize?.let { return it }
        read(0, 1)
        return totalSize
    }

    class RetryableHttpException(val code: Int, url: String) : IOException("HTTP $code for $url")
}

/**
 * Reads a PMTiles v3 archive through a [RangeSource]. Caches the header, root directory and recently used
 * leaf directories. Safe for concurrent use.
 */
class PmTilesReader(val source: RangeSource, private val leafCacheSize: Int = 64) : AutoCloseable {
    private val mutex = Mutex()
    private var header: PmTiles.Header? = null
    private var root: List<PmTiles.Entry>? = null
    private var headerBytes: ByteArray? = null
    private val leafCache = object : LinkedHashMap<Long, List<PmTiles.Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PmTiles.Entry>>?): Boolean = size > leafCacheSize
    }

    /** Header and root directory in one 16 KiB read (the spec guarantees both fit there). */
    suspend fun header(): PmTiles.Header {
        header?.let { return it }
        return mutex.withLock {
            header ?: run {
                val bytes = source.read(0, PmTiles.ROOT_WINDOW_BYTES)
                val h = PmTiles.Header.parse(bytes)
                headerBytes = bytes
                header = h
                h
            }
        }
    }

    suspend fun rootDirectory(): List<PmTiles.Entry> {
        root?.let { return it }
        val h = header()
        return mutex.withLock {
            root ?: run {
                val cached = headerBytes
                val raw = if (cached != null && h.rootDirOffset + h.rootDirLength <= cached.size) {
                    cached.copyOfRange(h.rootDirOffset.toInt(), (h.rootDirOffset + h.rootDirLength).toInt())
                } else {
                    source.read(h.rootDirOffset, h.rootDirLength.toInt())
                }
                PmTiles.decodeDirectory(PmTiles.decompress(raw, h.internalCompression)).also { root = it }
            }
        }
    }

    /** Decompressed JSON metadata. */
    suspend fun metadata(): String {
        val h = header()
        if (h.metadataLength == 0L) return "{}"
        val raw = source.read(h.metadataOffset, h.metadataLength.toInt())
        return String(PmTiles.decompress(raw, h.internalCompression), Charsets.UTF_8)
    }

    suspend fun leafDirectory(offsetInLeafSection: Long, length: Int): List<PmTiles.Entry> {
        mutex.withLock { leafCache[offsetInLeafSection] }?.let { return it }
        val h = header()
        val raw = source.read(h.leafDirsOffset + offsetInLeafSection, length)
        val entries = PmTiles.decodeDirectory(PmTiles.decompress(raw, h.internalCompression))
        mutex.withLock { leafCache[offsetInLeafSection] = entries }
        return entries
    }

    /** Resolve [tileId] to its tile entry (runLength > 0) or null if absent. */
    suspend fun findEntry(tileId: Long): PmTiles.Entry? {
        var dir = rootDirectory()
        repeat(PmTiles.MAX_DIRECTORY_DEPTH) {
            val e = PmTiles.findTile(dir, tileId) ?: return null
            if (!e.isLeaf) return e
            dir = leafDirectory(e.offset, e.length)
        }
        throw IllegalStateException("directory nesting deeper than ${PmTiles.MAX_DIRECTORY_DEPTH}")
    }

    /** Raw (still tile-compressed) tile bytes, or null if the tile is absent. */
    suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val e = findEntry(PmTiles.zxyToTileId(z, x, y)) ?: return null
        val h = header()
        return source.read(h.tileDataOffset + e.offset, e.length)
    }

    override fun close() = source.close()
}
