package dev.hansel.tiles

import dev.hansel.model.BoundingBox
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator (EPSG:3857 / XYZ scheme) tile arithmetic. */
object TileMath {
    const val MAX_LAT = 85.05112878

    fun lonToTileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val latRad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Inclusive tile index range covering [bbox] at zoom [z]. */
    data class TileRange(val z: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val count: Long get() = (maxX - minX + 1).toLong() * (maxY - minY + 1)
        fun contains(x: Int, y: Int) = x in minX..maxX && y in minY..maxY
    }

    fun tileRange(bbox: BoundingBox, z: Int): TileRange = TileRange(
        z,
        minX = lonToTileX(bbox.west, z), maxX = lonToTileX(bbox.east, z),
        minY = latToTileY(bbox.north, z), maxY = latToTileY(bbox.south, z),
    )

    /**
     * Sorted, distinct tile ids of every tile touching [bbox] for zooms `minZoom..maxZoom`. Because a tile's
     * parent always touches whatever the child touches, including every zoom from 0 up also includes every
     * ancestor of the max-zoom tiles.
     */
    fun tileIdsCovering(bbox: BoundingBox, minZoom: Int, maxZoom: Int): LongArray {
        require(minZoom in 0..maxZoom) { "bad zoom range $minZoom..$maxZoom" }
        var total = 0L
        for (z in minZoom..maxZoom) total += tileRange(bbox, z).count
        require(total < Int.MAX_VALUE) { "too many tiles: $total" }
        val out = LongArray(total.toInt())
        var i = 0
        for (z in minZoom..maxZoom) {
            val r = tileRange(bbox, z)
            for (x in r.minX..r.maxX) for (y in r.minY..r.maxY) out[i++] = PmTiles.zxyToTileId(z, x, y)
        }
        out.sort()
        return out
    }

    /** Number of tiles [tileIdsCovering] would return, without allocating. */
    fun tileCount(bbox: BoundingBox, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) total += tileRange(bbox, z).count
        return total
    }
}
