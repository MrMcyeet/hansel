package dev.hansel.tiles

import dev.hansel.model.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class ExtractPhase { RESOLVING, DOWNLOADING, ASSEMBLING, DONE }

data class ExtractProgress(
    val phase: ExtractPhase,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val batchesDone: Int = 0,
    val batchesTotal: Int = 0,
    val tilesDone: Long = 0,
    val tilesTotal: Long = 0,
) {
    val fraction: Double get() = when (phase) {
        ExtractPhase.RESOLVING -> if (tilesTotal == 0L) 0.0 else 0.05 * tilesDone / tilesTotal
        ExtractPhase.DOWNLOADING -> 0.05 + if (bytesTotal == 0L) 0.0 else 0.85 * bytesDone / bytesTotal
        ExtractPhase.ASSEMBLING -> 0.90 + if (tilesTotal == 0L) 0.0 else 0.10 * tilesDone / tilesTotal
        ExtractPhase.DONE -> 1.0
    }
}

/** A contiguous byte range of the source archive to download in one request. */
data class ExtractBatch(val sourceOffset: Long, val length: Long, val scratchOffset: Long) {
    val end: Long get() = sourceOffset + length
}

/**
 * Everything the extractor needs, computed from the source directories only (no tile bytes fetched yet).
 * Arrays are parallel and sorted by tile id; only tiles present in the source are listed.
 */
class ExtractPlan(
    val sourceUrl: String,
    val sourceSize: Long?,
    val sourceEtag: String?,
    val sourceHeader: PmTiles.Header,
    val metadataJson: String,
    val bbox: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
    val tileIds: LongArray,
    val tileOffsets: LongArray,
    val tileLengths: IntArray,
    val batches: List<ExtractBatch>,
    /** Tiles addressed by the bbox/zoom range, including ones missing in the source. */
    val addressedTiles: Long,
) {
    val presentTiles: Int get() = tileIds.size

    /** Bytes that will actually be downloaded (distinct blobs plus coalescing gaps). */
    val downloadBytes: Long = batches.sumOf { it.length }

    /** Sum of the distinct tile blobs, i.e. the approximate size of the output tile section. */
    val uniqueTileBytes: Long = run {
        var sum = 0L
        var lastOff = -1L
        val order = (0 until tileIds.size).sortedBy { tileOffsets[it] }
        for (i in order) if (tileOffsets[i] != lastOff) { sum += tileLengths[i]; lastOff = tileOffsets[i] }
        sum
    }

    /** Scratch-file position of tile [i]. */
    fun scratchOffsetOf(i: Int): Long {
        val off = tileOffsets[i]
        var lo = 0; var hi = batches.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (batches[mid].sourceOffset <= off) lo = mid else hi = mid - 1
        }
        val b = batches[lo]
        check(off >= b.sourceOffset && off + tileLengths[i] <= b.end) { "tile $i not covered by any batch" }
        return b.scratchOffset + (off - b.sourceOffset)
    }
}

data class ExtractEstimate(
    val addressedTiles: Long,
    val presentTiles: Int,
    val uniqueTileBytes: Long,
    val downloadBytes: Long,
    val batches: Int,
)

/**
 * Extracts a bounding box from a remote PMTiles archive into a local PMTiles file using HTTP range requests,
 * the way `pmtiles extract` does:
 *
 * 1. fetch header + root directory (first 16 KiB), metadata and the leaf directories the bbox touches;
 * 2. resolve every needed tile id to `(offset, length)`, coalesce into byte-range batches;
 * 3. download batches (retrying, concurrently) into a scratch file next to the output, recording finished
 *    batch indexes in a `.state` JSON so an interrupted extraction resumes;
 * 4. assemble the output with [PmTilesWriter] (de-duplicated, run-length encoded, source metadata copied,
 *    tile compression left untouched) and remove the scratch/state files.
 */
