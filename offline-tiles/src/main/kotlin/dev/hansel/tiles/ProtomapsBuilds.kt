package dev.hansel.tiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protomaps publishes a free daily planet build (OpenStreetMap, zoom 0-15, MVT/gzip, ~139 GB as of
 * 2026-11) at `https://build.protomaps.com/YYYYMMDD.pmtiles`. The bucket root itself is not listable
 * (`https://build.protomaps.com/` answers 404); the index used by https://maps.protomaps.com/builds/ is the JSON
 * array at [BUILDS_INDEX_URL]:
 *
 * ```
 * [{"key":"20261105.pmtiles","size":139339872322,"md5sum":"...","b3sum":"...",
 *   "uploaded":"2026-11-05T09:02:51.954Z","version":"4.15.2"}, ...]
 * ```
 *
 * `version` is the `@protomaps/basemaps` tileset schema version (styles must match its major version).
 */
object ProtomapsBuilds {
    const val BUILDS_INDEX_URL = "https://build-metadata.protomaps.dev/builds.json"
    const val BUILDS_BASE_URL = "https://build.protomaps.com/"

    @Serializable
    data class Build(
        val key: String,
        val size: Long,
        val uploaded: String = "",
        val version: String = "",
        val md5sum: String? = null,
        val b3sum: String? = null,
    ) {
        val url: String get() = BUILDS_BASE_URL + key

        /** `YYYYMMDD` taken from the key. */
        val date: String get() = key.substringBefore('.')
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseIndex(body: String): List<Build> =
        json.decodeFromString<List<Build>>(body).filter { it.key.endsWith(".pmtiles") }

    /** Newest build (by key, which sorts chronologically). [fetch] must return the body of the URL it is given. */
    fun latest(fetch: (String) -> String): Build =
        parseIndex(fetch(BUILDS_INDEX_URL)).maxByOrNull { it.key } ?: error("no builds listed at $BUILDS_INDEX_URL")

    /** Convenience: URL and byte size of the newest build. */
    fun latestUrl(fetch: (String) -> String): Pair<String, Long> = latest(fetch).let { it.url to it.size }
}
