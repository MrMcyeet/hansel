package dev.hansel.osm

import com.google.protobuf.ByteString
import dev.hansel.model.BoundingBox
import org.openstreetmap.osmosis.osmbinary.Fileformat
import org.openstreetmap.osmosis.osmbinary.Osmformat
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.roundToLong

/**
 * Streaming `.osm.pbf` writer. Entities are buffered into blocks of at most [Options.blockSize] entities of one type,
 * each written as a zlib-compressed `OSMData` blob with its own string table; nodes go out as `DenseNodes`.
 *
 * The caller is responsible for ordering. [PbfMerger] always feeds nodes, ways, relations ascending by id, which is
 * what GraphHopper's reader (and every other consumer) expects; the header advertises `Sort.Type_then_ID` only when
 * [Options.sorted] is true.
 *
 * No entity metadata (version/timestamp/user) is written, so the output does not declare `Has_Metadata`.
 */
class PbfWriter(stream: OutputStream, private val options: Options = Options()) : Closeable {

    data class Options(
        val bbox: BoundingBox? = null,
        val source: String? = null,
        /** `osmosis_replication_timestamp`, epoch seconds. GraphHopper reports it as the data date. */
        val replicationTimestamp: Long? = null,
        val writingProgram: String = "hansel",
        /** Spec recommends <= 8000 entities per block. */
        val blockSize: Int = 8000,
        val sorted: Boolean = true,
        /** zlib level; 6 is a good speed/size trade-off on a phone. 0 writes `raw` blobs (faster, ~2x larger). */
        val compressionLevel: Int = 6,
    )

    constructor(file: File, options: Options = Options()) : this(FileOutputStream(file), options)

    private val counting = CountingOutputStream(BufferedOutputStream(stream, 1 shl 16))
    private val out = DataOutputStream(counting)
    private val deflater = Deflater(options.compressionLevel)
    private val pending = ArrayList<OsmEntity>(options.blockSize)
    private var pendingType: EntityType? = null
    private var closed = false

    var nodesWritten = 0L; private set
    var waysWritten = 0L; private set
    var relationsWritten = 0L; private set
    val bytesWritten: Long get() = counting.count

    init {
        writeHeader()
    }

    fun write(entity: OsmEntity) {
        check(!closed) { "writer closed" }
        if (pendingType != null && pendingType != entity.type) flush()
        pendingType = entity.type
        pending += entity
        if (pending.size >= options.blockSize) flush()
    }

    fun writeAll(entities: Sequence<OsmEntity>) = entities.forEach(::write)
    fun writeAll(entities: Iterable<OsmEntity>) = entities.forEach(::write)

    /** Writes the buffered entities as one block. Called automatically; public so tests can force small blocks. */
    fun flush() {
        if (pending.isEmpty()) return
        val strings = StringTableBuilder()
        val group = Osmformat.PrimitiveGroup.newBuilder()
        when (pendingType!!) {
            EntityType.NODE -> { group.setDense(encodeDense(strings)); nodesWritten += pending.size }
            EntityType.WAY -> { for (e in pending) group.addWays(encodeWay(e as OsmEntity.Way, strings)); waysWritten += pending.size }
            EntityType.RELATION -> { for (e in pending) group.addRelations(encodeRelation(e as OsmEntity.Relation, strings)); relationsWritten += pending.size }
        }
        val block = Osmformat.PrimitiveBlock.newBuilder()
            .setStringtable(strings.build())
            .setGranularity(GRANULARITY)
            .setLatOffset(0).setLonOffset(0)
            .setDateGranularity(1000)
            .addPrimitivegroup(group)
            .build()
        writeBlob("OSMData", block.toByteArray())
        pending.clear()
        pendingType = null
    }

    override fun close() {
        if (closed) return
        flush()
        closed = true
        out.flush()
        out.close()
        deflater.end()
    }

    private fun writeHeader() {
        val hb = Osmformat.HeaderBlock.newBuilder()
            .addRequiredFeatures("OsmSchema-V0.6")
            .addRequiredFeatures("DenseNodes")
            .setWritingprogram(options.writingProgram)
        if (options.sorted) hb.addOptionalFeatures("Sort.Type_then_ID")
        options.bbox?.let { b ->
            hb.setBbox(
                Osmformat.HeaderBBox.newBuilder()
                    .setLeft(nano(b.west)).setRight(nano(b.east))
                    .setTop(nano(b.north)).setBottom(nano(b.south)),
            )
        }
        options.source?.let { hb.setSource(it) }
        options.replicationTimestamp?.let { hb.setOsmosisReplicationTimestamp(it) }
        writeBlob("OSMHeader", hb.build().toByteArray())
    }

