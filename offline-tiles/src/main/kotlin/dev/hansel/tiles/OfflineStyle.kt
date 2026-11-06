package dev.hansel.tiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Rewrites a Protomaps basemap style (MapLibre style spec v8) so that every network reference points at
 * local files.
 *
 * Observed shape of `https://api.protomaps.com/styles/v5/<theme>/en.json?key=...` (style@5.7.2, 2026-11):
 * ```
 * { "version": 8, "name": "style@5.7.2 flavor@dark lang@en",
 *   "glyphs": "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf",
 *   "sprite": "https://protomaps.github.io/basemaps-assets/sprites/v4/dark",
 *   "sources": { "protomaps": { "type": "vector", "attribution": "...",
 *                 "tiles": ["https://api.protomaps.com/tiles/v4/{z}/{x}/{y}.mvt?key=..."], "maxzoom": 15 } },
 *   "layers": [ ...71 layers, all on source "protomaps" (or background)... ] }
 * ```
 * Fonts referenced via `text-font` (directly or inside `["literal", [...]]` expressions):
 * "Noto Sans Regular", "Noto Sans Italic", "Noto Sans Medium", "Noto Sans Devanagari Regular v1".
 *
 * MapLibre Native URL forms (confirmed in maplibre-native `platform/default/src/mln/storage/`):
 * - PMTiles: `PMTILES_PROTOCOL + FILE_PROTOCOL + absolutePath` = `pmtiles://file:///abs/path.pmtiles`.
 *   `PMTilesFileSource` strips `pmtiles://` and hands the remainder to the regular resource loader, so the
 *   inner part must itself be a loadable URL (`file://`, `asset://`, `http(s)://`). A bare
 *   `pmtiles:///abs/path` does not resolve. (`mbtiles://` works the same way for MBTiles files.)
 * - Glyphs: `{fontstack}` is percent-encoded by MapLibre (`Noto%20Sans%20Regular`) and `LocalFileSource`
 *   percent-decodes `file://` paths, so on disk the directory is literally `fonts/Noto Sans Regular/0-255.pbf`.
 * - Sprite: MapLibre appends `.json`/`.png` and `@2x.json`/`@2x.png` to the sprite URL.
 */
object OfflineStyle {
    val themes = listOf("light", "dark", "white", "grayscale", "black")

    const val SOURCE_NAME = "protomaps"
    const val GLYPHS_SUBDIR = "fonts"
    const val SPRITES_SUBDIR = "sprites"

    fun hostedStyleUrl(theme: String, key: String, lang: String = "en"): String {
        require(theme in themes) { "unknown theme $theme" }
        return "https://api.protomaps.com/styles/v5/$theme/$lang.json?key=$key"
    }

    /** `pmtiles://file:///abs/path` for MapLibre Native's PMTilesFileSource. */
    fun pmtilesUrl(absolutePath: String): String {
        require(absolutePath.startsWith("/")) { "absolute path required: $absolutePath" }
        return "pmtiles://file://$absolutePath"
    }

    fun fileUrl(file: File): String = "file://" + file.absolutePath

    /** Theme name derived from the hosted sprite URL (`.../sprites/v4/dark` -> `dark`). */
    fun spriteTheme(spriteUrl: String): String = spriteUrl.trimEnd('/').substringAfterLast('/')

    /**
     * Rewrite [styleJson] for fully offline use.
     *
     * - `sources.protomaps` becomes `{ "type": "vector", "url": "pmtiles://file://<pmtilesAbsolutePath>" }`
     *   (`attribution` is kept when present; `tiles`/`maxzoom` are dropped, MapLibre reads them from the file).
     * - `glyphs` becomes `file://<assetsDir>/fonts/{fontstack}/{range}.pbf`.
     * - `sprite` becomes `file://<assetsDir>/sprites/<theme>` (array sprites are rewritten per entry).
     *
     * [assetsDir] must be absolute; `file://` URLs in MapLibre require absolute paths.
     */
    fun localize(styleJson: String, pmtilesAbsolutePath: String, assetsDir: File): String {
        val style = Json.parseToJsonElement(styleJson).jsonObject
        val assets = assetsDir.absoluteFile
        val out = LinkedHashMap<String, JsonElement>(style)

        val sources = style["sources"]?.jsonObject ?: error("style has no sources")
        val src = sources[SOURCE_NAME]?.jsonObject ?: error("style has no '$SOURCE_NAME' source; sources: ${sources.keys}")
        val newSource = buildJsonObject {
            put("type", JsonPrimitive("vector"))
            put("url", JsonPrimitive(pmtilesUrl(pmtilesAbsolutePath)))
            src["attribution"]?.let { put("attribution", it) }
        }
        out["sources"] = JsonObject(LinkedHashMap(sources).also { it[SOURCE_NAME] = newSource })

        if (style.containsKey("glyphs")) {
            out["glyphs"] = JsonPrimitive(fileUrl(File(assets, GLYPHS_SUBDIR)) + "/{fontstack}/{range}.pbf")
        }
        when (val sprite = style["sprite"]) {
            null -> Unit
            is JsonPrimitive -> out["sprite"] = JsonPrimitive(localSpriteUrl(assets, sprite.content))
            is JsonArray -> out["sprite"] = JsonArray(sprite.map { item ->
                val o = item.jsonObject
                val url = o["url"]?.jsonPrimitive?.content ?: error("sprite entry without url")
                JsonObject(LinkedHashMap(o).also { it["url"] = JsonPrimitive(localSpriteUrl(assets, url)) })
            })
            else -> error("unexpected sprite value: $sprite")
        }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun localSpriteUrl(assets: File, hostedUrl: String) =
        fileUrl(File(File(assets, SPRITES_SUBDIR), spriteTheme(hostedUrl)))

    /** Distinct font stacks requested by the style's `text-font` properties, as MapLibre will request them. */
    fun fontStacks(styleJson: String): Set<String> = fontStacks(Json.parseToJsonElement(styleJson).jsonObject)

    fun fontStacks(style: JsonObject): Set<String> {
        val out = LinkedHashSet<String>()
        val layers = style["layers"] as? JsonArray ?: return out
        for (layer in layers) {
            val layout = layer.jsonObject["layout"] as? JsonObject ?: continue
            val tf = layout["text-font"] ?: continue
            collectFontStacks(tf, out, topLevel = true)
        }
        return out
    }

    /**
     * `text-font` is an array of font names, or an expression that evaluates to one. Font arrays appear
     * either as the top-level value or as the argument of a `["literal", [...]]`; any other array of strings
     * (e.g. `["get", "script"]`) is expression syntax and skipped.
     */
    private fun collectFontStacks(v: JsonElement, out: MutableSet<String>, topLevel: Boolean) {
        if (v !is JsonArray) return
        val strings = v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (strings.size == v.size && v.isNotEmpty()) {
            if (topLevel) out += strings.joinToString(",")
            return
        }
        val first = (v.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (first == "literal") {
            val arg = v.getOrNull(1)
            if (arg is JsonArray) {
                val names = arg.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (names.isNotEmpty()) out += names.joinToString(",")
            }
            return
        }
        for (child in v) collectFontStacks(child, out, topLevel = false)
    }
}
