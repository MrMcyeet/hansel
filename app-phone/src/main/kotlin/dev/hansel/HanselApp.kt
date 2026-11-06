package dev.hansel

import android.app.Application
import dev.hansel.benchmark.AbShadowLogger
import dev.hansel.guidance.Speaker
import dev.hansel.location.FusedLocationSource
import dev.hansel.model.EngineId
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry
import dev.hansel.navigation.NavigationSession
import dev.hansel.navigation.RouteProgress
import dev.hansel.navigation.Rerouter
import dev.hansel.routing.RoutingEngine
import dev.hansel.routing.RoutingOrchestrator
import dev.hansel.routing.fake.FakeRoutingEngine
import dev.hansel.routing.graphhopper.GraphHopperEngine
import dev.hansel.routing.valhalla.ValhallaEngine
import dev.hansel.search.Geocoder
import dev.hansel.search.NominatimGeocoder
import dev.hansel.storage.HanselDatabase
import dev.hansel.storage.HanselSettings
import dev.hansel.storage.RecentPlacesRepository
import dev.hansel.storage.RoomAbRecordStore
import dev.hansel.storage.SavedRoutesRepository
import dev.hansel.storage.SettingsStore
import dev.hansel.storage.TripRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph (DESIGN §3): one navigation session, one orchestrator, one settings store.
 * Everything below is process-wide; the Android Auto service and the foreground service reach it through
 * [NavigationHostRegistry].
 */
class HanselApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        instance = this
    }

    companion object {
        lateinit var instance: HanselApp
            private set
    }
}

class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(app)
    /** Hot copy of settings so synchronous code paths (engine selection, units) never block. */
    val settings: StateFlow<HanselSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { firstSettings() })
    private suspend fun firstSettings(): HanselSettings = HanselSettings()

    val db: HanselDatabase = HanselDatabase.build(app)
    val savedRoutes = SavedRoutesRepository(db)
    val recentPlaces = RecentPlacesRepository(db)
    val tripRecorder = TripRecorder(db, scope)
    val abStore = RoomAbRecordStore(db)
    val abLogger = AbShadowLogger(abStore, modelVersion = { "baseline" }, launch = { block -> scope.launch { block() } })

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    val locationSource = FusedLocationSource(app, scope)
    val speaker = Speaker(app)

    /** Engines are rebuilt when the server URLs change; the cache is keyed by URL so settings edits take effect immediately. */
    private val engineCache = HashMap<String, RoutingEngine>()
    @Synchronized
    fun engines(): Map<EngineId, RoutingEngine> {
        val s = settings.value
        val v = engineCache.getOrPut("valhalla:${s.valhallaUrl}") { ValhallaEngine(s.valhallaUrl.trimEnd('/'), http) }
        val g = engineCache.getOrPut("graphhopper:${s.graphhopperUrl}") { GraphHopperEngine(s.graphhopperUrl.trimEnd('/'), http) }
        val f = engineCache.getOrPut("fake") { FakeRoutingEngine(latencyMillis = 150) }
        return mapOf(EngineId.VALHALLA to v, EngineId.GRAPHHOPPER to g, EngineId.FAKE to f)
    }

    fun orchestrator(): RoutingOrchestrator = RoutingOrchestrator(engines(), observers = listOf(abLogger))

    fun geocoder(): Geocoder = NominatimGeocoder(settings.value.geocoderUrl.trimEnd('/'), http)

    /** Set by the view model once an editor exists; the session calls it when the driver leaves the route. */
    @Volatile var rerouter: Rerouter = Rerouter { _, _, _: RouteProgress -> null }

    val session: NavigationSession = NavigationSession(scope, rerouter = Rerouter { r, f, p -> rerouter.reroute(r, f, p) })

    init {
        NavigationHostRegistry.session = session
        NavigationHostRegistry.locationSource = locationSource
        scope.launch { settings.collect { NavigationHostRegistry.units = it.units } }
        // The one and only fix pump into the session. It lives here, not in the view model (dies with the activity)
        // or the foreground service (may never start: Android 14+ FGS-location start restrictions, missing
        // permission, guidance started from the car screen), so every fix reaches the session exactly once for as
        // long as the process lives. GPS is only held while a route is actually being navigated; the UI and the
        // recorder keep their own subscriptions.
        scope.launch(Dispatchers.Main) {
            session.state.map { it is NavState.Navigating }.distinctUntilChanged().collectLatest { navigating ->
                if (navigating) locationSource.fixes.collect { session.onFix(it) }
            }
        }
    }
}