    private fun writeBlob(type: String, payload: ByteArray) {
        require(payload.size < PbfReader.MAX_RAW) { "block too large: ${payload.size}" }
        val blob = Fileformat.Blob.newBuilder()
        if (options.compressionLevel == 0) {
            blob.setRaw(ByteString.copyFrom(payload))
        } else {
            blob.setRawSize(payload.size)
            blob.setZlibData(ByteString.copyFrom(deflate(payload)))
        }
        val blobBytes = blob.build().toByteArray()
        val header = Fileformat.BlobHeader.newBuilder().setType(type).setDatasize(blobBytes.size).build().toByteArray()
        out.writeInt(header.size)
        out.write(header)
        out.write(blobBytes)
    }

    private fun deflate(data: ByteArray): ByteArray {
        deflater.reset()
        deflater.setInput(data)
        deflater.finish()
        var buf = ByteArray(maxOf(1024, data.size / 2))
        var len = 0
        while (!deflater.finished()) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            len += deflater.deflate(buf, len, buf.size - len)
        }
        return buf.copyOf(len)
    }

    private fun encodeDense(strings: StringTableBuilder): Osmformat.DenseNodes.Builder {
        val d = Osmformat.DenseNodes.newBuilder()
        var lastId = 0L; var lastLat = 0L; var lastLon = 0L
        val anyTags = pending.any { it.tags.isNotEmpty() }
        for (e in pending) {
            val n = e as OsmEntity.Node
            val lat = coord(n.lat); val lon = coord(n.lon)
            d.addId(n.id - lastId); d.addLat(lat - lastLat); d.addLon(lon - lastLon)
            lastId = n.id; lastLat = lat; lastLon = lon
            if (anyTags) {
                for ((k, v) in n.tags) { d.addKeysVals(strings.id(k)); d.addKeysVals(strings.id(v)) }
                d.addKeysVals(0)
            }
        }
        return d
    }

    private fun encodeWay(w: OsmEntity.Way, strings: StringTableBuilder): Osmformat.Way.Builder {
        val b = Osmformat.Way.newBuilder().setId(w.id)
        for ((k, v) in w.tags) { b.addKeys(strings.id(k)); b.addVals(strings.id(v)) }
        var last = 0L
        for (r in w.refs) { b.addRefs(r - last); last = r }
        return b
    }

    private fun encodeRelation(r: OsmEntity.Relation, strings: StringTableBuilder): Osmformat.Relation.Builder {
        val b = Osmformat.Relation.newBuilder().setId(r.id)
        for ((k, v) in r.tags) { b.addKeys(strings.id(k)); b.addVals(strings.id(v)) }
        var last = 0L
        for (m in r.members) {
            b.addRolesSid(strings.id(m.role))
            b.addMemids(m.ref - last); last = m.ref
            b.addTypes(
                when (m.type) {
                    EntityType.NODE -> Osmformat.Relation.MemberType.NODE
                    EntityType.WAY -> Osmformat.Relation.MemberType.WAY
                    EntityType.RELATION -> Osmformat.Relation.MemberType.RELATION
                },
            )
        }
        return b
    }

    /** Index 0 is the empty string, reserved as the DenseNodes tag separator. */
    private class StringTableBuilder {
        private val index = HashMap<String, Int>().also { it[""] = 0 }
        private val list = ArrayList<String>().also { it += "" }
        fun id(s: String): Int = index.getOrPut(s) { list += s; list.size - 1 }
        fun build(): Osmformat.StringTable {
            val b = Osmformat.StringTable.newBuilder()
            for (s in list) b.addS(ByteString.copyFromUtf8(s))
            return b.build()
        }
    }

    companion object {
        /** 100 nanodegrees = 1e-7 degrees, i.e. the OSM database's own precision (~1.1 cm). */
        const val GRANULARITY = 100

        private fun coord(deg: Double): Long = (deg * 1e7).roundToLong()
        private fun nano(deg: Double): Long = (deg * 1e9).roundToLong()
    }
}

internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) { delegate.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); count += len }
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
