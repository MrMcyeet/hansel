package dev.hansel.offline

import android.content.Context
import dev.hansel.model.LatLng
import dev.hansel.osm.RegionCatalog
import dev.hansel.tiles.PmTilesExtractor
import dev.hansel.tiles.ProtomapsBuilds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import dev.hansel.osm.Region as CatalogRegion

/**
 * Main-process face of the offline data (DESIGN §4.2 "Map package UX"). Reads the manifest and the build process's
 * progress file, starts/cancels builds, answers "which region is here" and "what would this cost".
 */
class OfflineManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    val catalog: RegionCatalog = RegionCatalog,
) {
    val layout = OfflineLayout(context)
    private val _status = MutableStateFlow(load())
    val status: StateFlow<OfflineStatus> = _status

    /** Bumps whenever the active graph/tiles/search change, so engines and map styles reload. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    init {
        PackageBuildService.ensureChannel(context)
        scope.launch {
            var lastManifest = layout.manifest.lastModified()
            while (isActive) {
                val s = load()
                _status.value = s
                val m = layout.manifest.lastModified()
                if (m != lastManifest) { lastManifest = m; _generation.value++ }
                delay(if (s.isBusy) 1_000 else 5_000)
            }
        }
    }

    fun refresh() { _status.value = load() }

    private fun load(): OfflineStatus {
        val manifest = JsonFiles.read<OfflineManifest>(layout.manifest) ?: OfflineManifest()
        val job = JsonFiles.read<JobProgress>(layout.progressFile)
        // A stale QUEUED/RUNNING progress with no job file means the build process died; surface it as failed.
        val effectiveJob = if (job != null && !job.isTerminal && !layout.jobFile.isFile && System.currentTimeMillis() - job.startedAtMillis > 30_000)
            job.copy(phase = JobProgress.Phase.FAILED, detail = "Build process stopped unexpectedly (${job.phase.name.lowercase()}: ${job.detail})")
        else job
        return OfflineStatus(manifest, effectiveJob, effectiveJob?.takeIf { it.phase == JobProgress.Phase.FAILED }?.detail)
    }

    // ---- Regions ---------------------------------------------------------------------------------------------------

    fun regionAt(p: LatLng): CatalogRegion? = catalog.regionAt(p)
    fun region(id: String): CatalogRegion? = catalog.byId(id)
    fun installedRegionIds(): List<String> = _status.value.manifest.regions.map { it.region.id }

    /** The user's state plus its neighbours, in a sensible order, for the setup screen. */
    fun suggestedRegions(here: LatLng?): List<CatalogRegion> {
        val home = here?.let { regionAt(it) } ?: return emptyList()
        return listOf(home) + home.neighbours.mapNotNull { catalog.byId(it) }
    }

    data class Estimate(
        val downloadBytes: Long,
        val tilesBytes: Long?,
        /** Rough graph size: ~0.9× the PBF size after import plus CH; stated as an estimate in the UI. */
        val graphBytesApprox: Long,
        val freeBytes: Long,
        val minutesApprox: Int,
    )

    /** HEADs the extracts for exact sizes; tile size comes from resolving the PMTiles directories (a few range requests). */
    suspend fun estimate(regionIds: List<String>, tilesMaxZoom: Int = 15): Estimate = withContext(Dispatchers.IO) {
        val regions = regionIds.mapNotNull { catalog.byId(it) }
        var download = 0L
        for (r in regions) {
            val len = runCatching {
                http.newCall(Request.Builder().url(r.pbfUrl).head().build()).execute().use { it.header("Content-Length")?.toLongOrNull() }
            }.getOrNull()
            download += len ?: r.approxBytes ?: 150_000_000L
        }
        val tiles = runCatching {
            val build = ProtomapsBuilds.latestUrl { url -> http.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            val b = regions.map { it.bbox }
            val bbox = dev.hansel.model.BoundingBox(b.minOf { it.south }, b.minOf { it.west }, b.maxOf { it.north }, b.maxOf { it.east }).expandedBy(5_000.0)
            PmTilesExtractor.estimateBytes(build.url, bbox, tilesMaxZoom, http)
        }.getOrNull()
        val graph = (download * 0.9).toLong()
        // Import throughput on a flagship phone is on the order of 2–4 MB of PBF per second including CH; downloads
        // dominate on slow Wi-Fi. Clearly labelled "≈" in the UI.
        val minutes = ((download + (tiles ?: 0L)) / 2_500_000L / 60 + download / 3_000_000L / 60 + 3).toInt()
        Estimate(download, tiles, graph, layout.freeBytes(), minutes)
    }

    // ---- Builds ----------------------------------------------------------------------------------------------------

    fun install(regionIds: List<String>, protomapsKey: String, tilesMaxZoom: Int = 15, themes: List<String> = listOf("dark", "light")) {
        PackageBuildService.start(context, layout, OfflineJobRequest(regionIds, tilesMaxZoom, protomapsKey, themes))
        refresh()
    }

    /** Re-run with the installed set (after a failure, or to refresh extracts). */
    fun rebuild(protomapsKey: String, tilesMaxZoom: Int = 15, full: Boolean = false) {
        val ids = installedRegionIds()
        if (ids.isEmpty()) return
        PackageBuildService.start(context, layout, OfflineJobRequest(ids, tilesMaxZoom, protomapsKey, incremental = !full))
        refresh()
    }

    fun cancel() { PackageBuildService.cancel(context); refresh() }

    /** Drops a region's extract and marks the graph/tiles stale; the next build rebuilds without it. */
    suspend fun remove(regionId: String) = withContext(Dispatchers.IO) {
        val m = JsonFiles.read<OfflineManifest>(layout.manifest) ?: return@withContext
        layout.pbfFor(regionId).delete()
        File(layout.pbfFor(regionId).path + ".md5").delete()
        JsonFiles.write(layout.manifest, m.copy(regions = m.regions.filter { it.region.id != regionId }))
        refresh()
    }

    /** Deletes everything offline. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        cancel()
        layout.root.deleteRecursively()
        context.deleteDatabase(SearchIndexDb.NAME)
        refresh()
        _generation.value++
    }

    fun dismissJob() { layout.progressFile.delete(); refresh() }

    // ---- What the app consumes -------------------------------------------------------------------------------------

    fun activeGraphDir(): File? = layout.activeGraph.takeIf { it.isDirectory && _status.value.hasGraph }
    fun styleFile(theme: String): File? {
        val s = _status.value
        if (!s.hasTiles) return null
        val exact = layout.styleFile(theme)
        if (exact.isFile) return exact
        return s.manifest.styleThemes.firstOrNull()?.let { layout.styleFile(it) }?.takeIf { it.isFile }
    }
    fun bytesUsed(): Long = layout.bytesUsed()
}
