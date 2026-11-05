package dev.hansel.benchmark

import dev.hansel.model.EngineId
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.routing.ComparisonMode
import dev.hansel.routing.RoutingOrchestrator
import dev.hansel.routing.editor.RouteEditor
import dev.hansel.routing.fake.FakeRoutingEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbBenchmarkTest {
    @Test fun shadowRecordThenChosenThenOutcomeProducesScore() = runTest {
        val store = InMemoryAbRecordStore()
        val logger = AbShadowLogger(store, launch = { block -> runBlocking { block() } })
        val fake = FakeRoutingEngine()
        val orch = RoutingOrchestrator(mapOf(EngineId.FAKE to fake), observers = listOf(logger))
        val req = RouteEditor.newRequest(LatLng(32.2988, -90.1848), Place("n", "New Orleans", point = LatLng(29.9511, -90.0715)))
        val res = orch.route(req, EngineId.FAKE, ComparisonMode.SHADOW, EngineId.FAKE)
        val rec = assertNotNull(store.get(req.id))
        assertEquals(EngineId.FAKE, rec.shadowEngine)
        assertNotNull(rec.overlap)
        val chosen = res.routes.first()
        logger.markChosen(req.id, chosen)
        logger.markOutcome(req.id, chosen, actualSeconds = chosen.durationSeconds + 120.0, drivenTrace = chosen.geometry)
        val done = store.get(req.id)!!
        assertEquals(120.0, done.etaErrorSeconds!!, 1e-6)
        assertTrue(done.drivenOverlap!! > 0.99)
        val score = Scorecard.compute(store.all()).single()
        assertEquals(1, score.samples)
        assertEquals(120.0, score.biasSeconds, 1e-6)
    }

    @Test fun incompleteTripsAreExcludedFromScorecard() {
        val rec = AbRecord("r", 0, ComparisonMode.SINGLE, EngineId.VALHALLA, null, 100.0, 1000.0, 10, null, null, null, null, false,
            chosenEngine = EngineId.VALHALLA, chosenEtaSeconds = 100.0, actualSeconds = 130.0, drivenOverlap = 0.3)
        assertTrue(Scorecard.compute(listOf(rec)).isEmpty(), "a trip that left the route is not evidence about the ETA")
    }
}