object PmTilesExtractor {
    /** Maximum bytes per ranged GET. */
    const val DEFAULT_BATCH_BYTES = 16L * 1024 * 1024

    /** Two needed ranges separated by at most this many unneeded bytes are fetched in one request. */
    const val DEFAULT_MAX_GAP_BYTES = 512L * 1024

    const val DEFAULT_CONCURRENCY = 3

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun plan(
        sourceUrl: String,
        bbox: BoundingBox,
        maxZoom: Int,
        client: OkHttpClient,
        batchBytes: Long = DEFAULT_BATCH_BYTES,
        maxGapBytes: Long = DEFAULT_MAX_GAP_BYTES,
        progress: (ExtractProgress) -> Unit = {},
    ): ExtractPlan {
        val source = HttpRangeSource(sourceUrl, client)
        return PmTilesReader(source).use { reader -> plan(reader, sourceUrl, bbox, maxZoom, batchBytes, maxGapBytes, progress) }
    }

    suspend fun plan(
        reader: PmTilesReader,
        sourceUrl: String,
        bbox: BoundingBox,
        maxZoom: Int,
        batchBytes: Long = DEFAULT_BATCH_BYTES,
        maxGapBytes: Long = DEFAULT_MAX_GAP_BYTES,
        progress: (ExtractProgress) -> Unit = {},
    ): ExtractPlan {
        val header = reader.header()
        check(header.tileType == PmTiles.TileType.MVT) { "source tile type is ${header.tileType}, expected MVT" }
        check(header.internalCompression == PmTiles.Compression.GZIP || header.internalCompression == PmTiles.Compression.NONE) {
            "unsupported internal compression ${header.internalCompression}"
        }
        check(header.tileCompression == PmTiles.Compression.GZIP || header.tileCompression == PmTiles.Compression.NONE) {
            "unsupported tile compression ${header.tileCompression}"
        }
        val minZ = header.minZoom
        val maxZ = minOf(maxZoom, header.maxZoom)
        require(maxZ >= minZ) { "maxZoom $maxZoom below source minZoom $minZ" }
        val metadata = reader.metadata()

        val wanted = TileMath.tileIdsCovering(bbox, minZ, maxZ)
        val ids = LongArray(wanted.size)
        val offs = LongArray(wanted.size)
        val lens = IntArray(wanted.size)
        var n = 0
        progress(ExtractProgress(ExtractPhase.RESOLVING, tilesTotal = wanted.size.toLong()))
        for ((i, id) in wanted.withIndex()) {
            val e = reader.findEntry(id)
            if (e != null) { ids[n] = id; offs[n] = e.offset; lens[n] = e.length; n++ }
            if (i and 0xffff == 0xffff) progress(ExtractProgress(ExtractPhase.RESOLVING, tilesDone = i.toLong() + 1, tilesTotal = wanted.size.toLong()))
        }
        check(n > 0) { "no tiles of the requested area exist in the source" }
        val batches = coalesce(offs.copyOf(n), lens.copyOf(n), batchBytes, maxGapBytes)
        val src = reader.source
        val (size, etag) = if (src is HttpRangeSource) src.totalSize to src.etag else src.size() to null
        progress(ExtractProgress(ExtractPhase.RESOLVING, tilesDone = wanted.size.toLong(), tilesTotal = wanted.size.toLong()))
        return ExtractPlan(
            sourceUrl = sourceUrl, sourceSize = size, sourceEtag = etag, sourceHeader = header, metadataJson = metadata,
            bbox = bbox, minZoom = minZ, maxZoom = maxZ,
            tileIds = ids.copyOf(n), tileOffsets = offs.copyOf(n), tileLengths = lens.copyOf(n),
            batches = batches, addressedTiles = wanted.size.toLong(),
        )
    }

