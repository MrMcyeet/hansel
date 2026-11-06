package dev.hansel.offline

import android.content.Context
import java.io.File

/**
 * Where self-contained data lives on the phone. Everything is under the app's external files dir so it is
 * removed with the app and visible to `adb` for sideloading/inspection (DESIGN §4.2, §13: no cloud).
 *
 * ```
 * <externalFiles>/hansel/
 *   osm/<regionId>.osm.pbf         downloaded Geofabrik extracts (+ .md5 sidecar, .part while downloading)
 *   osm/merged.osm.pbf             de-duplicated merge of every installed region (input to the importer)
 *   graphhopper/current/           the active GraphHopper graph folder (atomically swapped in from build/)
 *   graphhopper/build/             importer scratch; deleted on success or failure
 *   search/                        Room FTS database lives in the normal app database dir, not here
 *   manifest.json                  OfflineManifest: regions, versions, byte counts, build config hash
 * ```
 */
class OfflineLayout(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "hansel").apply { mkdirs() }
    val osmDir: File = File(root, "osm").apply { mkdirs() }
    val mergedPbf: File = File(osmDir, "merged.osm.pbf")
    val graphDir: File = File(root, "graphhopper").apply { mkdirs() }
    val activeGraph: File = File(graphDir, "current")
    val buildGraph: File = File(graphDir, "build")
    val manifest: File = File(root, "manifest.json")

    fun pbfFor(regionId: String): File = File(osmDir, "$regionId.osm.pbf")
    fun partFor(regionId: String): File = File(osmDir, "$regionId.osm.pbf.part")

    /** Free space on the volume holding [root], bytes. */
    fun freeBytes(): Long = root.usableSpace
}
