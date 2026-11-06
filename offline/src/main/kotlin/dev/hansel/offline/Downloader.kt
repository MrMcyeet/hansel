package dev.hansel.offline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Resumable, verified download of one Geofabrik extract into [dir] (DESIGN §12 "checksum, resumable").
 *
 * Files: `<id>.osm.pbf.part` while downloading, `<id>.osm.pbf.md5` (the server's sidecar, fetched first), and the
 * final `<id>.osm.pbf`, which only ever appears via an atomic rename after the MD5 matched.
 *
 * Resume: if a `.part` exists we send `Range: bytes=<len>-`; a 206 appends, a 200 (server ignored the range)
 * truncates and restarts. Geofabrik republishes every extract daily, so if the sidecar MD5 changed since the
 * `.part` was started the partial file is discarded rather than stitched onto a different version.
 *
 * On MD5 mismatch the `.part` is kept so a retry can re-check it; a retry that finds a complete-length `.part`
 * that still mismatches restarts from zero instead of looping.
 */
class Downloader(
    private val client: OkHttpClient,
    private val dir: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class Progress(val bytesDone: Long, val bytesTotal: Long, val bytesPerSecond: Double)

    class ChecksumMismatchException(val expected: String, val actual: String, val file: File) :
        IOException("MD5 mismatch for ${file.name}: expected $expected, got $actual")

    class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

    fun pbfFor(regionId: String): File = File(dir, "$regionId.osm.pbf")
    fun partFor(regionId: String): File = File(dir, "$regionId.osm.pbf.part")
    fun md5For(regionId: String): File = File(dir, "$regionId.osm.pbf.md5")

    /** True when the final file exists and matches the stored sidecar MD5. Hashes the file (about 1 s / 100 MB). */
    suspend fun isVerified(region: Region): Boolean = withContext(Dispatchers.IO) {
        val pbf = pbfFor(region.id)
        val sidecar = md5For(region.id)
        if (!pbf.isFile || !sidecar.isFile) return@withContext false
        val expected = parseMd5(sidecar.readText()) ?: return@withContext false
        md5Hex(pbf) == expected
    }

    /**
     * Downloads (or resumes) the region, verifies it and returns the [InstalledRegion] record. Already-verified
     * files are returned without network access beyond the sidecar refresh being skipped entirely.
     */
    suspend fun download(region: Region, onProgress: (Progress) -> Unit = {}): InstalledRegion = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val pbf = pbfFor(region.id)
        val part = partFor(region.id)
        val sidecar = md5For(region.id)

        // Fast path: a previous run finished and the file still matches.
        if (pbf.isFile && sidecar.isFile) {
            val expected = parseMd5(sidecar.readText())
            if (expected != null && md5Hex(pbf) == expected) {
                return@withContext installed(region, pbf, expected)
            }
            // Corrupt or stale final file: rebuild it from scratch.
            pbf.delete()
        }

        val expectedMd5 = fetchSidecar(region)
        val previousMd5 = sidecar.takeIf { it.isFile }?.let { parseMd5(it.readText()) }
        if (part.isFile && previousMd5 != null && previousMd5 != expectedMd5) {
            // Server republished since we started: a resumed .part would be two different versions glued together.
            part.delete()
        }
        sidecar.writeText("$expectedMd5  ${region.id}-latest.osm.pbf\n")

        var restartedAfterMismatch = false
        while (true) {
            val fetched = fetchBody(region, part, onProgress)
            val actual = md5Hex(part)
            if (actual == expectedMd5) {
                if (!part.renameTo(pbf)) throw IOException("Could not rename ${part.name} to ${pbf.name}")
                return@withContext installed(region, pbf, expectedMd5)
            }
            // Mismatch. If nothing was transferred this call, the .part was already complete from an earlier
            // attempt and can never verify: start over once. Otherwise keep it for the caller to retry.
            if (fetched.bytesReceived == 0L && !restartedAfterMismatch) {
                restartedAfterMismatch = true
                part.delete()
                continue
            }
            throw ChecksumMismatchException(expectedMd5, actual, part)
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private class Fetched(val total: Long, val bytesReceived: Long)

    private fun installed(region: Region, pbf: File, md5: String) = InstalledRegion(
        region = region.copy(approxBytes = pbf.length()),
        bytes = pbf.length(),
        downloadedAtMillis = nowMillis(),
        md5 = md5,
        sourceTimestamp = null,
    )

    private suspend fun fetchSidecar(region: Region): String {
        val req = Request.Builder().url(region.url + ".md5").build()
        return execute(req).use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, region.url + ".md5")
            parseMd5(resp.body.string()) ?: throw IOException("Unparseable MD5 sidecar for ${region.id}")
        }
    }

    /** Streams the body into [part], resuming when possible. [Fetched.total] is -1 if the server did not say. */
    private suspend fun fetchBody(region: Region, part: File, onProgress: (Progress) -> Unit): Fetched {
        val existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(region.url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        execute(builder.build()).use { resp ->
            var offset: Long
            var total: Long
            val append: Boolean
            when (resp.code) {
                206 -> {
                    append = true
                    offset = existing
                    total = parseContentRangeTotal(resp.header("Content-Range")) ?: run {
                        val len = resp.body.contentLength()
                        if (len >= 0) existing + len else -1L
                    }
                }
                200 -> {
                    append = false
                    offset = 0
                    total = resp.body.contentLength()
                }
                416 -> {
                    // Range not satisfiable: our .part is at least as long as the file. Verify what we have.
                    return Fetched(parseContentRangeTotal(resp.header("Content-Range")) ?: existing, 0)
                }
                else -> throw HttpException(resp.code, region.url)
            }
            if (append && total in 1..existing) return Fetched(total, 0)
            val startedAt = nowMillis()
            var windowStart = startedAt
            var windowBytes = 0L
            var lastReport = 0L
            var speed = 0.0
            FileOutputStream(part, append).use { out ->
                val source = resp.body.source()
                val buf = ByteArray(256 * 1024)
                while (true) {
                    currentContext().ensureActive()
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    offset += n
                    windowBytes += n
                    val now = nowMillis()
                    if (now - windowStart >= 2_000) {
                        speed = windowBytes * 1000.0 / (now - windowStart)
                        windowStart = now
                        windowBytes = 0
                    }
                    if (now - lastReport >= 250 || total in 1..offset) {
                        lastReport = now
                        onProgress(Progress(offset, if (total > 0) total else -1, speed))
                    }
                }
            }
            onProgress(Progress(offset, if (total > 0) total else offset, speed))
            if (total > 0 && offset != total) throw IOException("Connection closed after $offset of $total bytes for ${region.id}")
            return Fetched(total, offset - (if (append) existing else 0L))
        }
    }

    /** Executes synchronously on the IO dispatcher but cancels the call when the coroutine is cancelled. */
    private suspend fun execute(request: Request): Response {
        val ctx = currentContext()
        val call = client.newCall(request)
        val handle = ctx.job.invokeOnCompletion { if (it != null) call.cancel() }
        return try {
            call.execute()
        } catch (e: IOException) {
            ctx.ensureActive()
            throw e
        } finally {
            handle.dispose()
        }
    }

    private suspend fun currentContext() = kotlin.coroutines.coroutineContext

    companion object {
        /** Geofabrik sidecar format: `<32 hex>  <filename>`; also accepts a bare hash. */
        fun parseMd5(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        /** `bytes 100-999/1000` → 1000; null when absent or `*`. */
        fun parseContentRangeTotal(header: String?): Long? {
            val h = header ?: return null
            val slash = h.lastIndexOf('/')
            if (slash < 0) return null
            return h.substring(slash + 1).trim().toLongOrNull()
        }

        fun md5Hex(file: File): String {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