    /** Resolve directories only and report sizes, so the UI can show "about N MB" before downloading. */
    suspend fun estimate(sourceUrl: String, bbox: BoundingBox, maxZoom: Int, client: OkHttpClient): ExtractEstimate =
        plan(sourceUrl, bbox, maxZoom, client).let {
            ExtractEstimate(it.addressedTiles, it.presentTiles, it.uniqueTileBytes, it.downloadBytes, it.batches.size)
        }

    /** Bytes that will be downloaded for this extraction. */
    suspend fun estimateBytes(sourceUrl: String, bbox: BoundingBox, maxZoom: Int, client: OkHttpClient): Long =
        plan(sourceUrl, bbox, maxZoom, client).downloadBytes

    suspend fun extract(
        sourceUrl: String,
        bbox: BoundingBox,
        maxZoom: Int,
        output: File,
        client: OkHttpClient,
        progress: (ExtractProgress) -> Unit = {},
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): PmTiles.Header {
        val plan = plan(sourceUrl, bbox, maxZoom, client, progress = progress)
        return extract(plan, output, client, progress, concurrency)
    }

    suspend fun extract(
        plan: ExtractPlan,
        output: File,
        client: OkHttpClient,
        progress: (ExtractProgress) -> Unit = {},
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): PmTiles.Header {
        val scratch = scratchFile(output)
        val stateFile = stateFile(output)
        output.parentFile?.mkdirs()

        val identity = ExtractState.identityOf(plan)
        val previous = runCatching { stateFile.takeIf { it.exists() }?.let { json.decodeFromString<ExtractState>(it.readText()) } }.getOrNull()
        val resumable = previous != null && previous.sameIdentity(identity) && scratch.exists()
        val completed = if (resumable) java.util.TreeSet(previous!!.completed) else java.util.TreeSet<Int>()
        if (!resumable) {
            scratch.delete(); stateFile.delete()
        }

        download(plan, scratch, stateFile, identity, completed, client, concurrency, progress)
        val header = assemble(plan, scratch, output, progress)
        scratch.delete()
        stateFile.delete()
        progress(ExtractProgress(ExtractPhase.DONE, bytesDone = plan.downloadBytes, bytesTotal = plan.downloadBytes,
            batchesDone = plan.batches.size, batchesTotal = plan.batches.size, tilesDone = plan.presentTiles.toLong(), tilesTotal = plan.presentTiles.toLong()))
        return header
    }

    fun scratchFile(output: File) = File(output.path + ".tiles")
    fun stateFile(output: File) = File(output.path + ".state")

