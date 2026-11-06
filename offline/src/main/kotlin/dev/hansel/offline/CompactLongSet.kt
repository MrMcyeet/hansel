package dev.hansel.offline

import com.carrotsearch.hppc.LongHashSet

/**
 * An append-only set of 64-bit ids tuned for OSM ids as they appear in a Geofabrik extract: strictly ascending
 * within one file, mostly in long runs with small gaps (bulk imports allocate contiguous ids).
 *
 * Ids added in ascending order are stored as delta-varints in 1 MiB chunks: a run of consecutive ids costs one
 * byte per id, a gap of up to 16383 costs two, so a state extract averages well under 2 bytes/id against 8 bytes
 * (plus load-factor slack, so 13-26 bytes) for a `LongHashSet`. Every [INDEX_STRIDE]th id also goes into a sparse
 * index (value + byte position) so [contains] is a binary search over the index followed by decoding at most
 * [INDEX_STRIDE] varints. Queries that arrive in ascending order (the merge pass reads sorted input) hit a
 * monotone cursor instead and cost O(1) amortised.
 *
 * Ids that arrive out of order go to a small hppc [LongHashSet] overflow so correctness never depends on the
 * input being sorted; only the memory bound does.
 *
 * Measured in `CompactLongSetTest`: 10M ids with a Geofabrik-like gap distribution take about 12-20 MB including
 * the index, versus about 128-256 MB for a `LongHashSet` of the same ids.
 */
class CompactLongSet {
    private val chunks = ArrayList<ByteArray>()
    private var byteCount = 0L
    private var lastAdded = -1L
    private var count = 0
    private var indexValues = LongArray(1024)
    private var indexPositions = LongArray(1024)
    private var indexSize = 0
    private val overflow = LongHashSet()

    // Monotone cursor: position/value after the last decoded id of an ascending query sequence.
    private var cursorPos = 0L
    private var cursorValue = -1L
    private var cursorDecoded = 0

    val size: Int get() = count + overflow.size()

    /** Approximate heap bytes used by the encoded ids, index and overflow set. */
    val approximateBytes: Long
        get() = chunks.size.toLong() * CHUNK_SIZE + indexValues.size * 16L + overflow.size() * 16L

    fun add(id: Long) {
        if (id <= lastAdded) {
            if (id != lastAdded) overflow.add(id)
            return
        }
        if (count % INDEX_STRIDE == 0) pushIndex(id, byteCount)
        writeVarint(id - (if (count == 0) 0L else lastAdded))
        lastAdded = id
        count++
    }

    operator fun contains(id: Long): Boolean {
        if (count == 0) return overflow.contains(id)
        if (id > lastAdded) return overflow.contains(id)
        // Fast path: ascending queries continue from the cursor.
        if (cursorDecoded > 0 && id >= cursorValue) {
            if (id == cursorValue) return true
            if (scanFrom(cursorPos, cursorValue, cursorDecoded, id)) return true
            return overflow.contains(id)
        }
        // Binary search the sparse index for the last entry with value <= id.
        var lo = 0
        var hi = indexSize - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (indexValues[mid] <= id) lo = mid else hi = mid - 1
        }
        if (indexValues[lo] > id) return overflow.contains(id)
        val startValue = indexValues[lo]
        if (startValue == id) {
            cursorPos = indexPositions[lo]
            cursorValue = startValue
            cursorDecoded = lo * INDEX_STRIDE
            // Skip the varint that encodes startValue so the cursor is positioned after it.
            cursorPos += varintLength(cursorPos)
            cursorDecoded += 1
            return true
        }
        var pos = indexPositions[lo] + varintLength(indexPositions[lo])
        val decoded = lo * INDEX_STRIDE + 1
        if (scanFrom(pos, startValue, decoded, id)) return true
        return overflow.contains(id)
    }

    /** Scans forward from an exact (pos, value, decoded) state until value >= id; leaves the cursor there. */
    private fun scanFrom(startPos: Long, startValue: Long, startDecoded: Int, id: Long): Boolean {
        var pos = startPos
        var value = startValue
        var decoded = startDecoded
        while (decoded < count && value < id) {
            val delta = readVarint(pos)
            pos += varintLength(pos)
            value += delta
            decoded++
        }
        cursorPos = pos
        cursorValue = value
        cursorDecoded = decoded
        return value == id
    }

    private fun pushIndex(value: Long, pos: Long) {
        if (indexSize == indexValues.size) {
            indexValues = indexValues.copyOf(indexSize * 2)
            indexPositions = indexPositions.copyOf(indexSize * 2)
        }
        indexValues[indexSize] = value
        indexPositions[indexSize] = pos
        indexSize++
    }

    private fun writeVarint(v: Long) {
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) {
                writeByte(b)
                return
            }
            writeByte(b or 0x80)
        }
    }

    private fun writeByte(b: Int) {
        val chunk = (byteCount ushr CHUNK_SHIFT).toInt()
        if (chunk == chunks.size) chunks.add(ByteArray(CHUNK_SIZE))
        chunks[chunk][(byteCount and CHUNK_MASK).toInt()] = b.toByte()
        byteCount++
    }

    private fun byteAt(pos: Long): Int = chunks[(pos ushr CHUNK_SHIFT).toInt()][(pos and CHUNK_MASK).toInt()].toInt() and 0xFF

    private fun readVarint(pos: Long): Long {
        var p = pos
        var shift = 0
        var result = 0L
        while (true) {
            val b = byteAt(p++)
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun varintLength(pos: Long): Int {
        var p = pos
        var n = 1
        while (byteAt(p++) and 0x80 != 0) n++
        return n
    }

    companion object {
        private const val CHUNK_SHIFT = 20
        private const val CHUNK_SIZE = 1 shl CHUNK_SHIFT
        private const val CHUNK_MASK = (CHUNK_SIZE - 1).toLong()
        const val INDEX_STRIDE = 256
    }
}
