package dev.hansel.offline

import kotlinx.serialization.Serializable

/** A downloadable OSM extract. Geofabrik publishes one per US state; ids are Geofabrik path stems. */
@Serializable
data class Region(
    /** e.g. "mississippi" */
    val id: String,
    /** e.g. "Mississippi" */
    val name: String,
    /** Full URL of the .osm.pbf; a `.md5` sidecar is expected at the same URL + ".md5". */
    val url: String,
    /** Approximate size in bytes, for the size preview before download. Updated from Content-Length once known. */
    val approxBytes: Long,
    val group: String = "US",
)

/** Per-region install state (DESIGN §4.2 "show size, components, progress, version, integrity"). */
@Serializable
data class InstalledRegion(
    val region: Region,
    val bytes: Long,
    val downloadedAtMillis: Long,
    val md5: String? = null,
    /** From the extract's OSM header, if present; otherwise the download date. */
    val sourceTimestamp: String? = null,
)

@Serializable
data class OfflineManifest(
    val regions: List<InstalledRegion> = emptyList(),
    /** Regions that are part of the currently active graph (may lag [regions] until the next build). */
    val graphRegionIds: List<String> = emptyList(),
    val graphBuiltAtMillis: Long? = null,
    val graphBytes: Long = 0,
    /** Hash of the importer configuration (profile, encoded values, GraphHopper version) that built the graph. */
    val graphBuildConfig: String? = null,
    val searchIndexRows: Long = 0,
    val searchBuiltAtMillis: Long? = null,
    /** Map tiles: bbox covered by the active PMTiles file (south, west, north, east) and its zoom cap. */
    val tilesBbox: List<Double>? = null,
    val tilesMaxZoom: Int? = null,
    val tilesBytes: Long = 0,
    val tilesBuiltAtMillis: Long? = null,
    val tilesSource: String? = null,
    /** Themes whose localized style JSON exists under OfflineLayout.styleDir. */
    val styleThemes: List<String> = emptyList(),
)

/** One running job's progress; the setup screen renders exactly this. */
@Serializable
data class JobProgress(
    val phase: Phase,
    /** 0..1 within the phase, or null when unknown. */
    val fraction: Double?,
    val detail: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val startedAtMillis: Long,
) {
    @Serializable
    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, MERGING, INDEXING_SEARCH, IMPORTING_GRAPH, PREPARING_CH, DOWNLOADING_TILES, STYLING, ACTIVATING, DONE, FAILED, CANCELLED }

    val isTerminal: Boolean get() = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED
}

/** What the rest of the app looks at: is there usable data, and is anything happening right now. */
data class OfflineStatus(
    val manifest: OfflineManifest,
    val job: JobProgress? = null,
    val lastError: String? = null,
) {
    val hasGraph: Boolean get() = manifest.graphBuiltAtMillis != null
    val hasSearch: Boolean get() = manifest.searchBuiltAtMillis != null
    val isBusy: Boolean get() = job != null && !job.isTerminal
    val hasTiles: Boolean get() = manifest.tilesBuiltAtMillis != null
    /** Everything the app needs to work with no network at all. */
    val isSelfContained: Boolean get() = hasGraph && hasSearch && hasTiles
    /** Regions downloaded but not yet part of the active graph: a rebuild is pending. */
    val staleRegionIds: List<String> get() = manifest.regions.map { it.region.id }.filter { it !in manifest.graphRegionIds }
}