    private suspend fun download(
        plan: ExtractPlan,
        scratch: File,
        stateFile: File,
        identity: ExtractState,
        completed: java.util.TreeSet<Int>,
        client: OkHttpClient,
        concurrency: Int,
        progress: (ExtractProgress) -> Unit,
    ) {
        val total = plan.downloadBytes
        val batches = plan.batches
        var bytesDone = batches.indices.filter { it in completed }.sumOf { batches[it].length }
        val stateMutex = Mutex()
        progress(ExtractProgress(ExtractPhase.DOWNLOADING, bytesDone, total, completed.size, batches.size))
        if (completed.size == batches.size) return

        val source = HttpRangeSource(plan.sourceUrl, client)
        withContext(Dispatchers.IO) { RandomAccessFile(scratch, "rw") }.use { raf ->
            coroutineScope {
                val work = Channel<Int>(Channel.UNLIMITED)
                for (i in batches.indices) if (i !in completed) work.send(i)
                work.close()
                repeat(concurrency.coerceAtLeast(1)) {
                    launch {
                        for (i in work) {
                            val b = batches[i]
                            val bytes = source.read(b.sourceOffset, b.length.toInt())
                            if (bytes.size.toLong() != b.length) throw IOException("short read for batch $i: got ${bytes.size}, want ${b.length}")
                            withContext(Dispatchers.IO) {
                                synchronized(raf) { raf.seek(b.scratchOffset); raf.write(bytes) }
                            }
                            stateMutex.withLock {
                                completed += i
                                bytesDone += b.length
                                writeState(stateFile, identity.copy(completed = completed.toList()))
                                progress(ExtractProgress(ExtractPhase.DOWNLOADING, bytesDone, total, completed.size, batches.size))
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun assemble(plan: ExtractPlan, scratch: File, output: File, progress: (ExtractProgress) -> Unit): PmTiles.Header =
        withContext(Dispatchers.IO) {
            val tmp = File(output.path + ".partial")
            val total = plan.presentTiles.toLong()
            progress(ExtractProgress(ExtractPhase.ASSEMBLING, tilesDone = 0, tilesTotal = total))
            val header = RandomAccessFile(scratch, "r").use { raf ->
                PmTilesWriter(tmp, plan.sourceHeader.tileType, plan.sourceHeader.tileCompression, plan.metadataJson).use { writer ->
                    for (i in 0 until plan.presentTiles) {
                        val buf = ByteArray(plan.tileLengths[i])
                        raf.seek(plan.scratchOffsetOf(i))
                        raf.readFully(buf)
                        writer.addTile(plan.tileIds[i], buf)
                        if (i and 0x3fff == 0x3fff) progress(ExtractProgress(ExtractPhase.ASSEMBLING, tilesDone = i.toLong() + 1, tilesTotal = total))
                    }
                    writer.finish(plan.bbox, plan.minZoom, plan.maxZoom)
                }
            }
            output.delete()
            if (!tmp.renameTo(output)) throw IOException("could not move ${tmp.path} to ${output.path}")
            progress(ExtractProgress(ExtractPhase.ASSEMBLING, tilesDone = total, tilesTotal = total))
            header
        }

    /** Distinct byte ranges sorted by offset, merged when adjacent/overlapping/within [maxGap], capped at [batchBytes]. */
    internal fun coalesce(offsets: LongArray, lengths: IntArray, batchBytes: Long, maxGap: Long): List<ExtractBatch> {
        val n = offsets.size
        val order = (0 until n).sortedBy { offsets[it] }
        val out = ArrayList<ExtractBatch>()
        var start = -1L
        var end = -1L
        var scratchPos = 0L
        fun flush() {
            if (start >= 0) {
                out += ExtractBatch(start, end - start, scratchPos)
                scratchPos += end - start
            }
        }
        for (i in order) {
            val s = offsets[i]
            val e = s + lengths[i]
            if (start < 0) { start = s; end = e; continue }
            val merged = maxOf(end, e) - start
            if (s <= end + maxGap && (merged <= batchBytes || e <= end)) {
                end = maxOf(end, e)
            } else {
                flush(); start = s; end = e
            }
        }
        flush()
        return out
    }

    private fun writeState(stateFile: File, state: ExtractState) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(ExtractState.serializer(), state))
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }
}

/** Resume bookkeeping stored next to the output as `<output>.state`. */
@Serializable
data class ExtractState(
    val sourceUrl: String,
    val sourceSize: Long?,
    val sourceEtag: String?,
    val bbox: List<Double>,
    val minZoom: Int,
    val maxZoom: Int,
    val batchCount: Int,
    val downloadBytes: Long,
    val completed: List<Int> = emptyList(),
) {
    fun sameIdentity(o: ExtractState): Boolean =
        sourceUrl == o.sourceUrl && sourceSize == o.sourceSize && sourceEtag == o.sourceEtag && bbox == o.bbox &&
            minZoom == o.minZoom && maxZoom == o.maxZoom && batchCount == o.batchCount && downloadBytes == o.downloadBytes

    companion object {
        fun identityOf(p: ExtractPlan) = ExtractState(
            p.sourceUrl, p.sourceSize, p.sourceEtag, listOf(p.bbox.south, p.bbox.west, p.bbox.north, p.bbox.east),
            p.minZoom, p.maxZoom, p.batches.size, p.downloadBytes,
        )
    }
}
