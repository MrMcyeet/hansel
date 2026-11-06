package dev.hansel.storage

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpxWriterTest {
    private val points = listOf(
        GpxPoint(atMillis = 1_700_000_000_000, lat = 30.3322, lon = -81.6557, speedMps = 12.5f, bearing = 90f, accuracyMeters = 4f),
        GpxPoint(atMillis = 1_700_000_003_000, lat = 30.3325, lon = -81.6550),
    )

    @Test
    fun `writes a well-formed GPX 1_1 track with one segment`() {
        val gpx = GpxWriter.write("Test & <trip>", points, startAtMillis = 1_700_000_000_000)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(gpx.byteInputStream())
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("1.1", doc.documentElement.getAttribute("version"))
        assertEquals(1, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trk").length)
        assertEquals(1, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(2, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        assertEquals("Test & <trip>", doc.getElementsByTagName("name").item(0).textContent)
    }

    @Test
    fun `point attributes and ISO times are emitted`() {
        val gpx = GpxWriter.write("t", points)
        assertTrue(gpx.contains("<trkpt lat=\"30.3322000\" lon=\"-81.6557000\">"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:23Z</time>"))
    }

    @Test
    fun `speed course and accuracy go into extensions only when present`() {
        val gpx = GpxWriter.write("t", points)
        assertTrue(gpx.contains("<hansel:speed>12.5</hansel:speed>"))
        assertTrue(gpx.contains("<hansel:course>90.0</hansel:course>"))
        assertTrue(gpx.contains("<hansel:hacc>4.0</hansel:hacc>"))
        assertEquals(1, Regex("<extensions>").findAll(gpx).count())
    }

    @Test
    fun `empty trace still produces a valid document`() {
        val gpx = GpxWriter.write("empty", emptyList())
        assertTrue(gpx.contains("<trkseg>"))
        assertFalse(gpx.contains("<trkpt"))
        assertFalse(gpx.contains("<metadata>\n    <time>"))
    }
}
