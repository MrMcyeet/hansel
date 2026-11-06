package dev.hansel.osm

import dev.hansel.model.BoundingBox
import org.openstreetmap.osmosis.osmbinary.Fileformat
import org.openstreetmap.osmosis.osmbinary.Osmformat
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The `OSMHeader` block of a PBF. */
data class PbfHeader(
    val bbox: BoundingBox?,
    val requiredFeatures: List<String>,
    val optionalFeatures: List<String>,
    val writingProgram: String?,
    val source: String?,
    /** `osmosis_replication_timestamp`, epoch seconds; the age of the extract. */
    val replicationTimestamp: Long?,
) {
    companion object {
        val SUPPORTED_FEATURES = setOf("OsmSchema-V0.6", "DenseNodes")
    }
}

/**
 * Streaming reader for `.osm.pbf` files (https://wiki.openstreetmap.org/wiki/PBF_Format).
 *
 * One block (<= 8k entities) is decoded at a time; the file is otherwise never held in memory, so this is safe for
 * Geofabrik state extracts with tens of millions of nodes. Decoding uses the osmosis protobuf classes
 * ([Osmformat]/[Fileformat]) for the message layer; blob framing, zlib, granularity/offset scaling, delta decoding
 * and the DenseNodes `keys_vals` layout are handled here.
 *
 * Not thread-safe. [entities] may be consumed once.
 */
