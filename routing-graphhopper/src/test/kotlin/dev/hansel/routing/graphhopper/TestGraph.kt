package dev.hansel.routing.graphhopper

import dev.hansel.model.LatLng
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** Imports `src/test/resources/grid.osm` once per JVM with the strict (Janino-free) interpreter. */
object TestGraph {
    const val LAT0 = 30.3
    const val LON0 = -89.1
    const val DLAT = 0.0018
    const val DLON = 0.00208

    /** Grid node (row south→north, col west→east). */
    fun node(row: Int, col: Int) = LatLng(LAT0 + row * DLAT, LON0 + col * DLON)

    val osmFile: File by lazy {
        val dir = Files.createTempDirectory("hansel-osm").toFile().apply { deleteOnExit() }
        val f = File(dir, "grid.osm")
        val res = TestGraph::class.java.getResourceAsStream("/grid.osm") ?: error("grid.osm test resource missing")
        res.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    }

    val graphDir: File by lazy { Files.createTempDirectory("hansel-graph").toFile().apply { deleteOnExit() } }

    val progress = ArrayList<GraphImporter.Progress>()

    val result: GraphImporter.ImportResult by lazy {
        runBlocking {
            GraphImporter.import(osmFile, graphDir, dataAccess = "RAM_STORE", strictInterpreter = true, minNetworkSize = 0) { progress += it }
        }
    }

    /** A fresh strict engine over the shared graph. */
    fun engine(flexibleMaxStraightLineMeters: Double = LocalGraphHopperEngine.DEFAULT_FLEXIBLE_MAX_STRAIGHT_LINE_METERS): LocalGraphHopperEngine {
        result // ensure imported
        return LocalGraphHopperEngine(graphDir, strictInterpreter = true, flexibleMaxStraightLineMeters = flexibleMaxStraightLineMeters)
    }
}
