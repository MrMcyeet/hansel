package dev.hansel.navigation

import dev.hansel.model.Fix
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.routing.OrchestratedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class NavState {
    data object Idle : NavState()
    /** Routes computed, user picking one. */
    data class Previewing(val routes: List<Route>, val selectedIndex: Int) : NavState() {
        val selected: Route get() = routes[selectedIndex]
    }
    data class Navigating(
        val route: Route,
        val progress: RouteProgress?,
        val lastFix: Fix?,
        val rerouting: Boolean = false,
        val rerouteReason: String? = null,
        val startedAtMillis: Long,
        /** Alternatives still offered during guidance (DESIGN §8 "one-tap alternatives"). */
        val alternatives: List<Route> = emptyList(),
    ) : NavState()
    data class Arrived(val route: Route, val atMillis: Long) : NavState()
}

sealed class NavEvent {
    data class Announce(val announcement: Announcement) : NavEvent()
    data class Rerouted(val from: Route, val to: Route, val reason: String) : NavEvent()
    data class RerouteFailed(val reason: String) : NavEvent()
    data class Arrived(val route: Route) : NavEvent()
    data class OffRoute(val distanceMeters: Double) : NavEvent()
}

/** Supplied by the app: builds a reroute request (via the editor) and runs it through the orchestrator. */
fun interface Rerouter {
    suspend fun reroute(current: Route, position: Fix, progress: RouteProgress): OrchestratedResult?
}

/**
 * One session owns the active route, snapping, progress, rerouting and announcements (DESIGN §8). Phone UI and the
 * Android Auto screen are both projections of [state]. No Android dependencies, so it's unit-testable with canned fixes.
 */
class NavigationSession(
    private val scope: CoroutineScope,
    private val rerouter: Rerouter,
    private val units: DistanceUnits = DistanceUnits.IMPERIAL,
    private val minRerouteIntervalMillis: Long = 8_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state
    private val _events = MutableSharedFlow<NavEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NavEvent> = _events.asSharedFlow()

    private var tracker: RouteTracker? = null
    private val offRoute = OffRouteDetector()
    private val guidance = GuidanceScheduler(units)
    private var lastRerouteAt = Long.MIN_VALUE / 2
    private var rerouteJob: Job? = null
    /** The request the active route came from; kept so the app can show "custom route" and restore it. */
    var activeRequest: RouteRequest? = null
        private set

    fun preview(result: OrchestratedResult, selectedIndex: Int = 0) {
        activeRequest = result.request
        _state.value = NavState.Previewing(result.routes, selectedIndex.coerceIn(0, (result.routes.size - 1).coerceAtLeast(0)))
    }

    fun select(index: Int) {
        _state.update { s -> if (s is NavState.Previewing) s.copy(selectedIndex = index.coerceIn(0, s.routes.lastIndex)) else s }
    }

    fun start(route: Route? = null, alternatives: List<Route> = emptyList()) {
        val s = _state.value
        val r = route ?: (s as? NavState.Previewing)?.selected ?: return
        val alts = if (route == null && s is NavState.Previewing) s.routes.filter { it.id != r.id } else alternatives
        beginRoute(r, alts, startedAt = clock())
    }

    private fun beginRoute(route: Route, alternatives: List<Route>, startedAt: Long, resumeAlong: Double = 0.0) {
        tracker = RouteTracker(route).also { it.reset(resumeAlong) }
        offRoute.reset(); guidance.reset()
        _state.value = NavState.Navigating(route, null, null, startedAtMillis = startedAt, alternatives = alternatives)
    }

    /** Switch to one of the offered alternatives mid-trip. */
    fun switchTo(route: Route) {
        val s = _state.value as? NavState.Navigating ?: return
        beginRoute(route, (s.alternatives + s.route).filter { it.id != route.id }, s.startedAtMillis)
    }

    fun stop() {
        rerouteJob?.cancel(); tracker = null
        _state.value = NavState.Idle
    }

    fun snapshot(): NavigationSnapshot? {
        val s = _state.value as? NavState.Navigating ?: return null
        return NavigationSnapshot(s.route, s.progress?.distanceAlongMeters ?: 0.0, s.startedAtMillis)
    }

    fun restore(snapshot: NavigationSnapshot, request: RouteRequest?) {
        activeRequest = request
        beginRoute(snapshot.route, emptyList(), snapshot.startedAtMillis, resumeAlong = snapshot.distanceAlongMeters)
    }

    /** Feed every location fix here. Cheap; safe to call at 1 Hz. */
    fun onFix(fix: Fix) {
        val s = _state.value as? NavState.Navigating ?: return
        val t = tracker ?: return
        val progress = t.update(fix)
        _state.value = s.copy(progress = progress, lastFix = fix)

        if (progress.isNearArrival || (progress.distanceRemainingMeters < 25.0 && progress.offRouteDistanceMeters < 50.0)) {
            _state.value = NavState.Arrived(s.route, fix.timeMillis)
            _events.tryEmit(NavEvent.Arrived(s.route))
            return
        }
        guidance.next(progress, fix.speedMps)?.let { _events.tryEmit(NavEvent.Announce(it)) }

        if (!s.rerouting && offRoute.observe(fix, progress)) {
            _events.tryEmit(NavEvent.OffRoute(progress.offRouteDistanceMeters))
            requestReroute(fix, progress, "off route")
        }
    }

    fun forceReroute(reason: String = "requested") {
        val s = _state.value as? NavState.Navigating ?: return
        val fix = s.lastFix ?: return
        val p = s.progress ?: return
        lastRerouteAt = Long.MIN_VALUE / 2
        requestReroute(fix, p, reason)
    }

    private fun requestReroute(fix: Fix, progress: RouteProgress, reason: String) {
        val now = clock()
        if (now - lastRerouteAt < minRerouteIntervalMillis) return
        lastRerouteAt = now
        _state.update { s -> if (s is NavState.Navigating) s.copy(rerouting = true, rerouteReason = reason) else s }
        rerouteJob?.cancel()
        rerouteJob = scope.launch {
            val s = _state.value as? NavState.Navigating ?: return@launch
            val result = runCatching { rerouter.reroute(s.route, fix, progress) }.getOrNull()
            val newRoute = result?.routes?.firstOrNull()
            if (result == null || newRoute == null) {
                _state.update { st -> if (st is NavState.Navigating) st.copy(rerouting = false, rerouteReason = null) else st }
                _events.tryEmit(NavEvent.RerouteFailed(result?.error?.message ?: "reroute failed"))
                offRoute.reset()
                return@launch
            }
            activeRequest = result.request
            val old = s.route
            beginRoute(newRoute, result.routes.drop(1), s.startedAtMillis)
            _events.tryEmit(NavEvent.Rerouted(old, newRoute, reason))
        }
    }
}
