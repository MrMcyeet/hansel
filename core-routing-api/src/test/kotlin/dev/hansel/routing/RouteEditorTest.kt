package dev.hansel.routing

import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.WaypointType
import dev.hansel.routing.editor.RouteEditor
import dev.hansel.routing.editor.RouteStretch
import dev.hansel.routing.fake.FakeRoutingEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteEditorTest {
    private val hattiesburg = LatLng(31.3271, -89.2903)
    private val gulfport = Place("p1", "Gulfport", point = LatLng(30.3674, -89.0928))
    private val engine = FakeRoutingEngine()

    private fun editor() = RouteEditor(RouteEditor.newRequest(hattiesburg, gulfport))

    @Test fun throughPointIsInsertedInRouteOrder() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        // A point 75% of the way, then one 25% of the way: the 25% one must end up first.
        val p75 = route.geometry[(route.geometry.size * 0.75).toInt()]
        val p25 = route.geometry[(route.geometry.size * 0.25).toInt()]
        ed.addThroughPoint(p75, route)
        val route2 = engine.route(ed.request).primary!!
        ed.addThroughPoint(p25, route2)
        val mid = ed.request.intermediate
        assertEquals(2, mid.size)
        assertTrue(mid[0].point.distanceTo(p25) < 1.0)
        assertTrue(mid[1].point.distanceTo(p75) < 1.0)
        assertTrue(mid.all { it.type == WaypointType.THROUGH && it.isShaping })
        assertTrue(ed.state.value.isCustom)
    }

    @Test fun undoRedoRoundTrip() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        ed.addThroughPoint(route.geometry[5], route)
        assertEquals(1, ed.request.intermediate.size)
        assertTrue(ed.undo())
        assertEquals(0, ed.request.intermediate.size)
        assertTrue(ed.redo())
        assertEquals(1, ed.request.intermediate.size)
        assertFalse(ed.redo())
    }

    @Test fun dragMovesExistingShapingPointInsteadOfAddingOne() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        val wp = ed.addThroughPoint(route.geometry[10], route)
        val route2 = engine.route(ed.request).primary!!
        val dropped = LatLng(wp.point.lat + 0.002, wp.point.lon)
        ed.dragShapingPoint(route2, grabPoint = wp.point, dropPoint = dropped)
        assertEquals(1, ed.request.intermediate.size)
        assertTrue(ed.request.intermediate[0].point.distanceTo(dropped) < 1.0)
    }

    @Test fun lockStretchCreatesOrderedAnchorsAndValidatorAccepts() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        val lock = ed.lockStretch(route, RouteStretch(5_000.0, 15_000.0), label = "US-49")
        assertTrue(lock.anchors.size >= 2)
        val ctrl = CorridorLockController()
        val res = ctrl.route(engine, ed.request)
        assertTrue(res.isSuccess, "expected success, got ${res.error}")
        assertNotNull(res.primary)
        assertTrue(ed.request.locks.single().id == lock.id)
    }

    @Test fun lockExpansionPlacesAnchorsAsThroughPointsInOrder() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        ed.lockStretch(route, RouteStretch(20_000.0, 30_000.0))
        val expanded = CorridorLockController().expandLocksToWaypoints(ed.request)
        assertTrue(expanded.locks.isEmpty())
        val mid = expanded.intermediate
        assertTrue(mid.size >= 2)
        assertTrue(mid.all { it.type == WaypointType.THROUGH })
        // ordered by distance from origin
        val d = mid.map { it.point.distanceTo(hattiesburg) }
        assertEquals(d, d.sorted())
    }

    @Test fun avoidStretchForcesFakeEngineOntoAlternative() = runTest {
        val ed = editor()
        val res0 = engine.route(ed.request)
        val primary = res0.primary!!
        ed.avoidStretch(primary, RouteStretch(40_000.0, 50_000.0), bufferMeters = 30.0)
        val res = CorridorLockController().route(engine, ed.request)
        // The fake engine rejects candidates crossing a zone; the straight primary must be gone, an alternative survives.
        assertTrue(res.isSuccess, "expected an alternative to survive, got ${res.error}")
        assertTrue(res.routes.none { it.alternativeIndex == 0 })
    }

    @Test fun rerouteDropsPassedShapingPointsKeepsStops() = runTest {
        val ed = editor()
        val route = engine.route(ed.request).primary!!
        val early = ed.addThroughPoint(route.geometry[3], route)
        val route2 = engine.route(ed.request).primary!!
        val late = ed.addThroughPoint(route2.geometry[route2.geometry.size - 4], route2)
        ed.addStop(Place("s", "Wiggins", point = LatLng(30.8585, -89.1356)), index = 1)
        val route3 = engine.route(ed.request).primary!!
        val cum = dev.hansel.model.GeoMath.cumulativeDistances(route3.geometry)
        val passed = cum.last() * 0.75
        val pos = dev.hansel.model.GeoMath.pointAlong(route3.geometry, passed, cum)
        val rr = ed.forReroute(route3, pos, 180.0, passed)
        assertEquals("Current location", rr.origin.name)
        assertTrue(rr.waypoints.none { it.id == early.id }, "early shaping point should be dropped")
        assertTrue(rr.waypoints.any { it.id == late.id }, "late shaping point should be kept")
        // Wiggins sits near the midpoint; at 75% along it has been visited and must be dropped.
        assertTrue(rr.waypoints.none { it.name == "Wiggins" }, "a passed stop must not be revisited on reroute")
        assertEquals(gulfport.name, rr.destination.name)
    }

    @Test fun stopToggleAndRemove() = runTest {
        val ed = editor()
        ed.addStop(Place("s", "Wiggins", point = LatLng(30.8585, -89.1356)))
        val id = ed.request.intermediate.single().id
        ed.setWaypointType(id, WaypointType.THROUGH)
        assertEquals(WaypointType.THROUGH, ed.request.intermediate.single().type)
        ed.removeWaypoint(id)
        assertTrue(ed.request.intermediate.isEmpty())
        assertEquals(3, ed.state.value.past.size)
    }
}
