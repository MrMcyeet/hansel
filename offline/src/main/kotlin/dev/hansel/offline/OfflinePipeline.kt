package dev.hansel.offline

import android.content.Context
import dev.hansel.model.BoundingBox
import dev.hansel.osm.PbfMerger
import dev.hansel.osm.RegionCatalog
import dev.hansel.osm.SearchFeature
import dev.hansel.osm.SearchFeatureExtractor
import dev.hansel.osm.SearchFeatureSink
import dev.hansel.routing.graphhopper.GhProfile
import dev.hansel.routing.graphhopper.GraphImporter
import dev.hansel.tiles.OfflineAssets
import dev.hansel.tiles.OfflineStyle
import dev.hansel.tiles.PmTilesExtractor
import dev.hansel.tiles.ProtomapsBuilds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import dev.hansel.osm.Region as CatalogRegion

/**
 * The on-device data build (DESIGN §4, Phase 3), run inside [PackageBuildService]:
 *
 * 1. download each region's Geofabrik extract (resumable, MD5-verified)
 * 2. merge the installed extracts into one PBF (GraphHopper reads exactly one file)
 * 3. stream the merged PBF into the search index
 * 4. import the routing graph (+ CH) into a scratch folder
 * 5. extract the regions' bbox from Protomaps' planet PMTiles with range requests; fetch fonts/sprites/styles once
 * 6. localize the style JSON and swap graph + tiles into place atomically; write the manifest
 *
 * Every phase reports through [progress] (persisted by the service), is cancellable between units of work, and
 * is skipped when its outputs are already current and the job is incremental.
 */
