package dev.hansel.routing.graphhopper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds a GraphHopper graph folder from an OSM file on the phone, using exactly [GhProfile] so that
 * [LocalGraphHopperEngine] can `load()` it later (GraphHopper refuses a graph whose stored profile hash differs).
 *
 * ## Memory
 * With `graph.dataaccess.default_type=MMAP` (the default here) the graph itself, the pillar-node coordinates
 * (`tmp_pillar_info`), the location index and the CH shortcuts live in memory-mapped files, not on the Java heap.
 * What stays on-heap during import is the OSM-node-id → graph-node map (`OSMNodeData.idsByOsmNodeIds`, a
 * `GHLongLongBTree(maxLeafEntries = 200, bytesPerValue = 5)`): 8-byte key + 5-byte value = 13 bytes per entry, in
 * leaf arrays grown by ×1.7 up to 200 entries, so **budget ≈ 15–20 bytes of heap per OSM node that belongs to a kept
 * highway way** (every node of every way that survives `import.osm.ignored_highways`, not every node in the file).
 * A whole US state like Mississippi (~5–8 M highway nodes) therefore needs roughly 100–160 MB of heap for that map
 * alone, plus the way/relation buffers of the reader (tens of MB) and the CH preparation's own priority queues. The
 * app should run the import in a `largeHeap` process and warn above ~10 M highway nodes (≈ 200 MB).
 * `RAM_STORE` additionally puts the whole graph on the heap and is only meant for JVM tests.
 */
object GraphImporter {
    enum class Stage { READING_OSM, BUILDING_GRAPH, SUBNETWORKS, PREPARING_CH, FLUSHING, DONE }

    data class Progress(val stage: Stage, val detail: String, val elapsedMillis: Long)

    data class ImportResult(
        val nodes: Int,
        val edges: Int,
        /** Total size of [graphDir] after the import. */
        val bytes: Long,
        val elapsedMillis: Long,
        /** [GhProfile.buildConfigId] of the configuration that built this graph. */
        val buildConfigId: String,
    )

    /** How often the ticker reports elapsed time and folder size while GraphHopper is busy inside one stage. */
    const val TICK_MILLIS = 2_000L

    /**
     * Deletes [graphDir], imports [osmFile] (`.osm.pbf`, or `.osm`/`.osm.xml` for tests) into it and prepares CH.
     * Runs on [Dispatchers.IO]; cancelling the coroutine interrupts the import thread (GraphHopper checks interrupts
     * only between stages, so the actual stop can lag) and leaves a half-written folder the caller must discard.
     *
     * @param dataAccess `MMAP` (default, off-heap) or `RAM_STORE` (tests).
     * @param strictInterpreter fail instead of falling back to Janino when the profile model is not interpretable.
     * @param minNetworkSize see [GhProfile.config]; tests with tiny maps pass 0.
     * @param progress stage changes plus a heartbeat every [TICK_MILLIS] with the elapsed time and current folder size.
     */
    suspend fun import(
        osmFile: File,
        graphDir: File,
        dataAccess: String = "MMAP",
        strictInterpreter: Boolean = false,
        minNetworkSize: Int = GhProfile.MIN_NETWORK_SIZE,
        progress: (Progress) -> Unit = {},
    ): ImportResult = coroutineScope {
        require(osmFile.isFile) { "OSM file not found: ${osmFile.absolutePath}" }
        val t0 = System.currentTimeMillis()
        val stage = AtomicReference(Stage.READING_OSM)
        fun report(detail: String) = progress(Progress(stage.get(), detail, System.currentTimeMillis() - t0))

        val ticker = launchTicker(graphDir, t0, stage, progress)
        try {
            withContext(Dispatchers.IO) {
                if (graphDir.exists()) graphDir.deleteRecursively()
                graphDir.mkdirs()
                report("starting import of ${osmFile.name} (${osmFile.length() / 1_048_576} MB)")
                val hopper = GhProfile.newGraphHopper(graphDir, osmFile, dataAccess, strictInterpreter, minNetworkSize) { s ->
                    stage.set(s)
                    progress(Progress(s, s.name.lowercase().replace('_', ' '), System.currentTimeMillis() - t0))
                }
                val (nodes, edges) = runInterruptible {
                    try {
                        hopper.importOrLoad()
                        hopper.baseGraph.nodes to hopper.baseGraph.edges
                    } finally {
                        hopper.close()
                    }
                }
                stage.set(Stage.DONE)
                val bytes = folderSize(graphDir)
                val elapsed = System.currentTimeMillis() - t0
                progress(Progress(Stage.DONE, "$nodes nodes, $edges edges, ${bytes / 1_048_576} MB in ${elapsed / 1000} s", elapsed))
                ImportResult(nodes, edges, bytes, elapsed, GhProfile.buildConfigId())
            }
        } finally {
            ticker.cancel()
        }
    }

    private fun CoroutineScope.launchTicker(graphDir: File, t0: Long, stage: AtomicReference<Stage>, progress: (Progress) -> Unit) =
        launch(Dispatchers.IO) {
            while (isActive) {
                delay(TICK_MILLIS)
                val s = stage.get()
                if (s == Stage.DONE) break
                val elapsed = System.currentTimeMillis() - t0
                progress(Progress(s, "${elapsed / 1000} s elapsed · ${folderSize(graphDir) / 1_048_576} MB on disk", elapsed))
            }
        }

    fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
