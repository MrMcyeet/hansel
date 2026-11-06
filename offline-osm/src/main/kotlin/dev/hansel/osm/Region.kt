package dev.hansel.osm

import dev.hansel.model.BoundingBox
import dev.hansel.model.LatLng

/** A downloadable Geofabrik extract. */
data class Region(
    /** Geofabrik id, e.g. "us/mississippi", "europe/germany/bayern"-style ids are "bayern" with parent "germany". */
    val id: String,
    val name: String,
    /** Two-letter USPS code for US states/DC/PR; null for everything else. */
    val postal: String?,
    /** e.g. https://download.geofabrik.de/north-america/us/mississippi-latest.osm.pbf */
    val pbfUrl: String,
    /** Geofabrik publishes `<pbf>.md5` next to every extract. */
    val md5Url: String,
    /** Null when the catalog has no geometry for the region (Geofabrik index entries). */
    val bbox: BoundingBox?,
    /** Outer rings only (holes are not needed for state outlines); empty when unknown. */
    val polygons: List<List<LatLng>>,
    /** Ids of regions sharing a land/water boundary, for "also download neighbours" prompts. */
    val neighbours: List<String>,
    /** Geofabrik parent id ("north-america" for US states, "europe" for countries); null for top-level continents. */
    val parent: String? = null,
    /** ISO 3166-1 alpha-2 / 3166-2 codes from the Geofabrik index when known. */
    val isoCodes: List<String> = emptyList(),
) {
    val isUsState: Boolean get() = id.startsWith("us/")
}
