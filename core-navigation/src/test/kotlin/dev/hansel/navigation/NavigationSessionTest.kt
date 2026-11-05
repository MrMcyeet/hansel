package dev.hansel.navigation

import dev.hansel.model.EngineId
import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.Route
import dev.hansel.routing.ComparisonMode
import dev.hansel.routing.OrchestratedResult
import dev.hansel.routing.RoutingOrchestrator
import dev.hansel.routing.editor.RouteEditor
import dev.hansel.routing.fake.FakeRoutingEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionTest {
    private val origin = LatLng(31.3271, -89.2903)
    private val dest = Place("d", "Gulfport", point = LatLng(30.3674, -89.0928))
    private val engine = FakeRoutingEngine()
    private val orch = RoutingOrchestrator(mapOf(EngineId.FAKE to engine))

    private suspend fun route(): OrchestratedResult = orch.route(RouteEditor.newRequest(origin, dest), EngineId.FAKE, ComparisonMode.SINGLE)

    private fun fixesAlong(route: Route, fromAlong: Double, toAlong: Double, stepMeters: Double, t0: Long, speed: Float = 25f, offsetMeters: Double = 0.0): List<Fix> {
        val cum = GeoMath.cumulativeDistances(route.geometry)
        val out = ArrayList<Fix>()
        var d = fromAlong; var t = t0
        while (d <= toAlong) {
            var p = GeoMath.pointAlong(route.geometry, d, cum)
            val next = GeoMath.pointAlong(route.geometry, (d + 10).coerceAtMost(cum.last()), cum)
            val bearing = p.bearingTo(next)
            if (offsetMeters > 0) p = GeoMath.destination(p, (bearing + 90) % 360, offsetMeters)
            out += Fix(p, t, 5f, speed, bearing.toFloat())
            d += stepMeters; t += (stepMeters / speed * 1000).toLong()
        }
        return out
    }

    @Test fun progressAdvancesAndArrives() = runTest {
        val session = NavigationSession(this, rerouter = { _, _, _ -> null }, clock = { testScheduler.currentTime })
        val res = route()
        session.preview(res); session.start()
        val r = (session.state.value as NavState.Navigating).route
        val fixes = fixesAlong(r, 0.0, r.distanceMeters, 500.0, 0L)
        val events = ArrayList<NavEvent>()
        val job = launch { session.events.toList(events) }
        testScheduler.runCurrent()
        var lastFrac = -1.0
        for (f in fixes) {
            session.onFix(f)
            val s = session.state.value
            if (s is NavState.Navigating) {
                val p = s.progress!!
                assertTrue(p.fractionComplete >= lastFrac - 1e-9, "progress must not go backwards")
                lastFrac = p.fractionComplete
                assertTrue(p.offRouteDistanceMeters < 5.0)
            }
        }
        // final fix at destination
        session.onFix(Fix(r.geometry.last(), 10_000_000L, 5f, 0f, null))
        advanceUntilIdle()
        assertIs<NavState.Arrived>(session.state.value)
        assertTrue(events.any { it is NavEvent.Announce }, "expected at least one announcement")
        assertTrue(events.any { it is NavEvent.Arrived })
        job.cancel()
    }

    @Test fun offRouteTriggersRerouteWithPreservedDestination() = runTest {
        var rerouteCalls = 0
        val editor = RouteEditor(RouteEditor.newRequest(origin, dest))
        val session = NavigationSession(this, rerouter = { current, fix, progress ->
            rerouteCalls++
            val req = editor.forReroute(current, fix.point, fix.bearing?.toDouble(), progress.distanceAlongMeters)
            orch.route(req, EngineId.FAKE)
        }, clock = { testScheduler.currentTime })
        session.preview(route()); session.start()
        val r = (session.state.value as NavState.Navigating).route
        // Drive 2 km on-route, then veer 400 m off for several fixes.
        fixesAlong(r, 0.0, 2000.0, 250.0, 0L).forEach { session.onFix(it) }
        fixesAlong(r, 2250.0, 4000.0, 250.0, 100_000L, offsetMeters = 400.0).forEach { session.onFix(it) }
        advanceUntilIdle()
        assertEquals(1, rerouteCalls, "exactly one reroute expected (debounced)")
        val s = assertIs<NavState.Navigating>(session.state.value)
        assertTrue(s.route.id != r.id, "route should have been replaced")
        assertEquals(dest.name, session.activeRequest!!.destination.name)
        assertEquals("Current location", session.activeRequest!!.origin.name)
        assertTrue(!s.rerouting)
    }

    @Test fun snapshotRestoresMidRoute() = runTest {
        val session = NavigationSession(this, rerouter = { _, _, _ -> null }, clock = { testScheduler.currentTime })
        session.preview(route()); session.start()
        val r = (session.state.value as NavState.Navigating).route
        fixesAlong(r, 0.0, 30_000.0, 1000.0, 0L).forEach { session.onFix(it) }
        val snap = session.snapshot()!!
        assertTrue(snap.distanceAlongMeters > 29_000)
        val s2 = NavigationSession(this, rerouter = { _, _, _ -> null }, clock = { testScheduler.currentTime })
        s2.restore(snap, session.activeRequest)
        s2.onFix(fixesAlong(r, 31_000.0, 31_000.0, 1.0, 0L).first())
        val p = (s2.state.value as NavState.Navigating).progress!!
        assertTrue(p.distanceAlongMeters > 30_000, "restored session should continue from the snapshot, got ${p.distanceAlongMeters}")
    }

    @Test fun guidanceStagesFireOnceEach() {
        val sched = GuidanceScheduler()
        val m = dev.hansel.model.Maneuver(dev.hansel.model.ManeuverType.TURN_LEFT, "Turn left onto Hardy St", verbalPreTransition = "In 1 mile, turn left onto Hardy Street",
            distanceMeters = 500.0, durationSeconds = 30.0, beginShapeIndex = 1, endShapeIndex = 2)
        val arrive = dev.hansel.model.Maneuver(dev.hansel.model.ManeuverType.ARRIVE, "Arrive", distanceMeters = 0.0, durationSeconds = 0.0, beginShapeIndex = 2, endShapeIndex = 2)
        val geometry = listOf(LatLng(30.0, -89.0), LatLng(30.02, -89.0), LatLng(30.02, -88.99))
        val depart = m.copy(type = dev.hansel.model.ManeuverType.DEPART, instruction = "Head north", verbalPreTransition = null, beginShapeIndex = 0, endShapeIndex = 1, distanceMeters = 2200.0)
        val route = Route("r", "q", EngineId.FAKE, geometry, listOf(dev.hansel.model.RouteLeg(0, 2, 2700.0, 150.0, listOf(depart, m, arrive))), 2700.0, 150.0)
        val tracker = RouteTracker(route)
        val texts = ArrayList<String>()
        for (d in listOf(100.0, 800.0, 1500.0, 1950.0, 2170.0, 2190.0)) {
            val fix = Fix(GeoMath.pointAlong(geometry, d), (d * 100).toLong(), 5f, 20f)
            val p = tracker.update(fix)
            sched.next(p, 20f)?.let { texts += it.text }
        }
        assertTrue(texts.any { it.startsWith("In ") && it.contains("turn left") }, "got $texts")
        assertTrue(texts.last().startsWith("Turn left") || texts.last().startsWith("turn left"), "got $texts")
        assertEquals(texts.size, texts.distinct().size)
    }

    @Test fun unitsFormatting() {
        assertEquals("0.3 mi", DistanceUnits.IMPERIAL.display(500.0))
        assertEquals("200 ft", DistanceUnits.IMPERIAL.display(61.0))
        assertEquals("half a mile", DistanceUnits.IMPERIAL.spoken(800.0))
        assertEquals("1 h 5 min", DistanceUnits.IMPERIAL.eta(3900.0))
    }
}
