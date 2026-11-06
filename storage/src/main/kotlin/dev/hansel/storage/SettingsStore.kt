package dev.hansel.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.hanselPrefs: DataStore<Preferences> by preferencesDataStore(name = "hansel_settings")

/** Preferences-backed [HanselSettings]. Unknown/corrupt enum values fall back to the default rather than throwing. */
class SettingsStore(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.hanselPrefs

    val settings: Flow<HanselSettings> = store.data.map { it.toSettings() }

    suspend fun current(): HanselSettings = settings.first()

    suspend fun update(transform: (HanselSettings) -> HanselSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private object Keys {
        val tilesMaxZoom = intPreferencesKey("tiles_max_zoom")
        val setupDismissed = booleanPreferencesKey("setup_dismissed")
        val primaryEngine = stringPreferencesKey("primary_engine")
        val comparisonMode = stringPreferencesKey("comparison_mode")
        val shadowEngine = stringPreferencesKey("shadow_engine")
        val protomapsKey = stringPreferencesKey("protomaps_key")
        val mapTheme = stringPreferencesKey("map_theme")
        val units = stringPreferencesKey("units")
        val recordTrips = booleanPreferencesKey("record_trips")
        val demoMode = booleanPreferencesKey("demo_mode")
        val voiceGuidance = booleanPreferencesKey("voice_guidance")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val avoidFerries = booleanPreferencesKey("avoid_ferries")
        val avoidUnpaved = booleanPreferencesKey("avoid_unpaved")
        val highways = stringPreferencesKey("highways")
        val alternatives = intPreferencesKey("alternatives")
        val personalizeEta = booleanPreferencesKey("personalize_eta")
        val personalizeRouting = booleanPreferencesKey("personalize_routing")
        val etaProfile = stringPreferencesKey("eta_profile")
        val lastDestinationJson = stringPreferencesKey("last_destination_json")
        val navSnapshotJson = stringPreferencesKey("nav_snapshot_json")
        val editorStateJson = stringPreferencesKey("editor_state_json")
    }

    private fun Preferences.toSettings(): HanselSettings {
        val d = HanselSettings()
        return HanselSettings(
            tilesMaxZoom = this[Keys.tilesMaxZoom] ?: d.tilesMaxZoom,
            setupDismissed = this[Keys.setupDismissed] ?: d.setupDismissed,
            primaryEngine = enum(this[Keys.primaryEngine], d.primaryEngine),
            comparisonMode = enum(this[Keys.comparisonMode], d.comparisonMode),
            shadowEngine = enum(this[Keys.shadowEngine], d.shadowEngine),
            protomapsKey = this[Keys.protomapsKey] ?: d.protomapsKey,
            mapTheme = this[Keys.mapTheme] ?: d.mapTheme,
            units = enum(this[Keys.units], d.units),
            recordTrips = this[Keys.recordTrips] ?: d.recordTrips,
            demoMode = this[Keys.demoMode] ?: d.demoMode,
            voiceGuidance = this[Keys.voiceGuidance] ?: d.voiceGuidance,
            avoidTolls = this[Keys.avoidTolls] ?: d.avoidTolls,
            avoidFerries = this[Keys.avoidFerries] ?: d.avoidFerries,
            avoidUnpaved = this[Keys.avoidUnpaved] ?: d.avoidUnpaved,
            highways = enum(this[Keys.highways], d.highways),
            alternatives = this[Keys.alternatives] ?: d.alternatives,
            personalizeEta = this[Keys.personalizeEta] ?: d.personalizeEta,
            personalizeRouting = this[Keys.personalizeRouting] ?: d.personalizeRouting,
            etaProfile = enum(this[Keys.etaProfile], d.etaProfile),
            lastDestinationJson = this[Keys.lastDestinationJson],
            navSnapshotJson = this[Keys.navSnapshotJson],
            editorStateJson = this[Keys.editorStateJson],
        )
    }

    private fun MutablePreferences.write(s: HanselSettings) {
        this[Keys.tilesMaxZoom] = s.tilesMaxZoom
        this[Keys.setupDismissed] = s.setupDismissed
        this[Keys.primaryEngine] = s.primaryEngine.name
        this[Keys.comparisonMode] = s.comparisonMode.name
        this[Keys.shadowEngine] = s.shadowEngine.name
        this[Keys.protomapsKey] = s.protomapsKey
        this[Keys.mapTheme] = s.mapTheme
        this[Keys.units] = s.units.name
        this[Keys.recordTrips] = s.recordTrips
        this[Keys.demoMode] = s.demoMode
        this[Keys.voiceGuidance] = s.voiceGuidance
        this[Keys.avoidTolls] = s.avoidTolls
        this[Keys.avoidFerries] = s.avoidFerries
        this[Keys.avoidUnpaved] = s.avoidUnpaved
        this[Keys.highways] = s.highways.name
        this[Keys.alternatives] = s.alternatives
        this[Keys.personalizeEta] = s.personalizeEta
        this[Keys.personalizeRouting] = s.personalizeRouting
        this[Keys.etaProfile] = s.etaProfile.name
        setOrRemove(Keys.lastDestinationJson, s.lastDestinationJson)
        setOrRemove(Keys.navSnapshotJson, s.navSnapshotJson)
        setOrRemove(Keys.editorStateJson, s.editorStateJson)
    }

    private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private inline fun <reified E : Enum<E>> enum(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
