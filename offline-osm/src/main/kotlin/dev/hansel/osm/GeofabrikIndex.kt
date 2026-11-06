package dev.hansel.osm

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parser for Geofabrik's region index (https://download.geofabrik.de/index-v1-nogeom.json): a GeoJSON
 * FeatureCollection without geometry whose feature properties are `id`, `name`, `parent`, `iso3166-1:alpha2`,
 * `iso3166-2` and `urls` (`pbf`, `shp`, `pbf-internal`, `history`, `taginfo`, `updates`).
 *
 * Observed shape (fetched 2026-11): US states have ids like "us/mississippi", parent "north-america" (not "us"),
 * and their `name` is the id itself ("us/mississippi") except Georgia, whose name is "Georgia". Puerto Rico and the
 * US Virgin Islands are "us/puerto-rico" and "us/us-virgin-islands"; Guam, American Samoa and the Northern Mariana
 * Islands are not offered at all. Names are prettified here so the UI never shows "us/new-york".
 *
 * Pure parser: the HTTP fetch is injected so this module needs no HTTP client.
 */
object GeofabrikIndex {

    const val INDEX_URL = "${RegionCatalog.GEOFABRIK_BASE}/index-v1-nogeom.json"

    /** Downloads and parses the live index; [fetch] must return the response body for a URL. */
    fun load(fetch: (String) -> String): List<Region> = parse(fetch(INDEX_URL))

    fun parse(json: String): List<Region> =
        GeoJson.parseFeatureCollection(json).mapNotNull { f ->
            val id = f.string("id") ?: return@mapNotNull null
            val urls = f.obj("urls")
            val pbf = (urls?.get("pbf") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val rawName = f.string("name") ?: id
            val iso = f.strings("iso3166-1:alpha2") + f.strings("iso3166-2")
            Region(
                id = id,
                name = prettyName(id, rawName),
                postal = iso.firstOrNull { it.startsWith("US-") && it.length == 5 }?.substring(3)?.takeIf { id.startsWith("us/") },
                pbfUrl = pbf,
                md5Url = "$pbf.md5",
                bbox = null,
                polygons = emptyList(),
                neighbours = emptyList(),
                parent = f.string("parent"),
                isoCodes = iso,
            )
        }

    /** Everything that is not a US state/territory (those come from [RegionCatalog] with geometry). */
    fun nonUs(regions: List<Region>): List<Region> = regions.filter { !it.id.startsWith("us/") && it.id != "us" }

    /** "us/new-york" -> "New York"; names that are already words pass through. */
    internal fun prettyName(id: String, name: String): String {
        if (name != id && !name.contains('/')) return name
        val slug = name.substringAfterLast('/')
        return slug.split('-').joinToString(" ") { part ->
            when (part) {
                "of" -> "of"
                "us" -> "US"
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
    }
}
