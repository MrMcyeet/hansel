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
import dev.hansel.offline.OfflineManager
import dev.hansel.offline.OfflineSearch
import dev.hansel.routing.RoutingEngine
import dev.hansel.routing.RoutingOrchestrator
import dev.hansel.routing.fake.FakeRoutingEngine
import dev.hansel.routing.graphhopper.LocalGraphHopperEngine
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
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph (DESIGN §3): one navigation session, one orchestrator, one settings store, one
 * offline data manager. Everything here is process-wide; the Android Auto service and the foreground service reach
 * it through [NavigationHostRegistry]. Self-contained by design: routing, search and map tiles come from data built
 * on the phone by [OfflineManager]; the only network use is downloading that data (and the hosted map before it exists).
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
    /** Hot copy of settings so synchronous code paths never block; starts with defaults until DataStore answers. */
    val settings: StateFlow<HanselSettings> = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, HanselSettings())

    val db: HanselDatabase = HanselDatabase.build(app)
    val savedRoutes = SavedRoutesRepository(db)
    val recentPlaces = RecentPlacesRepository(db)
    val tripRecorder = TripRecorder(db, scope)
    val abStore = RoomAbRecordStore(db)
    val abLogger = AbShadowLogger(abStore, modelVersion = { "baseline" }, launch = { block -> scope.launch { block() } })

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    val offline = OfflineManager(app, http, scope)
    val search = OfflineSearch(app)
    val locationSource = FusedLocationSource(app, scope)
    val speaker = Speaker(app)

    /** The on-device engine is rebuilt whenever the active graph changes (offline generation bump). */
    @Volatile private var localEngine: LocalGraphHopperEngine? = null
    @Volatile private var localEngineDir: File? = null
    private val fake = FakeRoutingEngine(latencyMillis = 150)

    @Synchronized
    fun engines(): Map<EngineId, RoutingEngine> {
        val dir = offline.activeGraphDir()
        val engine = when {
            dir == null -> { localEngine = null; localEngineDir = null; null }
            dir != localEngineDir || localEngine == null -> LocalGraphHopperEngine(dir).also { localEngine = it; localEngineDir = dir }
            else -> localEngine
        }
        return buildMap {
            engine?.let { put(EngineId.GRAPHHOPPER, it) }
            put(EngineId.FAKE, fake)
        }
    }

    fun orchestrator(): RoutingOrchestrator = RoutingOrchestrator(engines(), observers = listOf(abLogger))

    /** Set by the view model once an editor exists; the session calls it when the driver leaves the route. */
    @Volatile var rerouter: Rerouter = Rerouter { _, _, _: RouteProgress -> null }

    val session: NavigationSession = NavigationSession(scope, rerouter = Rerouter { r, f, p -> rerouter.reroute(r, f, p) })

    init {
        NavigationHostRegistry.session = session
        NavigationHostRegistry.locationSource = locationSource
        scope.launch { settings.collect { NavigationHostRegistry.units = it.units } }
        // Single fix pump: exactly one onFix per fix while navigating, whoever started guidance (phone, car, restore).
        scope.launch(Dispatchers.Main) {
            session.state.map { it is NavState.Navigating }.distinctUntilChanged().collectLatest { navigating ->
                if (navigating) locationSource.fixes.collect { session.onFix(it) }
            }
        }
        // Drop the cached engine when a new graph is activated so the next request loads it.
        scope.launch { offline.generation.collect { synchronized(this@AppGraph) { localEngine = null; localEngineDir = null } } }
    }
}
