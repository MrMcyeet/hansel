package dev.hansel.offline

import android.content.Context
import java.io.File

/**
 * Where self-contained data lives on the phone. Everything is under the app's external files dir so it is removed
 * with the app and visible to `adb` for inspection (DESIGN §4.2, §13: no cloud).
 *
 * ```
 * <externalFiles>/hansel/
 *   osm/<regionId>.osm.pbf         downloaded Geofabrik extracts (+ .md5 sidecar, .part while downloading)
 *   osm/merged.osm.pbf             de-duplicated merge of every installed region (input to importer + indexer)
 *   graphhopper/current/           the active GraphHopper graph folder (atomically swapped in from build/)
 *   graphhopper/build/             importer scratch; deleted on success or failure
 *   tiles/current.pmtiles          the active map tiles for the installed regions' bbox
 *   tiles/build.pmtiles            extractor scratch (+ .state for resume)
 *   assets/fonts|sprites|styles    glyph ranges, sprites, the original hosted style JSON per theme
 *   style/<theme>.json             localized MapLibre style pointing at the files above
 *   manifest.json                  OfflineManifest
 *   job.json / progress.json       the running job's request and progress (written by the build process)
 * ```
 * The Room search index lives in the normal app database dir (shared by both processes).
 */
class OfflineLayout(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "hansel").apply { mkdirs() }
    val osmDir: File = File(root, "osm").apply { mkdirs() }
    val mergedPbf: File = File(osmDir, "merged.osm.pbf")
    val graphDir: File = File(root, "graphhopper").apply { mkdirs() }
    val activeGraph: File = File(graphDir, "current")
    val buildGraph: File = File(graphDir, "build")
    val tilesDir: File = File(root, "tiles").apply { mkdirs() }
    val activeTiles: File = File(tilesDir, "current.pmtiles")
    val buildTiles: File = File(tilesDir, "build.pmtiles")
    val assetsDir: File = File(root, "assets").apply { mkdirs() }
    val hostedStylesDir: File = File(assetsDir, "styles").apply { mkdirs() }
    val styleDir: File = File(root, "style").apply { mkdirs() }
    val manifest: File = File(root, "manifest.json")
    val jobFile: File = File(root, "job.json")
    val progressFile: File = File(root, "progress.json")

    fun pbfFor(regionId: String): File = File(osmDir, "$regionId.osm.pbf")
    fun styleFile(theme: String): File = File(styleDir, "$theme.json")
    fun hostedStyleFile(theme: String): File = File(hostedStylesDir, "$theme.json")

    /** Free space on the volume holding [root], bytes. */
    fun freeBytes(): Long = root.usableSpace

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