class PbfReader private constructor(
    private val counting: CountingInputStream,
    private val input: DataInputStream,
) : Closeable {

    /** The `OSMHeader` block; parsed eagerly by [open]. */
    lateinit var header: PbfHeader
        private set

    /** Compressed bytes consumed so far; for progress reporting. */
    val bytesRead: Long get() = counting.count

    private val inflater = Inflater()

    /** All entities in file order. For a `Sort.Type_then_ID` file that is nodes, ways, relations, each ascending by id. */
    fun entities(): Sequence<OsmEntity> = sequence {
        while (true) {
            val block = nextPrimitiveBlock() ?: break
            yieldAll(decodeBlock(block))
        }
    }

    /**
     * Decodes the next `OSMData` block, or null at EOF. Exposed so callers that only want nodes (e.g. the search
     * index's coordinate pass) can stop without decoding the ways/relations that follow.
     */
    fun nextPrimitiveBlock(): Osmformat.PrimitiveBlock? {
        while (true) {
            val (type, data) = readRawBlock() ?: return null
            when (type) {
                "OSMData" -> return Osmformat.PrimitiveBlock.parseFrom(data)
                "OSMHeader" -> {
                    // Tolerate a stray second header (some tools emit one per concatenated file); nothing in it is new.
                    continue
                }
                else -> continue // spec: skip unknown block types
            }
        }
    }

    /** Reads one blob: returns (BlobHeader.type, uncompressed payload) or null at clean EOF. */
    private fun readRawBlock(): Pair<String, ByteArray>? {
        val headerLen = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        require(headerLen in 1..MAX_BLOB_HEADER) { "BlobHeader length $headerLen out of range" }
        val headerBytes = ByteArray(headerLen).also { input.readFully(it) }
        val blobHeader = Fileformat.BlobHeader.parseFrom(headerBytes)
        val dataSize = blobHeader.datasize
        require(dataSize in 0..MAX_BLOB) { "Blob size $dataSize out of range" }
        val blobBytes = ByteArray(dataSize).also { input.readFully(it) }
        val blob = Fileformat.Blob.parseFrom(blobBytes)
        val payload: ByteArray = when {
            blob.hasRaw() -> blob.raw.toByteArray()
            blob.hasZlibData() -> inflate(blob.zlibData.toByteArray(), blob.rawSize)
            blob.hasLzmaData() -> throw IOException("PBF uses lzma_data; only raw and zlib_data are supported")
            else -> throw IOException("PBF blob with no supported payload (type=${blobHeader.type})")
        }
        return blobHeader.type to payload
    }

    private fun inflate(compressed: ByteArray, rawSize: Int): ByteArray {
        require(rawSize in 0..MAX_RAW) { "Blob raw_size $rawSize out of range" }
        val out = ByteArray(rawSize)
        inflater.reset()
        inflater.setInput(compressed)
        var off = 0
        try {
            while (off < rawSize) {
                val n = inflater.inflate(out, off, rawSize - off)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw IOException("zlib stream needs a preset dictionary")
                }
                off += n
            }
        } catch (e: DataFormatException) {
            throw IOException("Corrupt zlib data in PBF blob", e)
        }
        if (off != rawSize) throw IOException("Blob inflated to $off bytes, raw_size says $rawSize")
        return out
    }

    override fun close() {
        inflater.end()
        input.close()
    }

    companion object {
        /** Spec: BlobHeader must be < 64 KiB, Blob must be < 32 MiB (uncompressed). */
        const val MAX_BLOB_HEADER = 64 * 1024
        const val MAX_BLOB = 32 * 1024 * 1024
        const val MAX_RAW = 32 * 1024 * 1024

        fun open(file: File): PbfReader = open(FileInputStream(file))

        fun open(stream: InputStream): PbfReader {
            val counting = CountingInputStream(BufferedInputStream(stream, 1 shl 16))
            val input = DataInputStream(counting)
            // The spec requires OSMHeader before the first OSMData block. Parse it eagerly so required_features can
            // be rejected before any data is decoded.
            val reader = PbfReader(counting, input)
            val (type, data) = reader.readRawBlock() ?: run { reader.close(); throw IOException("Empty PBF: no OSMHeader block") }
            if (type != "OSMHeader") { reader.close(); throw IOException("PBF must start with OSMHeader, found '$type'") }
            val hb = Osmformat.HeaderBlock.parseFrom(data)
            val header = PbfHeader(
                bbox = if (hb.hasBbox()) BoundingBox(
                    south = hb.bbox.bottom / 1e9, west = hb.bbox.left / 1e9,
                    north = hb.bbox.top / 1e9, east = hb.bbox.right / 1e9,
                ) else null,
                requiredFeatures = hb.requiredFeaturesList.toList(),
                optionalFeatures = hb.optionalFeaturesList.toList(),
                writingProgram = if (hb.hasWritingprogram()) hb.writingprogram else null,
                source = if (hb.hasSource()) hb.source else null,
                replicationTimestamp = if (hb.hasOsmosisReplicationTimestamp()) hb.osmosisReplicationTimestamp else null,
            )
            val unsupported = header.requiredFeatures - PbfHeader.SUPPORTED_FEATURES
            if (unsupported.isNotEmpty()) {
                reader.close()
                throw IOException("PBF requires unsupported features: $unsupported")
            }
            reader.header = header
            return reader
        }

        /** Decodes every entity of one `PrimitiveBlock`, in block order. */
        fun decodeBlock(block: Osmformat.PrimitiveBlock): Sequence<OsmEntity> = sequence {
            val strings = StringTableView(block.stringtable)
            val granularity = block.granularity.toLong()
            val latOffset = block.latOffset
            val lonOffset = block.lonOffset
            for (group in block.primitivegroupList) {
                if (group.hasDense() && group.dense.idCount > 0) yieldAll(decodeDense(group.dense, strings, granularity, latOffset, lonOffset))
                for (n in group.nodesList) {
                    yield(OsmEntity.Node(n.id, toDegrees(n.lat, granularity, latOffset), toDegrees(n.lon, granularity, lonOffset), tags(strings, n.keysList, n.valsList)))
                }
                for (w in group.waysList) {
                    val refs = LongArray(w.refsCount)
                    var acc = 0L
                    for (i in refs.indices) { acc += w.getRefs(i); refs[i] = acc }
                    yield(OsmEntity.Way(w.id, refs, tags(strings, w.keysList, w.valsList)))
                }
                for (r in group.relationsList) {
                    val members = ArrayList<OsmEntity.Member>(r.memidsCount)
                    var acc = 0L
                    for (i in 0 until r.memidsCount) {
                        acc += r.getMemids(i)
                        val t = when (r.getTypes(i)) {
                            Osmformat.Relation.MemberType.NODE -> EntityType.NODE
                            Osmformat.Relation.MemberType.WAY -> EntityType.WAY
                            Osmformat.Relation.MemberType.RELATION -> EntityType.RELATION
                        }
                        members += OsmEntity.Member(t, acc, strings[r.getRolesSid(i)])
                    }
                    yield(OsmEntity.Relation(r.id, members, tags(strings, r.keysList, r.valsList)))
                }
            }
        }

        private fun decodeDense(
            dense: Osmformat.DenseNodes,
            strings: StringTableView,
            granularity: Long,
            latOffset: Long,
            lonOffset: Long,
        ): Sequence<OsmEntity.Node> = sequence {
            val n = dense.idCount
            require(dense.latCount == n && dense.lonCount == n) { "DenseNodes id/lat/lon length mismatch" }
            val keysVals = dense.keysValsList
            val hasTags = keysVals.isNotEmpty()
            var id = 0L; var lat = 0L; var lon = 0L
            var kv = 0
            for (i in 0 until n) {
                id += dense.getId(i); lat += dense.getLat(i); lon += dense.getLon(i)
                var tags: Map<String, String> = emptyMap()
                if (hasTags) {
                    // keys_vals: ((keyid valid)* 0)* — a 0 string id terminates each node's tags.
                    if (kv < keysVals.size && keysVals[kv] != 0) {
                        val m = HashMap<String, String>(4)
                        while (kv < keysVals.size) {
                            val k = keysVals[kv]
                            if (k == 0) break
                            m[strings[k]] = strings[keysVals[kv + 1]]
                            kv += 2
                        }
                        tags = m
                    }
                    kv++ // skip the 0 terminator (or stay past the end on a truncated array)
                }
                yield(OsmEntity.Node(id, toDegrees(lat, granularity, latOffset), toDegrees(lon, granularity, lonOffset), tags))
            }
        }

        /** Division rather than `* 1e-9`: 1e9 is exact in binary, 1e-9 is not, and the difference shows up as 7.500000000000001. */
        private fun toDegrees(raw: Long, granularity: Long, offset: Long): Double = (offset + granularity * raw) / 1e9

        private fun tags(strings: StringTableView, keys: List<Int>, vals: List<Int>): Map<String, String> {
            if (keys.isEmpty()) return emptyMap()
            require(keys.size == vals.size) { "keys/vals length mismatch" }
            val m = HashMap<String, String>(keys.size * 2)
            for (i in keys.indices) m[strings[keys[i]]] = strings[vals[i]]
            return m
        }
    }

    /** Lazily decodes string-table entries (bytes -> String) once per block; most entries are keys reused thousands of times. */
    private class StringTableView(private val table: Osmformat.StringTable) {
        private val cache = arrayOfNulls<String>(table.sCount)
        operator fun get(i: Int): String {
            require(i in cache.indices) { "string table index $i out of range (${cache.size})" }
            return cache[i] ?: table.getS(i).toStringUtf8().also { cache[i] = it }
        }
    }
}

/** Counts bytes passing through; used for progress against the input file size. */
internal class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count: Long = 0
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = delegate.skip(n).also { count += it }
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}
