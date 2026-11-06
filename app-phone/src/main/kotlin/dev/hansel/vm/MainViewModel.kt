package dev.hansel.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hansel.AppGraph
import dev.hansel.location.NavigationForegroundService
import dev.hansel.map.CameraMode
import dev.hansel.map.MapOverlay
import dev.hansel.model.Fix
import dev.hansel.model.GeoMath
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import dev.hansel.model.Route
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingError
import dev.hansel.model.WaypointType
import dev.hansel.navigation.NavEvent
import dev.hansel.navigation.NavState
import dev.hansel.navigation.Rerouter
import dev.hansel.routing.OrchestratedResult
import dev.hansel.routing.editor.EditorState
import dev.hansel.routing.editor.RouteEditor
import dev.hansel.routing.editor.RouteStretch
import dev.hansel.storage.HanselSettings
import dev.hansel.storage.TripRecorder
import dev.hansel.storage.effectivePrimaryEngine
import dev.hansel.storage.routingOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context

/** What the preview/editor screens render. */
data class RoutingUi(
    val loading: Boolean = false,
    val result: OrchestratedResult? = null,
    val selectedRouteId: String? = null,
    val error: String? = null,
    val editor: EditorState? = null,
    /** Two taps on the route define a stretch for lock/avoid actions. */
    val stretchStart: Double? = null,
    val stretchEnd: Double? = null,
    val editMode: Boolean = false,
    val destination: Place? = null,
    val lastRerouteReason: String? = null,
) {
    val routes: List<Route> get() = result?.routes ?: emptyList()
    val selectedRoute: Route? get() = routes.firstOrNull { it.id == selectedRouteId } ?: routes.firstOrNull()
    val hasStretch: Boolean get() = stretchStart != null && stretchEnd != null
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(private val graph: AppGraph, private val appContext: Context) : ViewModel() {
    val settings: StateFlow<HanselSettings> = graph.settings
    val navState: StateFlow<NavState> = graph.session.state
    val position = MutableStateFlow<Fix?>(graph.locationSource.lastKnown)
    private val _ui = MutableStateFlow(RoutingUi())
    val ui: StateFlow<RoutingUi> = _ui
    private val _cameraMode = MutableStateFlow(CameraMode.CENTER_POSITION)
    val cameraMode: StateFlow<CameraMode> = _cameraMode
    private val _toasts = MutableStateFlow<String?>(null)
    val toasts: StateFlow<String?> = _toasts

    private val editorFlow = MutableStateFlow<RouteEditor?>(null)
    val editor: RouteEditor? get() = editorFlow.value
    private var routeJob: Job? = null
    private var activeRecordingRequestId: String? = null

    init {
        // Location → UI position (+ recorder). The foreground service feeds the session itself while navigating.
        viewModelScope.launch {
            graph.locationSource.fixes.collect { fix ->
                position.value = fix
                if (graph.tripRecorder.isRecording) graph.tripRecorder.record(fix)
            }
        }
        // Every editor revision re-routes (debounced so drags don't spam the server).
        viewModelScope.launch {
            editorFlow.filterNotNull()
                .flatMapLatest { ed -> ed.state.distinctUntilChangedBy { it.revision } }
                .debounce(250)
                .collectLatest { st ->
                    _ui.update { it.copy(editor = st) }
                    // adoptRerouted() bumps the revision too; while navigating the session already holds that
                    // result, so re-routing here would only double the server calls and the A/B log entries.
                    if (navState.value !is NavState.Navigating) route(st.request)
                }
        }
        // Session events → speech, editor sync, recorder outcome.
        viewModelScope.launch {
            graph.session.events.collect { ev ->
                when (ev) {
                    is NavEvent.Announce -> if (settings.value.voiceGuidance) graph.speaker.say(ev.announcement.text)
                    is NavEvent.Rerouted -> {
                        graph.session.activeRequest?.let { editor?.adoptRerouted(it) }
                        _ui.update { it.copy(lastRerouteReason = ev.reason) }
                        if (settings.value.voiceGuidance) graph.speaker.say("Rerouting")
                    }
                    is NavEvent.RerouteFailed -> _toasts.value = "Reroute failed: ${ev.reason}"
                    is NavEvent.OffRoute -> Unit
                    is NavEvent.Arrived -> onArrived(ev.route)
                }
            }
        }
        viewModelScope.launch { settings.collect { graph.speaker.enabled = it.voiceGuidance } }
        graph.rerouter = Rerouter { current, fix, progress ->
            val ed = editor ?: return@Rerouter null
            val req = ed.forReroute(current, fix.point, fix.bearing?.toDouble(), progress.distanceAlongMeters)
            val s = settings.value
            graph.orchestrator().route(req, s.effectivePrimaryEngine, s.comparisonMode, null)
        }
    }

    // ---- Destination / routing --------------------------------------------------------------------------------

    fun chooseDestination(place: Place) {
        val origin = position.value?.point ?: run { _toasts.value = "Waiting for GPS fix"; return }
        viewModelScope.launch { graph.recentPlaces.recordUse(place) }
        val ed = RouteEditor(RouteEditor.newRequest(origin, place, options = settings.value.routingOptions()))
        editorFlow.value = ed
        _ui.value = RoutingUi(loading = true, destination = place, editor = ed.state.value)
        _cameraMode.value = CameraMode.FIT_ROUTES
    }

    fun loadSavedRoute(request: RouteRequest) {
        val ed = RouteEditor(request)
        // Fresh origin: the saved template's origin is wherever the user was when they saved it.
        position.value?.point?.let { ed.setOrigin(it, "Current location") }
        editorFlow.value = ed
        val dest = request.destination.let { Place(it.id, it.name ?: "Saved destination", point = it.point, source = PlaceSource.SAVED) }
        _ui.value = RoutingUi(loading = true, destination = dest, editor = ed.state.value, editMode = true)
        _cameraMode.value = CameraMode.FIT_ROUTES
    }

    private fun route(request: RouteRequest) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val s = settings.value
            val req = request.copy(options = s.routingOptions())
            // Engines promise not to throw for routing failures, but a malformed server URL from Settings throws
            // IllegalArgumentException out of OkHttp before any of that; keep it on screen instead of crashing.
            val res = try {
                graph.orchestrator().route(req, s.effectivePrimaryEngine, s.comparisonMode, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = "Routing failed: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            val keepSelection = _ui.value.selectedRouteId?.let { id -> res.routes.firstOrNull { it.id == id }?.id }
            _ui.update { it.copy(loading = false, result = res, selectedRouteId = keepSelection ?: res.routes.firstOrNull()?.id, error = res.error?.let(::describe)) }
            if (res.isSuccess && navState.value !is NavState.Navigating) graph.session.preview(res, 0)
        }
    }

    private fun describe(e: RoutingError): String = when (e) {
        is RoutingError.Network -> "Network error: ${e.message}"
        is RoutingError.Unsupported -> "No road data installed yet. Open Offline data to download your state, or turn on Demo mode."
        is RoutingError.LockViolated -> "Locked road can't be honored: ${e.message}"
        else -> e.message
    }

    fun selectRoute(id: String) {
        _ui.update { it.copy(selectedRouteId = id) }
        val idx = _ui.value.routes.indexOfFirst { it.id == id }
        if (idx >= 0) graph.session.select(idx)
    }

    fun retry() { editor?.request?.let(::route) }

    // ---- Editing (DESIGN §5.1) --------------------------------------------------------------------------------

    fun setEditMode(on: Boolean) { _ui.update { it.copy(editMode = on, stretchStart = null, stretchEnd = null) } }
    fun undo() { editor?.undo() }
    fun redo() { editor?.redo() }
    fun resetShaping() { editor?.resetShaping() }

    fun addThroughPoint(p: LatLng) { editor?.addThroughPoint(p, _ui.value.selectedRoute) }
    fun dragShaping(grab: LatLng, drop: LatLng) { val r = _ui.value.selectedRoute ?: return; editor?.dragShapingPoint(r, grab, drop) }
    fun removeWaypoint(id: String) { editor?.removeWaypoint(id) }
    fun moveWaypoint(id: String, to: Int) { editor?.moveWaypoint(id, to) }
    fun toggleStop(id: String) {
        val wp = editor?.request?.waypoints?.firstOrNull { it.id == id } ?: return
        val next = if (wp.type == WaypointType.STOP || wp.type == WaypointType.STOP_THROUGH) WaypointType.THROUGH else WaypointType.STOP
        editor?.setWaypointType(id, next)
    }
    fun addStop(place: Place) { viewModelScope.launch { graph.recentPlaces.recordUse(place) }; editor?.addStop(place) }
    fun removeLock(id: String) { editor?.removeLock(id) }
    fun removeAvoidZone(id: String) { editor?.removeAvoidZone(id) }

    /** In edit mode, a tap on the selected route marks the start/end of a stretch. */
    fun onRouteTapped(routeId: String, at: LatLng?) {
        val u = _ui.value
        if (!u.editMode || at == null) { selectRoute(routeId); return }
        val r = u.selectedRoute ?: return
        if (routeId != r.id) { selectRoute(routeId); return }
        val along = GeoMath.projectOntoPolyline(at, r.geometry)?.alongMeters ?: return
        _ui.update {
            when {
                it.stretchStart == null -> it.copy(stretchStart = along)
                it.stretchEnd == null -> it.copy(stretchStart = minOf(it.stretchStart, along), stretchEnd = maxOf(it.stretchStart, along))
                else -> it.copy(stretchStart = along, stretchEnd = null)
            }
        }
    }
    fun clearStretch() { _ui.update { it.copy(stretchStart = null, stretchEnd = null) } }
    private fun stretch(): RouteStretch? { val u = _ui.value; return if (u.hasStretch) RouteStretch(u.stretchStart!!, u.stretchEnd!!) else null }
    fun preferStretch() { val r = _ui.value.selectedRoute ?: return; val s = stretch() ?: return; editor?.lockStretch(r, s); clearStretch() }
    fun avoidStretch() { val r = _ui.value.selectedRoute ?: return; val s = stretch() ?: return; editor?.avoidStretch(r, s); clearStretch() }
    fun keepUpToStretchEnd() { val r = _ui.value.selectedRoute ?: return; val s = stretch() ?: return; editor?.keepPortion(r, s.endAlongMeters); clearStretch() }

    fun saveCurrentRoute(name: String) {
        val req = editor?.request ?: return
        viewModelScope.launch { graph.savedRoutes.save(name, req); _toasts.value = "Saved \"$name\"" }
    }

    // ---- Navigation ----------------------------------------------------------------------------------------------

    fun startNavigation() {
        val u = _ui.value
        val route = u.selectedRoute ?: return
        val others = u.routes.filter { it.id != route.id }
        graph.session.start(route, others)
        if (dev.hansel.location.LocationPermissions.canStartNavigationService(appContext)) NavigationForegroundService.start(appContext)
        else _toasts.value = "Location permission missing: guidance runs only while the app is open"
        _cameraMode.value = CameraMode.FOLLOW
        graph.abLogger.markChosen(route.requestId, route)
        if (settings.value.recordTrips) viewModelScope.launch {
            activeRecordingRequestId = route.requestId
            graph.tripRecorder.start(requestId = route.requestId, routeId = route.id, engine = route.engine, source = TripRecorder.SOURCE_ACTIVE)
        }
        if (settings.value.voiceGuidance) graph.speaker.say("Starting navigation to ${u.destination?.name ?: "destination"}")
    }

    fun stopNavigation() {
        graph.session.stop()
        NavigationForegroundService.stop(appContext)
        _cameraMode.value = CameraMode.FIT_ROUTES
        viewModelScope.launch { graph.tripRecorder.stop() }
    }

    fun switchToAlternative(route: Route) { graph.session.switchTo(route); _ui.update { it.copy(selectedRouteId = route.id) } }
    fun forceReroute() { graph.session.forceReroute("requested") }
    fun recenter() { _cameraMode.value = if (navState.value is NavState.Navigating) CameraMode.FOLLOW else CameraMode.FIT_ROUTES }
    fun onUserMovedCamera() { _cameraMode.value = CameraMode.FREE }
    fun setCamera(mode: CameraMode) { _cameraMode.value = mode }

    private fun onArrived(route: Route) {
        // The session stays in Arrived (the foreground service lingers ~5 s on its own); the UI goes back to Home
        // instead of re-showing the finished route's preview with a stale "Start".
        routeJob?.cancel()
        editorFlow.value = null
        _ui.value = RoutingUi()
        _cameraMode.value = CameraMode.CENTER_POSITION
        viewModelScope.launch {
            val summary = graph.tripRecorder.stop()
            if (summary != null && activeRecordingRequestId != null) {
                graph.abLogger.markOutcome(activeRecordingRequestId!!, route, summary.durationSeconds, summary.trace)
            }
            activeRecordingRequestId = null
        }
        if (settings.value.voiceGuidance) graph.speaker.say("You have arrived")
    }

    fun clearRoute() {
        routeJob?.cancel()
        editorFlow.value = null
        _ui.value = RoutingUi()
        if (navState.value !is NavState.Navigating) graph.session.stop()
        _cameraMode.value = CameraMode.CENTER_POSITION
    }

    fun consumeToast() { _toasts.value = null }

    /** Map overlay for the current screen state. */
    fun overlay(): MapOverlay {
        val u = _ui.value
        val nav = navState.value
        val pos = position.value
        return when (nav) {
            is NavState.Navigating -> MapOverlay(
                routes = listOf(nav.route) + nav.alternatives, selectedRouteId = nav.route.id,
                waypoints = graph.session.activeRequest?.waypoints ?: emptyList(),
                locks = graph.session.activeRequest?.locks ?: emptyList(),
                avoidZones = graph.session.activeRequest?.avoidZones ?: emptyList(),
                position = pos, snapped = nav.progress?.snapped?.point,
            )
            else -> MapOverlay(
                routes = u.routes, selectedRouteId = u.selectedRoute?.id,
                waypoints = u.editor?.request?.waypoints ?: emptyList(),
                locks = u.editor?.request?.locks ?: emptyList(),
                avoidZones = u.editor?.request?.avoidZones ?: emptyList(),
                position = pos, destination = u.destination?.point,
            )
        }
    }

    class Factory(private val graph: AppGraph, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph, context.applicationContext) as T
    }
}
