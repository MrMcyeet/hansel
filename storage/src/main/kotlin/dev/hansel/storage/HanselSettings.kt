package dev.hansel.storage

import dev.hansel.model.EngineId
import dev.hansel.model.EtaProfile
import dev.hansel.model.HighwayPreference
import dev.hansel.model.RoutingOptions
import dev.hansel.navigation.DistanceUnits
import dev.hansel.routing.ComparisonMode

/** Everything the user can change in Settings plus a few restore-after-process-death blobs. Defaults = dev LAN setup. */
data class HanselSettings(
    /** Self-contained by default: GraphHopper running in-process on the locally built graph. */
    val primaryEngine: EngineId = EngineId.GRAPHHOPPER,
    val comparisonMode: ComparisonMode = ComparisonMode.SINGLE,
    val shadowEngine: EngineId = EngineId.VALHALLA,
    /** Only used to fetch the hosted style JSON once per theme during the offline build, and as the online map fallback before any region is installed. */
    val protomapsKey: String = "6d0170c0f856240d",
    /** Zoom cap for extracted tiles. 14 ≈ 0.5 GB for MS+LA+AL; 15 (Protomaps' max, building footprints) ≈ 1 GB. */
    val tilesMaxZoom: Int = 14,
    /** The first-launch setup card was dismissed without installing anything. */
    val setupDismissed: Boolean = false,
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

/** Which engine should actually answer requests, honoring demo mode and the external-servers switch. */
val HanselSettings.effectivePrimaryEngine: EngineId
    get() = if (demoMode) EngineId.FAKE else EngineId.GRAPHHOPPER

/** Shadow engine, or null when comparison is off, would duplicate the primary, or needs a server that's disabled. */
/** Only one on-device engine ships today, so there is never a shadow engine; kept for the A/B design (DESIGN §9). */
val HanselSettings.effectiveShadowEngine: EngineId?
    get() = null
