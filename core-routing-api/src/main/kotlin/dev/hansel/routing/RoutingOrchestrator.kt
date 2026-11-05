package dev.hansel.routing

import dev.hansel.model.EngineId
import dev.hansel.model.GeoMath
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.RoutingResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/** DESIGN §9: shadow mode computes both and navigates one; side-by-side pools both engines' candidates. */
@Serializable
enum class ComparisonMode { SINGLE, SHADOW, SIDE_BY_SIDE }

@Serializable
data class RouteDiscrepancy(
    val primaryEngine: EngineId,
    val shadowEngine: EngineId,
    /** Mutual geometric overlap of the two engines' preferred routes, 0..1. */
    val overlap: Double,
    val etaDeltaSeconds: Double,
    val distanceDeltaMeters: Double,
    val primaryLatencyMillis: Long,
    val shadowLatencyMillis: Long,
    val shadowFailed: Boolean = false,
)

data class OrchestratedResult(
    val request: RouteRequest,
    val mode: ComparisonMode,
    val primaryEngine: EngineId,
    /** The engine-independent set the UI shows: evaluated primary first, then de-duplicated alternatives. */
    val routes: List<Route>,
    val error: RoutingError?,
    val primaryRaw: RoutingResult,
    val shadowRaw: RoutingResult? = null,
    val discrepancy: RouteDiscrepancy? = null,
    val dropped: List<AlternativesEvaluator.Dropped> = emptyList(),
) {
    val isSuccess: Boolean get() = error == null && routes.isNotEmpty()
}

/** Observers (the A/B benchmark logger) see every orchestrated request. */
fun interface RoutingObserver { fun onResult(result: OrchestratedResult) }

/**
 * Single entry point for the app: picks engines, runs the corridor-lock controller above them, runs the shadow engine
 * in parallel on the very same request, and re-scores the alternatives through one evaluator.
 */
class RoutingOrchestrator(
    private val engines: Map<EngineId, RoutingEngine>,
    private val lockController: CorridorLockController = CorridorLockController(),
    private val evaluator: AlternativesEvaluator = AlternativesEvaluator(),
    private val observers: List<RoutingObserver> = emptyList(),
) {
    fun engine(id: EngineId): RoutingEngine? = engines[id]
    val availableEngines: Set<EngineId> get() = engines.keys

    suspend fun route(
        request: RouteRequest,
        primary: EngineId,
        mode: ComparisonMode = ComparisonMode.SINGLE,
        shadow: EngineId? = null,
    ): OrchestratedResult {
        val primaryEngine = engines[primary]
            ?: return failed(request, mode, primary, RoutingError.Unsupported("engine $primary not configured"))
        val shadowEngine = if (mode != ComparisonMode.SINGLE) shadow?.let { engines[it] } else null

        val (primaryRes, shadowRes) = coroutineScope {
            val p = async { lockController.route(primaryEngine, request) }
            val s = shadowEngine?.let { eng -> async { lockController.route(eng, request) } }
            p.await() to s?.await()
        }

        val pool = when (mode) {
            ComparisonMode.SIDE_BY_SIDE -> primaryRes.routes + (shadowRes?.routes ?: emptyList())
            else -> primaryRes.routes
        }
        val evaluated = evaluator.evaluate(pool)
        val routes = evaluated?.let { listOf(it.primary) + it.alternatives } ?: emptyList()
        val discrepancy = shadowRes?.let { s -> discrepancy(primaryRes, s) }
        val error = primaryRes.error ?: if (routes.isEmpty()) RoutingError.NoRoute() else null

        val out = OrchestratedResult(request, mode, primary, routes, error, primaryRes, shadowRes, discrepancy, evaluated?.dropped ?: emptyList())
        observers.forEach { runCatching { it.onResult(out) } }
        return out
    }

    private fun discrepancy(p: RoutingResult, s: RoutingResult): RouteDiscrepancy {
        val pr = p.primary; val sr = s.primary
        if (pr == null || sr == null) {
            return RouteDiscrepancy(p.engine, s.engine, 0.0, 0.0, 0.0, p.latencyMillis, s.latencyMillis, shadowFailed = sr == null)
        }
        val overlap = (GeoMath.overlapFraction(pr.geometry, sr.geometry) + GeoMath.overlapFraction(sr.geometry, pr.geometry)) / 2
        return RouteDiscrepancy(p.engine, s.engine, overlap, sr.durationSeconds - pr.durationSeconds,
            sr.distanceMeters - pr.distanceMeters, p.latencyMillis, s.latencyMillis)
    }

    private fun failed(request: RouteRequest, mode: ComparisonMode, engine: EngineId, error: RoutingError) =
        OrchestratedResult(request, mode, engine, emptyList(), error, RoutingResult(request.id, engine, emptyList(), error))
}
