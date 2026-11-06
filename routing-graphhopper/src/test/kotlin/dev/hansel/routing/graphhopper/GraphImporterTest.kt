package dev.hansel.routing.graphhopper

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphImporterTest {
    @Test
    fun importsGridAndReportsStages() {
        val r = TestGraph.result
        assertTrue(r.nodes > 0, "nodes: ${r.nodes}")
        assertTrue(r.edges > 0, "edges: ${r.edges}")
        assertTrue(r.bytes > 0, "bytes written")
        assertTrue(r.elapsedMillis >= 0)
        val stages = TestGraph.progress.map { it.stage }
        assertTrue(GraphImporter.Stage.READING_OSM in stages, "stages: $stages")
        assertTrue(GraphImporter.Stage.PREPARING_CH in stages, "stages: $stages")
        assertEquals(GraphImporter.Stage.DONE, stages.last())
        assertTrue(TestGraph.graphDir.listFiles().orEmpty().any { it.name == "properties" }, "GraphHopper properties file")
    }

    @Test
    fun buildConfigIdIsStableAndMatchesProfile() {
        assertEquals(GhProfile.buildConfigId(), TestGraph.result.buildConfigId)
        assertEquals(GhProfile.buildConfigId(), GhProfile.buildConfigId())
        assertTrue(TestGraph.result.buildConfigId.startsWith("gh11."), TestGraph.result.buildConfigId)
        assertTrue("profile=" in TestGraph.result.buildConfigId)
    }

    @Test
    fun reloadsWithEngineUsingTheSameProfileHash() = runBlocking {
        val engine = TestGraph.engine()
        assertTrue(!engine.isLoaded)
        val health = engine.healthCheck()
        assertTrue(health.reachable, health.detail)
        assertTrue(engine.isLoaded)
        assertTrue(engine.loadMillis >= 0)
        assertTrue("nodes" in health.detail && "edges" in health.detail, health.detail)
        assertTrue(engine.reload(), "reload")
        engine.close()
        assertTrue(!engine.isLoaded)
    }

    @Test
    fun missingGraphIsAnErrorNotAnException() = runBlocking {
        val engine = LocalGraphHopperEngine(java.io.File(TestGraph.graphDir.parentFile, "does-not-exist"), strictInterpreter = true)
        val health = engine.healthCheck()
        assertTrue(!health.reachable)
        assertTrue("no offline graph installed" in health.detail, health.detail)
        val result = engine.route(dev.hansel.model.RouteRequest("r", listOf(
            dev.hansel.model.Waypoint("a", TestGraph.node(0, 0)), dev.hansel.model.Waypoint("b", TestGraph.node(1, 1)))))
        assertTrue(result.error is dev.hansel.model.RoutingError.Engine, "${result.error}")
        assertTrue("no offline graph installed" in result.error!!.message)
    }
}
