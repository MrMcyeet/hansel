package dev.hansel.storage

import dev.hansel.model.EngineId
import dev.hansel.model.EtaProfile
import dev.hansel.model.HighwayPreference
import dev.hansel.model.RoutingOptions
import dev.hansel.navigation.DistanceUnits
import dev.hansel.routing.ComparisonMode

/** Everything the user can change in Settings plus a few restore-after-process-death blobs. Defaults = dev LAN setup. */
data class HanselSettings(
    val valhallaUrl: String = "http://192.168.1.10:8002",
    val graphhopperUrl: String = "http://192.168.1.10:8989",
    val geocoderUrl: String = "http://192.168.1.10:8080",
    /** Self-contained by default: GraphHopper running in-process on the locally built graph. */
    val primaryEngine: EngineId = EngineId.GRAPHHOPPER_LOCAL,
    val comparisonMode: ComparisonMode = ComparisonMode.SINGLE,
    val shadowEngine: EngineId = EngineId.VALHALLA,
    /** Advanced: talk to Valhalla/GraphHopper/Nominatim on a LAN PC. Off by default — the app runs on its own. */
    val useExternalServers: Boolean = false,
    /** MapLibre ambient tile cache ceiling, MB. */
    val mapCacheMb: Int = 512,
    val protomapsKey: String = "6d0170c0f856240d",
    val mapTheme: String = "dark",
    val units: DistanceUnits = DistanceUnits.IMPERIAL,
    val recordTrips: Boolean = false,
    /** Use the fake engine instead of the LAN servers. */
    val demoMode: Boolean = false,
    val voiceGuidance: Boolean = true,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val highways: HighwayPreference = HighwayPreference.NEUTRAL,
    val alternatives: Int = 2,
    val personalizeEta: Boolean = false,
    val personalizeRouting: Boolean = false,
    val etaProfile: EtaProfile = EtaProfile.BASELINE,
    /** Serialized [dev.hansel.model.Place]; null when nothing is pending. */
    val lastDestinationJson: String? = null,
    /** Serialized [dev.hansel.navigation.NavigationSnapshot] for crash recovery (DESIGN §12). */
    val navSnapshotJson: String? = null,
    /** Serialized route-editor state (destination, shaping anchors, locks, avoid zones). */
    val editorStateJson: String? = null,
)

/** The engine-facing options derived from the user's settings. */
fun HanselSettings.routingOptions(): RoutingOptions = RoutingOptions(
    avoidTolls = avoidTolls,
    avoidFerries = avoidFerries,
    avoidUnpaved = avoidUnpaved,
    highways = highways,
    alternatives = alternatives.coerceAtLeast(0),
    etaProfile = etaProfile,
)

private val EngineId.isServer: Boolean get() = this == EngineId.VALHALLA || this == EngineId.GRAPHHOPPER

/** Which engine should actually answer requests, honoring demo mode and the external-servers switch. */
val HanselSettings.effectivePrimaryEngine: EngineId
    get() = when {
        demoMode -> EngineId.FAKE
        primaryEngine.isServer && !useExternalServers -> EngineId.GRAPHHOPPER_LOCAL
        else -> primaryEngine
    }

/** Shadow engine, or null when comparison is off, would duplicate the primary, or needs a server that's disabled. */
val HanselSettings.effectiveShadowEngine: EngineId?
    get() = when {
        demoMode -> null
        comparisonMode == ComparisonMode.SINGLE -> null
        shadowEngine == effectivePrimaryEngine -> null
        shadowEngine.isServer && !useExternalServers -> null
        else -> shadowEngine
    }
