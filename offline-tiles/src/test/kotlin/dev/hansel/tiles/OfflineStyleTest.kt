package dev.hansel.tiles

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineStyleTest {
    /** Trimmed from https://api.protomaps.com/styles/v5/dark/en.json (style@5.7.2), same shapes as the real file. */
    private val fixture = """
    {
      "version": 8,
      "name": "style@5.7.2 flavor@dark lang@en",
      "glyphs": "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf",
      "sprite": "https://protomaps.github.io/basemaps-assets/sprites/v4/dark",
      "sources": {
        "protomaps": {
          "attribution": "<a href=\"https://github.com/protomaps/basemaps\">Protomaps</a> © <a href=\"https://openstreetmap.org\">OpenStreetMap</a>",
          "type": "vector",
          "tiles": ["https://api.protomaps.com/tiles/v4/{z}/{x}/{y}.mvt?key=6d0170c0f856240d"],
          "maxzoom": 15
        }
      },
      "layers": [
        {"id": "background", "type": "background", "paint": {"background-color": "#34343f"}},
        {"id": "earth", "type": "fill", "source": "protomaps", "source-layer": "earth", "paint": {"fill-color": "#1f1f1f"}},
        {"id": "roads_shields", "type": "symbol", "source": "protomaps", "source-layer": "roads",
         "layout": {"text-font": ["Noto Sans Medium"], "icon-image": ["get", "shield_text_length"]}},
        {"id": "water_waterway_label", "type": "symbol", "source": "protomaps", "source-layer": "water",
         "layout": {"text-font": ["Noto Sans Italic"], "text-field": ["get", "name"]}},
        {"id": "places_locality", "type": "symbol", "source": "protomaps", "source-layer": "places",
         "layout": {"text-font": ["case", ["==", ["get", "script"], "Devanagari"],
                                   ["literal", ["Noto Sans Devanagari Regular v1"]],
                                   ["literal", ["Noto Sans Regular"]]],
                    "text-field": ["format", ["get", "name"], {"text-font": ["case", ["<=", ["get", "min_zoom"], 5],
                                   ["literal", ["Noto Sans Medium"]], ["literal", ["Noto Sans Regular"]]]}]}}
      ]
    }
    """.trimIndent()

    private val fonts = setOf("Noto Sans Medium", "Noto Sans Italic", "Noto Sans Devanagari Regular v1", "Noto Sans Regular")

    @Test fun `localize rewrites source, glyphs and sprite to local files`() {
        val out = OfflineStyle.localize(fixture, "/data/user/0/dev.hansel/files/tiles/region.pmtiles", File("/data/user/0/dev.hansel/files/assets"))
        val style = Json.parseToJsonElement(out).jsonObject
        val src = style["sources"]!!.jsonObject["protomaps"]!!.jsonObject
        assertEquals("vector", src["type"]!!.jsonPrimitive.content)
        assertEquals("pmtiles://file:///data/user/0/dev.hansel/files/tiles/region.pmtiles", src["url"]!!.jsonPrimitive.content)
        assertNull(src["tiles"])
        assertNull(src["maxzoom"])
        assertTrue(src["attribution"]!!.jsonPrimitive.content.contains("OpenStreetMap"))
        assertEquals("file:///data/user/0/dev.hansel/files/assets/fonts/{fontstack}/{range}.pbf", style["glyphs"]!!.jsonPrimitive.content)
        assertEquals("file:///data/user/0/dev.hansel/files/assets/sprites/dark", style["sprite"]!!.jsonPrimitive.content)
        // untouched
        assertEquals(8, style["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, style["layers"]!!.jsonArray.size)
        assertEquals("style@5.7.2 flavor@dark lang@en", style["name"]!!.jsonPrimitive.content)
        assertFalse(out.contains("api.protomaps.com"))
        assertFalse(out.contains("protomaps.github.io"))
    }

    @Test fun `font stacks are collected from plain arrays and literal expressions only`() {
        assertEquals(fonts, OfflineStyle.fontStacks(fixture))
    }

    @Test fun `plan lists every glyph range and sprite file`() {
        val all = OfflineAssets.plan(fixture)
        assertEquals(fonts.size * 256 + 4, all.size)
        assertTrue(all.any { it.url == "https://protomaps.github.io/basemaps-assets/fonts/Noto%20Sans%20Regular/0-255.pbf" && it.relativePath == "fonts/Noto Sans Regular/0-255.pbf" })
        assertTrue(all.any { it.url == "https://protomaps.github.io/basemaps-assets/fonts/Noto%20Sans%20Devanagari%20Regular%20v1/65280-65535.pbf" })
        assertTrue(all.any { it.url == "https://protomaps.github.io/basemaps-assets/sprites/v4/dark@2x.png" && it.relativePath == "sprites/dark@2x.png" })
        assertTrue(all.any { it.url == "https://protomaps.github.io/basemaps-assets/sprites/v4/dark.json" && it.relativePath == "sprites/dark.json" })
        assertEquals(all.size, all.map { it.relativePath }.toSet().size)

        val basic = OfflineAssets.plan(fixture, OfflineAssets.BASIC_LATIN_PLUS)
        assertEquals(fonts.size * 11 + 4, basic.size)
        assertTrue(basic.any { it.relativePath == "fonts/Noto Sans Regular/2304-2559.pbf" })
        assertTrue(basic.any { it.relativePath == "fonts/Noto Sans Regular/8192-8447.pbf" })
        assertFalse(basic.any { it.relativePath == "fonts/Noto Sans Regular/19968-20223.pbf" })
        assertEquals("65280-65535", OfflineAssets.ALL_RANGES.last())
        assertEquals(256, OfflineAssets.ALL_RANGES.size)
    }

    @Test fun `download fetches with concurrency and skips existing files`() = runBlocking {
        val server = MockWebServer()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse.Builder().code(200).body("content-of:" + request.url.encodedPath).build()
            }
        }
        server.start()
        val dest = java.nio.file.Files.createTempDirectory("assets").toFile()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val style = fixture
                .replace("https://protomaps.github.io/basemaps-assets/fonts", "$base/fonts")
                .replace("https://protomaps.github.io/basemaps-assets/sprites/v4", "$base/sprites/v4")
            val plan = OfflineAssets.plan(style, listOf("0-255", "256-511"))
            assertEquals(fonts.size * 2 + 4, plan.size)
            File(dest, "sprites").mkdirs()
            File(dest, "sprites/dark.json").writeText("already here")

            val progress = ArrayList<AssetProgress>()
            val client = OkHttpClient()
            val downloaded = OfflineAssets.download(plan, client, dest, { progress += it })
            assertEquals(plan.size - 1, downloaded)
            assertEquals(plan.size, progress.last().filesDone)
            assertEquals(1, progress.last().skipped)
            assertEquals("already here", File(dest, "sprites/dark.json").readText())
            assertEquals("content-of:/fonts/Noto%20Sans%20Regular/256-511.pbf", File(dest, "fonts/Noto Sans Regular/256-511.pbf").readText())
            assertEquals("content-of:/sprites/v4/dark@2x.png", File(dest, "sprites/dark@2x.png").readText())
            assertEquals(plan.size - 1, requests.get())

            // second run: everything skipped
            assertEquals(0, OfflineAssets.download(plan, client, dest))
            assertEquals(plan.size - 1, requests.get())
        } finally {
            server.close(); dest.deleteRecursively()
        }
    }

    @Test fun `hosted style url and themes`() {
        assertEquals("https://api.protomaps.com/styles/v5/dark/en.json?key=k", OfflineStyle.hostedStyleUrl("dark", "k"))
        assertEquals(listOf("light", "dark", "white", "grayscale", "black"), OfflineStyle.themes)
        assertEquals("grayscale", OfflineStyle.spriteTheme("https://protomaps.github.io/basemaps-assets/sprites/v4/grayscale"))
    }

    @Test fun `protomaps builds index parses the real shape and picks the newest`() {
        val body = """[
          {"key":"20230918.pmtiles","size":114683195425,"uploaded":"2023-09-18T11:02:48.409Z","version":"0.0.0"},
          {"key":"20261105.pmtiles","size":139339872322,"md5sum":"ejmrJehvyrfzImsSpm0VJg==","b3sum":"9f51","uploaded":"2026-11-05T09:02:51.954Z","version":"4.15.2"},
          {"key":"20261104.pmtiles","size":139288101746,"uploaded":"2026-11-04T09:04:47.073Z","version":"4.15.2"},
          {"key":"20261104.layerstats.parquet","size":1,"uploaded":"2026-11-04T09:04:47.073Z","version":"4.15.2"}
        ]"""
        val requested = ArrayList<String>()
        val (url, size) = ProtomapsBuilds.latestUrl { requested += it; body }
        assertEquals(listOf("https://build-metadata.protomaps.dev/builds.json"), requested)
        assertEquals("https://build.protomaps.com/20261105.pmtiles", url)
        assertEquals(139339872322L, size)
        val latest = ProtomapsBuilds.latest { body }
        assertEquals("20261105", latest.date)
        assertEquals("4.15.2", latest.version)
        assertEquals(3, ProtomapsBuilds.parseIndex(body).size)
    }
}
