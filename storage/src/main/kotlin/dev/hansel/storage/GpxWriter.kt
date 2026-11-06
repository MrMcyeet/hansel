package dev.hansel.storage

import java.time.Instant
import java.util.Locale

/** One point of a trace to export; mirrors [GpsSampleEntity] without the Room dependency so it's JVM-testable. */
data class GpxPoint(
    val atMillis: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val accuracyMeters: Float? = null,
)

/**
 * Minimal GPX 1.1 writer: one `<trk>` with one `<trkseg>`. Speed/course/accuracy go into a `<extensions>` block
 * under the `hansel` namespace because GPX 1.1 dropped `<speed>` from `<trkpt>`. Raw values, no rounding of speed.
 */
object GpxWriter {
    private const val NS = "https://hansel.dev/gpx/1"

    fun write(name: String, points: List<GpxPoint>, startAtMillis: Long? = null): String {
        val sb = StringBuilder(256 + points.size * 160)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Hansel\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:hansel=\"").append(NS).append("\">\n")
        sb.append("  <metadata>\n")
        if (startAtMillis != null) sb.append("    <time>").append(iso(startAtMillis)).append("</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(fmt(p.lat)).append("\" lon=\"").append(fmt(p.lon)).append("\">\n")
            sb.append("        <time>").append(iso(p.atMillis)).append("</time>\n")
            if (p.speedMps != null || p.bearing != null || p.accuracyMeters != null) {
                sb.append("        <extensions>\n")
                p.speedMps?.let { sb.append("          <hansel:speed>").append(it.toString()).append("</hansel:speed>\n") }
                p.bearing?.let { sb.append("          <hansel:course>").append(it.toString()).append("</hansel:course>\n") }
                p.accuracyMeters?.let { sb.append("          <hansel:hacc>").append(it.toString()).append("</hansel:hacc>\n") }
                sb.append("        </extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.7f", d)
    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
