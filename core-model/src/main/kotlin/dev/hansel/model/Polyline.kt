package dev.hansel.model

/**
 * Google encoded-polyline codec. Valhalla emits precision 6 (`polyline6`); GraphHopper emits precision 5
 * unless `points_encoded=false`. Both are handled by the [precision] parameter.
 */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val factor = pow10(precision)
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L; var shift = 0; var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            result = 0L; shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            out += LatLng(lat / factor, lon / factor)
        }
        return out
    }

    fun encode(points: List<LatLng>, precision: Int = 6): String {
        val factor = pow10(precision)
        val sb = StringBuilder()
        var prevLat = 0L; var prevLon = 0L
        for (p in points) {
            val lat = Math.round(p.lat * factor)
            val lon = Math.round(p.lon * factor)
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat; prevLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value.toInt() + 63).toChar())
    }

    private fun pow10(p: Int): Double { var f = 1.0; repeat(p) { f *= 10.0 }; return f }
}
