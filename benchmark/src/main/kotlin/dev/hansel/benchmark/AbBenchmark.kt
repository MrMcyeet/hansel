package dev.hansel.benchmark

import dev.hansel.model.EngineId
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import dev.hansel.model.Route
import dev.hansel.routing.ComparisonMode
import dev.hansel.routing.OrchestratedResult
import dev.hansel.routing.RoutingObserver
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * One A/B observation (DESIGN §9, §11 Benchmark). Captures what each engine *predicted* for the same request, which
 * route the user actually chose, and later what actually happened. The unchosen engine's prediction is never treated
 * as an outcome; only the driven route gets `actualSeconds`.
 */
@Serializable
data class AbRecord(
    val requestId: String,
    val createdAtMillis: Long,
    val mode: ComparisonMode,
    val primaryEngine: EngineId,
    val shadowEngine: EngineId?,
    val primaryEtaSeconds: Double?,
    val primaryDistanceMeters: Double?,
    val primaryLatencyMillis: Long,
    val shadowEtaSeconds: Double?,
    val shadowDistanceMeters: Double?,
    val shadowLatencyMillis: Long?,
    /** Mutual overlap of the two engines' preferred routes. */
    val overlap: Double?,
    val isCustomShaped: Boolean,
    val modelVersion: String = "baseline",
    val mapRevision: String? = null,
    val trafficSnapshotMillis: Long? = null,
    // filled in later
    val chosenRouteId: String? = null,
    val chosenEngine: EngineId? = null,
    val chosenEtaSeconds: Double? = null,
    val actualSeconds: Double? = null,
    /** How much of the chosen route was actually driven (0..1), from the recorded trace. */
    val drivenOverlap: Double? = null,
    val completedAtMillis: Long? = null,
) {
    /** Signed ETA error for the driven route (positive = arrived later than predicted). */
    val etaErrorSeconds: Double? get() = if (actualSeconds != null && chosenEtaSeconds != null) actualSeconds - chosenEtaSeconds else null
}

/** Persistence boundary; the app implements it with Room. */
interface AbRecordStore {
    suspend fun upsert(record: AbRecord)
    suspend fun get(requestId: String): AbRecord?
    suspend fun all(limit: Int = 500): List<AbRecord>
}

class InMemoryAbRecordStore : AbRecordStore {
    private val map = LinkedHashMap<String, AbRecord>()
    override suspend fun upsert(record: AbRecord) { map[record.requestId] = record }
    override suspend fun get(requestId: String) = map[requestId]
    override suspend fun all(limit: Int) = map.values.toList().takeLast(limit)
}

/**
 * Shadow A/B logger: observes the orchestrator, then takes `markChosen` and `markOutcome` from the navigation layer.
 * Pure Kotlin; the app wires it with a coroutine scope and a Room-backed store.
 */
class AbShadowLogger(private val store: AbRecordStore, private val modelVersion: () -> String = { "baseline" },
                     private val launch: (suspend () -> Unit) -> Unit) : RoutingObserver {

    override fun onResult(result: OrchestratedResult) {
        val p = result.primaryRaw.primary
        val s = result.shadowRaw?.primary
        val rec = AbRecord(
            requestId = result.request.id, createdAtMillis = System.currentTimeMillis(), mode = result.mode,
            primaryEngine = result.primaryEngine, shadowEngine = result.shadowRaw?.engine,
            primaryEtaSeconds = p?.durationSeconds, primaryDistanceMeters = p?.distanceMeters, primaryLatencyMillis = result.primaryRaw.latencyMillis,
            shadowEtaSeconds = s?.durationSeconds, shadowDistanceMeters = s?.distanceMeters, shadowLatencyMillis = result.shadowRaw?.latencyMillis,
            overlap = result.discrepancy?.overlap, isCustomShaped = result.request.isCustomShaped, modelVersion = modelVersion(),
        )
        launch { store.upsert(rec) }
    }

    fun markChosen(requestId: String, route: Route) = launch {
        store.get(requestId)?.let { store.upsert(it.copy(chosenRouteId = route.id, chosenEngine = route.engine, chosenEtaSeconds = route.durationSeconds)) }
    }

    fun markOutcome(requestId: String, route: Route, actualSeconds: Double, drivenTrace: List<LatLng>) = launch {
        store.get(requestId)?.let {
            val overlap = if (drivenTrace.size >= 2) GeoMath.overlapFraction(route.geometry, drivenTrace, toleranceMeters = 40.0) else null
            store.upsert(it.copy(actualSeconds = actualSeconds, drivenOverlap = overlap, completedAtMillis = System.currentTimeMillis()))
        }
    }
}

/** DESIGN §6.6 / §9 scorecard: MAE, median, p90, bias and sample size per engine, from completed records only. */
@Serializable
data class EngineScore(
    val engine: EngineId,
    val samples: Int,
    val maeSeconds: Double,
    val medianAbsErrorSeconds: Double,
    val p90AbsErrorSeconds: Double,
    /** Mean signed error: positive means the engine is optimistic (trips take longer than predicted). */
    val biasSeconds: Double,
    val meanLatencyMillis: Double,
    val meanOverlapWithOther: Double?,
)

object Scorecard {
    fun compute(records: List<AbRecord>): List<EngineScore> {
        val completed = records.filter { it.etaErrorSeconds != null && (it.drivenOverlap ?: 1.0) >= 0.8 }
        return completed.groupBy { it.chosenEngine ?: it.primaryEngine }.map { (engine, recs) ->
            val errs = recs.map { it.etaErrorSeconds!! }
            val absErrs = errs.map { abs(it) }.sorted()
            val latencies = records.filter { it.primaryEngine == engine }.map { it.primaryLatencyMillis.toDouble() } +
                records.filter { it.shadowEngine == engine && it.shadowLatencyMillis != null }.map { it.shadowLatencyMillis!!.toDouble() }
            val overlaps = records.mapNotNull { it.overlap }
            EngineScore(
                engine, recs.size,
                maeSeconds = absErrs.average(),
                medianAbsErrorSeconds = percentile(absErrs, 0.5),
                p90AbsErrorSeconds = percentile(absErrs, 0.9),
                biasSeconds = errs.average(),
                meanLatencyMillis = if (latencies.isEmpty()) 0.0 else latencies.average(),
                meanOverlapWithOther = if (overlaps.isEmpty()) null else overlaps.average(),
            )
        }.sortedBy { it.maeSeconds }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt()
        return sorted[idx]
    }
}
