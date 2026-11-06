package dev.hansel.offline

/**
 * The Geofabrik US extracts the app can install. One entry per state plus DC (and the two Geofabrik-listed
 * territories, in their own group so the picker can hide them).
 *
 * Sizes were read from https://download.geofabrik.de/north-america/us.html on 2026-11-06 (Geofabrik republishes
 * every file daily, so they drift by a few percent). The URL slug is the Geofabrik convention: the English name
 * lower-cased with spaces as hyphens, e.g. `north-carolina`, `district-of-columbia`, `us-virgin-islands`.
 * [Downloader] replaces [Region.approxBytes] with the real Content-Length once it has it.
 */
object Regions {
    private const val BASE = "https://download.geofabrik.de/north-america/us/"
    private const val MB = 1_000_000L

    private fun us(name: String, mb: Double, group: String = "US"): Region {
        val slug = name.lowercase().replace(' ', '-')
        return Region(id = slug, name = name, url = "$BASE$slug-latest.osm.pbf", approxBytes = (mb * MB).toLong(), group = group)
    }

    val US: List<Region> = listOf(
        us("Alabama", 131.0),
        us("Alaska", 134.0),
        us("Arizona", 275.0),
        us("Arkansas", 87.0),
        us("California", 1200.0),
        us("Colorado", 337.0),
        us("Connecticut", 202.0),
        us("Delaware", 19.8),
        us("District of Columbia", 19.4),
        us("Florida", 603.0),
        us("Georgia", 327.0),
        us("Hawaii", 23.6),
        us("Idaho", 115.0),
        us("Illinois", 320.0),
        us("Indiana", 174.0),
        us("Iowa", 122.0),
        us("Kansas", 99.0),
        us("Kentucky", 142.0),
        us("Louisiana", 134.0),
        us("Maine", 84.0),
        us("Maryland", 196.0),
        us("Massachusetts", 288.0),
        us("Michigan", 282.0),
        us("Minnesota", 254.0),
        us("Mississippi", 87.0),
        us("Missouri", 174.0),
        us("Montana", 93.0),
        us("Nebraska", 86.0),
        us("Nevada", 113.0),
        us("New Hampshire", 64.0),
        us("New Jersey", 149.0),
        us("New Mexico", 124.0),
        us("New York", 462.0),
        us("North Carolina", 393.0),
        us("North Dakota", 119.0),
        us("Ohio", 284.0),
        us("Oklahoma", 154.0),
        us("Oregon", 232.0),
        us("Pennsylvania", 310.0),
        us("Rhode Island", 47.3),
        us("South Carolina", 150.0),
        us("South Dakota", 41.4),
        us("Tennessee", 166.0),
        us("Texas", 648.0),
        us("Utah", 148.0),
        us("Vermont", 42.3),
        us("Virginia", 398.0),
        us("Washington", 329.0),
        us("West Virginia", 89.0),
        us("Wisconsin", 271.0),
        us("Wyoming", 83.0),
        // Territories Geofabrik lists under /us/ (slug for the Virgin Islands is "us-virgin-islands").
        Region("puerto-rico", "Puerto Rico", "${BASE}puerto-rico-latest.osm.pbf", (68.0 * MB).toLong(), group = "US territory"),
        Region("us-virgin-islands", "U.S. Virgin Islands", "${BASE}us-virgin-islands-latest.osm.pbf", (2.9 * MB).toLong(), group = "US territory"),
    )

    private val byId: Map<String, Region> = US.associateBy { it.id }

    fun byId(id: String): Region? = byId[id]

    /** Throws with a readable message for an unknown id (typo in a saved preference, for instance). */
    fun require(id: String): Region = byId(id) ?: throw IllegalArgumentException("Unknown region id '$id'")
}
