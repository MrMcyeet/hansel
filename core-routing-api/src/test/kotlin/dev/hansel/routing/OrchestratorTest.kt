package dev.hansel.routing

import dev.hansel.model.EngineId
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingResult
import dev.hansel.model.WaypointType
import dev.hansel.routing.editor.RouteEditor
import dev.hansel.routing.fake.FakeRoutingEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestratorTest {
    private val request = RouteEditor.newRequest(LatLng(32.2988, -90.1848), Place("n", "New Orleans", point = LatLng(29.9511, -90.0715)))

    /** Wraps the fake so it reports a different EngineId; lets us test shadow comparisons without a server. */
    private class Renamed(private val inner: RoutingEngine, override val id: EngineId, private val slow: Double = 1.0, through: Boolean = true) : RoutingEngine {
        override val capabilities = inner.capabilities.copy(throughPoints = through)
        override suspend fun healthCheck() = inner.healthCheck()
        override suspend fun route(request: RouteRequest): RoutingResult {
            val r = inner.route(request)
            return r.copy(engine = id, routes = r.routes.map { it.copy(engine = id, durationSeconds = it.durationSeconds * slow) })
        }
    }

    @Test fun shadowModeRunsBothAndReportsDiscrepancy() = runTest {
        val v = Renamed(FakeRoutingEngine(), EngineId.VALHALLA)
        val g = Renamed(FakeRoutingEngine(), EngineId.GRAPHHOPPER, slow = 1.2)
        val seen = ArrayList<OrchestratedResult>()
        val orch = RoutingOrchestrator(mapOf(EngineId.VALHALLA to v, EngineId.GRAPHHOPPER to g), observers = listOf(RoutingObserver { seen += it }))
        val res = orch.route(request, EngineId.VALHALLA, ComparisonMode.SHADOW, EngineId.GRAPHHOPPER)
        assertTrue(res.isSuccess)
        assertEquals(EngineId.VALHALLA, res.routes.first().engine)
        val d = assertNotNull(res.discrepancy)
        assertTrue(d.overlap > 0.95, "same fake geometry should overlap, got ${d.overlap}")
        assertTrue(d.etaDeltaSeconds > 0)
        assertEquals(1, seen.size)
    }

    @Test fun sideBySidePoolsAndDedupes() = runTest {
        val v = Renamed(FakeRoutingEngine(), EngineId.VALHALLA)
        val g = Renamed(FakeRoutingEngine(), EngineId.GRAPHHOPPER, slow = 1.05)
        val orch = RoutingOrchestrator(mapOf(EngineId.VALHALLA to v, EngineId.GRAPHHOPPER to g))
        val res = orch.route(request, EngineId.VALHALLA, ComparisonMode.SIDE_BY_SIDE, EngineId.GRAPHHOPPER)
        // Fake produces primary + 2 alternatives per engine with identical geometry across engines -> 3 survive.
        assertEquals(3, res.routes.size, "routes: ${res.routes.map { it.engine to it.alternativeIndex }}, dropped: ${res.dropped.map { it.reason }}")
        assertTrue(res.dropped.size == 3)
        assertTrue(res.routes.drop(1).all { it.label != null })
    }

    @Test fun missingEngineIsAnErrorNotAnException() = runTest {
        val orch = RoutingOrchestrator(emptyMap())
        val res = orch.route(request, EngineId.VALHALLA)
        assertTrue(res.error is RoutingError.Unsupported)
    }

    @Test fun locksAreStitchedWhenEngineLacksThroughPoints() = runTest {
        val fake = FakeRoutingEngine()
        val noThrough = Renamed(fake, EngineId.GRAPHHOPPER, through = false)
        val ed = RouteEditor(request)
        val base = fake.route(request).primary!!
        ed.lockStretch(base, dev.hansel.routing.editor.RouteStretch(10_000.0, 20_000.0), "I-55")
        val res = CorridorLockController().route(noThrough, ed.request)
        assertTrue(res.isSuccess, "got ${res.error}")
        val r: Route = res.primary!!
        assertTrue(r.warnings.any { it.contains("stitched") })
        assertEquals(1, r.legs.size, "no intermediate stops -> single leg")
        assertEquals(dev.hansel.model.ManeuverType.DEPART, r.maneuvers.first().type)
        assertTrue(r.maneuvers.last().type.isArrival)
        assertEquals(1, r.maneuvers.count { it.type.isArrival }, "seams must not duplicate arrivals")
        // shape indices must be monotonic and within geometry
        var last = -1
        for (m in r.maneuvers) { assertTrue(m.beginShapeIndex >= last && m.endShapeIndex < r.geometry.size); last = m.beginShapeIndex }
    }

    @Test fun validatorFlagsMissedLock() = runTest {
        val fake = FakeRoutingEngine()
        val base = fake.route(request).primary!!
        val ed = RouteEditor(request)
        // Lock anchors far off any route the fake can produce
        ed.load(ed.request.copy(locks = listOf(dev.hansel.model.CorridorLock("L", listOf(LatLng(33.5, -88.0), LatLng(33.6, -88.0)), "nowhere"))))
        val violations = RouteValidator().validate(ed.request, base)
        assertTrue(violations.any { it.error is RoutingError.LockViolated })
        assertTrue(ed.request.waypoints.all { it.type == WaypointType.STOP })
    }
}
