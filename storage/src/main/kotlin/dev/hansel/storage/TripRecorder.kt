package dev.hansel.storage

import dev.hansel.model.EngineId
import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import dev.hansel.model.Ids
import dev.hansel.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a finished recording looked like, computed from the in-memory trace so it needs no DB round trip. */
data class TripSummary(
    val tripId: String,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val durationSeconds: Double,
    /** Length of the raw persisted trace, meters. Not snapped to the route. */
    val distanceMeters: Double,
    val sampleCount: Int,
    val trace: List<LatLng>,
    val requestId: String?,
    val routeId: String?,
)

/**
 * Records raw GPS traces per trip (DESIGN §6.1: never clamp speed; §12: adaptive sampling, bounded retention; §13:
 * per-trip exclusion and deletion). One active trip at a time. [record] is cheap and non-suspending so it can be
 * called straight from a location collector; writes are batched and flushed on [scope].
 */
class TripRecorder(
    private val db: HanselDatabase,
    private val scope: CoroutineScope,
    private val policy: SamplingPolicy = SamplingPolicy.DEFAULT,
    private val batchSize: Int = 10,
    private val flushIntervalMillis: Long = 10_000,
    /** Oldest finished trips beyond this many are pruned when a trip stops. */
    private val maxRetainedTrips: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SOURCE_ACTIVE = "active"
        const val SOURCE_PASSIVE = "passive"
    }

    private class Active(
        val id: String,
        val startAt: Long,
        val requestId: String?,
        val routeId: String?,
    ) {
        var lastPersisted: Fix? = null
        val trace = ArrayList<LatLng>()
        var distanceMeters = 0.0
        var sampleCount = 0
        val pending = ArrayList<GpsSampleEntity>()
        var timer: Job? = null
    }

    private val lock = Any()
    private val flushMutex = Mutex()
    @Volatile private var active: Active? = null

    private val dao get() = db.trips()

    val isRecording: Boolean get() = active != null
    val activeTripId: String? get() = active?.id

    /** Starts a trip. If one is already active it is stopped first. Returns the new trip id. */
    suspend fun start(
        requestId: String? = null,
        routeId: String? = null,
        engine: EngineId? = null,
        source: String = SOURCE_ACTIVE,
    ): String {
        if (active != null) stop()
        val id = Ids.newId("trip")
        val now = clock()
        dao.insert(
            TripEntity(
                id = id, startAtMillis = now, endAtMillis = null, requestId = requestId, routeId = routeId,
                engine = engine?.name, source = source, excluded = false, sampleCount = 0,
            ),
        )
        val a = Active(id, now, requestId, routeId)
        a.timer = scope.launch {
            while (isActive) {
                delay(flushIntervalMillis)
                flush()
            }
        }
        synchronized(lock) { active = a }
        return id
    }

    /** Feed every fix; the policy decides whether it becomes a row. Mock fixes are recorded too (flagged by provider). */
    fun record(fix: Fix) {
        var needsFlush = false
        synchronized(lock) {
            val a = active ?: return
            if (!policy.shouldPersist(a.lastPersisted, fix)) return
            a.trace.lastOrNull()?.let { a.distanceMeters += GeoMath.haversineMeters(it, fix.point) }
            a.trace += fix.point
            a.lastPersisted = fix
            a.sampleCount++
            a.pending += GpsSampleEntity(
                tripId = a.id, atMillis = fix.timeMillis, lat = fix.point.lat, lon = fix.point.lon,
                accuracyM = fix.accuracyMeters, speedMps = fix.speedMps, bearing = fix.bearing,
                provider = if (fix.isMock) "mock:${fix.provider ?: "?"}" else fix.provider,
            )
            needsFlush = a.pending.size >= batchSize
        }
        if (needsFlush) scope.launch { flush() }
    }

    /** Writes any buffered samples. Safe to call concurrently; batches are drained under a mutex. */
    suspend fun flush() {
        flushMutex.withLock {
            val batch: List<GpsSampleEntity> = synchronized(lock) {
                val a = active ?: return
                if (a.pending.isEmpty()) return
                ArrayList(a.pending).also { a.pending.clear() }
            }
            dao.insertSamples(batch)
        }
    }

    /** Ends the active trip, flushes, and returns its summary (null if nothing was recording). */
    suspend fun stop(): TripSummary? {
        val a = synchronized(lock) { active.also { active = null } } ?: return null
        a.timer?.cancel()
        // Drain whatever is left; `flush()` only sees `active`, so write directly.
        val tail: List<GpsSampleEntity> = synchronized(lock) { ArrayList(a.pending).also { a.pending.clear() } }
        flushMutex.withLock { if (tail.isNotEmpty()) dao.insertSamples(tail) }
        val end = clock()
        dao.finish(a.id, end, a.sampleCount)
        dao.pruneOlderThanNewest(maxRetainedTrips)
        return TripSummary(
            tripId = a.id, startAtMillis = a.startAt, endAtMillis = end,
            durationSeconds = (end - a.startAt) / 1000.0, distanceMeters = a.distanceMeters,
            sampleCount = a.sampleCount, trace = a.trace.toList(), requestId = a.requestId, routeId = a.routeId,
        )
    }

    fun trips(): Flow<List<TripEntity>> = dao.observeAll()
    suspend fun trip(tripId: String): TripEntity? = dao.get(tripId)
    suspend fun samples(tripId: String): List<GpsSampleEntity> = dao.samplesForTrip(tripId)
    suspend fun trace(tripId: String): List<LatLng> = dao.samplesForTrip(tripId).map { LatLng(it.lat, it.lon) }

    /** Keeps the data but marks it off-limits for learning (DESIGN §13 per-trip exclusions). */
    suspend fun exclude(tripId: String, excluded: Boolean = true) = dao.setExcluded(tripId, excluded)

    suspend fun delete(tripId: String) {
        if (active?.id == tripId) stop()
        dao.deleteTrip(tripId)
    }

    suspend fun deleteAll() {
        stop()
        dao.deleteAll()
    }

    /** Count of all stored samples across trips, for the privacy/storage screen. */
    fun totalSamples(): Flow<Long> = dao.observeSampleCount()

    /** GPX 1.1 export of one trip's raw trace. Export files are sensitive (DESIGN §13); the caller decides where it goes. */
    suspend fun exportGpx(tripId: String): String {
        val trip = dao.get(tripId)
        val points = dao.samplesForTrip(tripId).map {
            GpxPoint(atMillis = it.atMillis, lat = it.lat, lon = it.lon, speedMps = it.speedMps, bearing = it.bearing, accuracyMeters = it.accuracyM)
        }
        return GpxWriter.write(name = "Hansel trip $tripId", points = points, startAtMillis = trip?.startAtMillis)
    }
}