class OfflinePipeline(
    private val context: Context,
    private val layout: OfflineLayout,
    private val http: OkHttpClient,
    private val catalog: RegionCatalog = RegionCatalog,
) {
    private val startedAt = System.currentTimeMillis()

    suspend fun run(request: OfflineJobRequest, progress: (JobProgress) -> Unit): OfflineManifest {
        var manifest = JsonFiles.read<OfflineManifest>(layout.manifest) ?: OfflineManifest()
        fun report(phase: JobProgress.Phase, detail: String, fraction: Double? = null, done: Long = 0, total: Long = 0) =
            progress(JobProgress(phase, fraction, detail, done, total, startedAt))

        try {
            // ---- 1. download -------------------------------------------------------------------------------------
            val downloader = Downloader(http, layout.osmDir)
            val wanted = (manifest.regions.map { it.region.id } + request.regionIds).distinct()
            val regions = wanted.mapNotNull { id -> catalog.byId(id) }
            require(regions.isNotEmpty()) { "no known regions in ${request.regionIds}" }
            val installed = ArrayList<InstalledRegion>()
            regions.forEachIndexed { i, r ->
                coroutineContext.ensureActive()
                val region = r.toDownloadRegion()
                report(JobProgress.Phase.DOWNLOADING, "${r.name} (${i + 1}/${regions.size})", i.toDouble() / regions.size)
                val rec = downloader.download(region) { p ->
                    report(JobProgress.Phase.DOWNLOADING, "${r.name}: ${mb(p.bytesDone)} / ${mb(p.bytesTotal)} MB · ${mb(p.bytesPerSecond.toLong())} MB/s",
                        if (p.bytesTotal > 0) p.bytesDone.toDouble() / p.bytesTotal else null, p.bytesDone, p.bytesTotal)
                }
                installed += rec
                manifest = manifest.copy(regions = installed + manifest.regions.filter { m -> installed.none { it.region.id == m.region.id } })
                JsonFiles.write(layout.manifest, manifest)
            }
            val regionIds = installed.map { it.region.id }
            val graphCurrent = request.incremental && manifest.graphRegionIds.toSet() == regionIds.toSet() &&
                manifest.graphBuildConfig == GhProfile.buildConfigId() && layout.activeGraph.isDirectory

            // ---- 2. merge ------------------------------------------------------------------------------------------
            val pbfs = installed.map { layout.pbfFor(it.region.id) }
            val sourcePbf: File
            if (!graphCurrent || !manifest.hasSearch()) {
                sourcePbf = if (pbfs.size == 1) pbfs.single() else {
                    report(JobProgress.Phase.MERGING, "Merging ${pbfs.size} extracts")
                    val merged = layout.mergedPbf
                    val tmp = File(merged.path + ".tmp")
                    PbfMerger.merge(pbfs, tmp) { p ->
                        report(JobProgress.Phase.MERGING, "Merging: ${p.nodes} nodes · ${p.ways} ways · ${mb(p.bytesWritten)} MB written", p.fraction)
                    }
                    tmp.renameTo(merged) || error("could not finalize merged PBF")
                    merged
                }
            } else sourcePbf = pbfs.firstOrNull() ?: layout.mergedPbf

            // ---- 3. search index -----------------------------------------------------------------------------------
            if (!request.incremental || !manifest.hasSearch() || manifest.graphRegionIds.toSet() != regionIds.toSet()) {
                report(JobProgress.Phase.INDEXING_SEARCH, "Indexing places and streets")
                val rows = buildSearchIndex(sourcePbf) { n -> report(JobProgress.Phase.INDEXING_SEARCH, "Indexed ${"%,d".format(n)} places") }
                manifest = manifest.copy(searchIndexRows = rows, searchBuiltAtMillis = System.currentTimeMillis())
                JsonFiles.write(layout.manifest, manifest)
            }

            // ---- 4. graph ------------------------------------------------------------------------------------------
            if (!graphCurrent) {
                layout.buildGraph.deleteRecursively()
                report(JobProgress.Phase.IMPORTING_GRAPH, "Reading roads from ${sourcePbf.name} (${mb(sourcePbf.length())} MB)")
                val result = GraphImporter.import(sourcePbf, layout.buildGraph) { p ->
                    val phase = if (p.stage == GraphImporter.Stage.PREPARING_CH) JobProgress.Phase.PREPARING_CH else JobProgress.Phase.IMPORTING_GRAPH
                    report(phase, "${p.stage.name.lowercase().replace('_', ' ')} · ${p.detail} · ${p.elapsedMillis / 1000}s")
                }
                report(JobProgress.Phase.ACTIVATING, "Activating graph")
                swapDir(layout.buildGraph, layout.activeGraph)
                manifest = manifest.copy(
                    graphRegionIds = regionIds, graphBuiltAtMillis = System.currentTimeMillis(),
                    graphBytes = result.bytes, graphBuildConfig = result.buildConfigId,
                )
                JsonFiles.write(layout.manifest, manifest)
            }

            // ---- 5. tiles + assets ---------------------------------------------------------------------------------
            val bbox = unionBbox(regions).expandedBy(5_000.0)
            val tilesCurrent = request.incremental && layout.activeTiles.isFile && manifest.tilesBbox?.let { b ->
                b[0] <= bbox.south && b[1] <= bbox.west && b[2] >= bbox.north && b[3] >= bbox.east
            } == true && (manifest.tilesMaxZoom ?: 0) >= request.tilesMaxZoom
            if (!tilesCurrent) {
                report(JobProgress.Phase.DOWNLOADING_TILES, "Finding the latest Protomaps build")
                val build = ProtomapsBuilds.latestUrl { url -> fetchText(url) }
                report(JobProgress.Phase.DOWNLOADING_TILES, "Map tiles for ${regions.joinToString { it.name }} (z≤${request.tilesMaxZoom})")
                PmTilesExtractor.extract(build.url, bbox, request.tilesMaxZoom, layout.buildTiles, http) { p ->
                    report(JobProgress.Phase.DOWNLOADING_TILES, "Tiles: ${mb(p.bytesDone)} / ${mb(p.bytesTotal)} MB · ${p.tilesDone}/${p.tilesTotal}",
                        if (p.bytesTotal > 0) p.bytesDone.toDouble() / p.bytesTotal else null, p.bytesDone, p.bytesTotal)
                }
                report(JobProgress.Phase.ACTIVATING, "Activating tiles")
                layout.activeTiles.delete()
                layout.buildTiles.renameTo(layout.activeTiles) || error("could not activate tiles")
                File(layout.buildTiles.path + ".state").delete()
                manifest = manifest.copy(
                    tilesBbox = listOf(bbox.south, bbox.west, bbox.north, bbox.east), tilesMaxZoom = request.tilesMaxZoom,
                    tilesBytes = layout.activeTiles.length(), tilesBuiltAtMillis = System.currentTimeMillis(), tilesSource = build.url,
                )
                JsonFiles.write(layout.manifest, manifest)
            }

            // ---- 6. styles: hosted style JSON + fonts/sprites, localized to the files above ------------------------
            report(JobProgress.Phase.STYLING, "Fonts, sprites and map style")
            val themes = ArrayList<String>()
            for (theme in request.styleThemes) {
                coroutineContext.ensureActive()
                val hosted = layout.hostedStyleFile(theme)
                if (!hosted.isFile) {
                    val json = runCatching { fetchText(OfflineStyle.hostedStyleUrl(theme, request.protomapsKey)) }.getOrNull()
                    if (json == null) { continue } else hosted.writeText(json)
                }
                val styleJson = hosted.readText()
                val plan = OfflineAssets.plan(styleJson)
                OfflineAssets.download(plan, http, layout.assetsDir) { done, total ->
                    report(JobProgress.Phase.STYLING, "$theme: assets $done/$total", if (total > 0) done.toDouble() / total else null)
                }
                layout.styleFile(theme).writeText(OfflineStyle.localize(styleJson, layout.activeTiles.absolutePath, layout.assetsDir))
                themes += theme
            }
            manifest = manifest.copy(styleThemes = themes.ifEmpty { manifest.styleThemes })
            JsonFiles.write(layout.manifest, manifest)

            // Scratch cleanup: the merged file is only needed until the next build; keep per-region extracts for incremental adds.
            report(JobProgress.Phase.DONE, "Ready: ${regions.joinToString { it.name }}", 1.0)
            return manifest
        } catch (e: CancellationException) {
            report(JobProgress.Phase.CANCELLED, "Cancelled")
            throw e
        } catch (t: Throwable) {
            report(JobProgress.Phase.FAILED, (t.message ?: t::class.simpleName ?: "failed").take(300))
            throw t
        }
    }

    private suspend fun buildSearchIndex(pbf: File, onCount: (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        val db = SearchIndexDb.build(context)
        try {
            val dao = db.places()
            dao.clear()
            val batch = ArrayList<PlaceRow>(2000)
            var count = 0L
            val sink = SearchFeatureSink { f ->
                batch += f.toRow()
                if (batch.size >= 2000) {
                    runBlocking { dao.insertAll(batch) }
                    count += batch.size; batch.clear(); onCount(count)
                }
            }
            SearchFeatureExtractor().extract(pbf, sink) { }
            if (batch.isNotEmpty()) { dao.insertAll(batch); count += batch.size }
            onCount(count)
            count
        } finally { db.close() }
    }

    private fun SearchFeature.toRow() = PlaceRow(
        osmId = "$osmType/$osmId", name = name,
        street = listOfNotNull(houseNumber, street).joinToString(" ").ifBlank { null },
        city = city, state = null, postcode = postcode, kind = category, lat = lat, lon = lon,
        rank = (10 - rank).coerceAtLeast(0),
    )

    private fun unionBbox(regions: List<CatalogRegion>): BoundingBox {
        val b = regions.map { it.bbox }
        return BoundingBox(b.minOf { it.south }, b.minOf { it.west }, b.maxOf { it.north }, b.maxOf { it.east })
    }

    private fun swapDir(from: File, to: File) {
        val old = File(to.path + ".old")
        old.deleteRecursively()
        if (to.exists() && !to.renameTo(old)) error("could not retire ${to.name}")
        if (!from.renameTo(to)) { old.renameTo(to); error("could not activate ${from.name}") }
        old.deleteRecursively()
    }

    private fun fetchText(url: String): String {
        val client = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            return r.body.string()
        }
    }

    private fun mb(bytes: Long) = bytes / 1_048_576
    private fun OfflineManifest.hasSearch() = searchBuiltAtMillis != null

    companion object {
        fun CatalogRegion.toDownloadRegion() = Region(id = id, name = name, url = pbfUrl, approxBytes = approxBytes ?: 0L)
    }
}
