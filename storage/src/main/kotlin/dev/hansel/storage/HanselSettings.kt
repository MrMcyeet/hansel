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
    val primaryEngine: EngineId = EngineId.VALHALLA,
    val comparisonMode: ComparisonMode = ComparisonMode.SHADOW,
    val shadowEngine: EngineId = EngineId.GRAPHHOPPER,
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

/** Which engine should actually answer requests, honoring demo mode. */
val HanselSettings.effectivePrimaryEngine: EngineId
    get() = if (demoMode) EngineId.FAKE else primaryEngine

/** Shadow engine, or null when comparison is off or would duplicate the primary. */
val HanselSettings.effectiveShadowEngine: EngineId?
    get() = when {
        demoMode -> null
        comparisonMode == ComparisonMode.SINGLE -> null
        shadowEngine == primaryEngine -> null
        else -> shadowEngine
    }
