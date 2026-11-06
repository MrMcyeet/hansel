package dev.hansel.osm

import dev.hansel.model.BoundingBox
import java.io.File
import java.io.IOException
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

data class MergeProgress(
    /** Which entity type the merge is currently emitting. */
    val phase: EntityType,
    val entitiesRead: Long,
    val entitiesWritten: Long,
    val duplicatesSkipped: Long,
    /** Compressed input bytes consumed across all inputs, against [bytesTotal] (sum of input file sizes). */
    val bytesRead: Long,
    val bytesTotal: Long,
    val bytesWritten: Long,
) {
    val fraction: Double get() = if (bytesTotal <= 0) 0.0 else (bytesRead.toDouble() / bytesTotal).coerceIn(0.0, 1.0)
}

data class MergeResult(
    val inputs: Int,
    val nodes: Long,
    val ways: Long,
    val relations: Long,
    val duplicatesSkipped: Long,
    val bytesRead: Long,
    val bytesWritten: Long,
) {
    val entities: Long get() = nodes + ways + relations
}

/**
 * Merges several `.osm.pbf` extracts into one, de-duplicating by entity id.
 *
 * Geofabrik extracts are "complete ways": a way that crosses a state line, and all of its nodes, is present in both
 * state files with identical ids, so the union-by-id of the inputs is a consistent multi-state file that
 * GraphHopper's single-file `OSMReader` can consume.
 *
 * Memory: every Geofabrik file is already sorted `Sort.Type_then_ID` (nodes, ways, relations; ascending ids). The
 * merge is a single-pass k-way merge over the k inputs, so at any moment RAM holds one decoded block (<= 8000
 * entities) per input plus the writer's pending block, i.e. on the order of a few MB for three states regardless of
 * the tens of millions of nodes streamed through. No id set is kept: a duplicate is simply an entity whose
 * (type, id) equals the one just emitted, which the ordering guarantees are adjacent. First input wins.
 *
 * Inputs that are not sorted this way are rejected with an [IOException] rather than silently producing a file
 * other readers would mis-handle.
 */
object PbfMerger {

    /** Progress is reported at least every this many entities. */
    private const val PROGRESS_EVERY = 250_000L

    fun merge(inputs: List<File>, output: File, progress: (MergeProgress) -> Unit = {}): MergeResult {
        require(inputs.isNotEmpty()) { "no inputs" }
        for (f in inputs) if (!f.isFile) throw IOException("input not found: $f")
        val bytesTotal = inputs.sumOf { it.length() }

        val readers = inputs.map { PbfReader.open(it) }
        val tmp = File(output.parentFile ?: File("."), output.name + ".part")
        try {
            val headers = readers.map { it.header }
            val options = PbfWriter.Options(
                bbox = unionBbox(headers),
                source = inputs.joinToString(",") { it.name },
                replicationTimestamp = headers.mapNotNull { it.replicationTimestamp }.minOrNull(),
            )
            val cursors = readers.mapIndexed { i, r -> Cursor(i, inputs[i].name, r.entities().iterator()) }
            var entitiesRead = 0L
            var written = 0L
            var dupes = 0L
            var nodes = 0L; var ways = 0L; var relations = 0L
            var lastType: EntityType? = null
            var lastId = Long.MIN_VALUE
            var nextProgressAt = PROGRESS_EVERY

            PbfWriter(tmp, options).use { writer ->
                val queue = PriorityQueue<Cursor>(cursors.size, compareBy<Cursor> { it.head!!.type }.thenBy { it.head!!.id }.thenBy { it.index })
                for (c in cursors) if (c.advance()) queue.add(c)
                var phase = EntityType.NODE

                fun report() = progress(
                    MergeProgress(phase, entitiesRead, written, dupes, readers.sumOf { it.bytesRead }, bytesTotal, writer.bytesWritten),
                )

                while (queue.isNotEmpty()) {
                    val c = queue.poll()
                    val e = c.head!!
                    entitiesRead++
                    if (e.type != phase) { phase = e.type; report() }
                    if (lastType == e.type && lastId == e.id) {
                        dupes++
                    } else {
                        writer.write(e)
                        written++
                        when (e.type) {
                            EntityType.NODE -> nodes++
                            EntityType.WAY -> ways++
                            EntityType.RELATION -> relations++
                        }
                        lastType = e.type; lastId = e.id
                    }
                    if (c.advance()) queue.add(c)
                    if (entitiesRead >= nextProgressAt) { report(); nextProgressAt += PROGRESS_EVERY }
                }
                phase = lastType ?: phase
                report()
            }
            val bytesRead = readers.sumOf { it.bytesRead }
            readers.forEach { it.close() }
            if (output.exists() && !output.delete()) throw IOException("could not replace $output")
            if (!tmp.renameTo(output)) throw IOException("could not rename $tmp to $output")
            return MergeResult(inputs.size, nodes, ways, relations, dupes, bytesRead, output.length())
        } catch (t: Throwable) {
            readers.forEach { runCatching { it.close() } }
            tmp.delete()
            throw t
        }
    }

    private fun unionBbox(headers: List<PbfHeader>): BoundingBox? {
        val boxes = headers.map { it.bbox ?: return null }
        return boxes.reduce { a, b -> BoundingBox(min(a.south, b.south), min(a.west, b.west), max(a.north, b.north), max(a.east, b.east)) }
    }

    /** One input's position in the merge, plus the sortedness check that makes the no-hash-set de-dup valid. */
    private class Cursor(val index: Int, val name: String, private val it: Iterator<OsmEntity>) {
        var head: OsmEntity? = null
            private set
        private var prevType: EntityType? = null
        private var prevId = Long.MIN_VALUE

        fun advance(): Boolean {
            if (!it.hasNext()) { head = null; return false }
            val e = it.next()
            val pt = prevType
            if (pt != null) {
                val ok = e.type.ordinal > pt.ordinal || (e.type == pt && e.id > prevId)
                if (!ok) throw IOException("$name is not sorted by type then id (saw ${e.type} ${e.id} after $pt $prevId); PbfMerger requires Sort.Type_then_ID inputs")
            }
            prevType = e.type; prevId = e.id
            head = e
            return true
        }
    }
}
