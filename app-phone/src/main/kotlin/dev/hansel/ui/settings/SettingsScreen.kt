package dev.hansel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hansel.HanselApp
import dev.hansel.model.HighwayPreference
import dev.hansel.navigation.DistanceUnits
import dev.hansel.storage.HanselSettings
import dev.hansel.ui.lab.LabCard
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as HanselApp).graph
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val sampleCount by graph.tripRecorder.totalSamples().collectAsState(initial = 0L)
    fun upd(f: (HanselSettings) -> HanselSettings) = scope.launch { graph.settingsStore.update(f) }

    Scaffold(containerColor = HanselColors.Ink, topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabCard("Routing") {
                Text("Hansel routes on this phone with its own road data (⋮ → Offline data). Nothing below is needed for that.",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                ToggleRow("Demo mode (fake engine)", s.demoMode) { upd { c -> c.copy(demoMode = it) } }
            }
            LabCard("External engines (advanced)") {
                ToggleRow("Use servers on my LAN", s.useExternalServers) { upd { c -> c.copy(useExternalServers = it) } }
                if (s.useExternalServers) {
                    Text("Valhalla / GraphHopper / Nominatim from dev-server/ in the repo. Enables the two-engine A/B lab. Plain HTTP; LAN only.",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    UrlField("Valhalla", s.valhallaUrl) { v -> upd { it.copy(valhallaUrl = v) } }
                    UrlField("GraphHopper", s.graphhopperUrl) { v -> upd { it.copy(graphhopperUrl = v) } }
                    UrlField("Nominatim (search)", s.geocoderUrl) { v -> upd { it.copy(geocoderUrl = v) } }
                }
            }
            LabCard("Map") {
                UrlField("Protomaps API key", s.protomapsKey) { v -> upd { it.copy(protomapsKey = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in listOf("dark", "black", "grayscale", "light")) FilterChip(selected = s.mapTheme == t, onClick = { upd { it.copy(mapTheme = t) } }, label = { Text(t) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in DistanceUnits.entries) FilterChip(selected = s.units == u, onClick = { upd { it.copy(units = u) } }, label = { Text(u.name.lowercase()) })
                }
            }
            LabCard("Routing preferences") {
                ToggleRow("Avoid tolls", s.avoidTolls) { upd { c -> c.copy(avoidTolls = it) } }
                ToggleRow("Avoid ferries", s.avoidFerries) { upd { c -> c.copy(avoidFerries = it) } }
                ToggleRow("Avoid unpaved", s.avoidUnpaved) { upd { c -> c.copy(avoidUnpaved = it) } }
                Text("Highways", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in HighwayPreference.entries) FilterChip(selected = s.highways == h, onClick = { upd { it.copy(highways = h) } }, label = { Text(h.name.lowercase()) })
                }
                Text("Alternatives", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 0..3) FilterChip(selected = s.alternatives == n, onClick = { upd { it.copy(alternatives = n) } }, label = { Text("$n") })
                }
            }
            LabCard("Guidance") {
                ToggleRow("Voice guidance", s.voiceGuidance) { upd { c -> c.copy(voiceGuidance = it) } }
            }
            LabCard("Driving history (Phase 5 groundwork)") {
                ToggleRow("Record trips while navigating", s.recordTrips) { upd { c -> c.copy(recordTrips = it) } }
                Text("$sampleCount GPS samples stored locally. Raw speeds are kept as measured — nothing is capped to the posted limit. No cloud upload.",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { scope.launch { graph.tripRecorder.deleteAll() } }) { Text("Delete all history", color = HanselColors.Red) }
                }
                ToggleRow("Personalized ETA (needs history model)", s.personalizeEta, enabled = false) { }
                ToggleRow("Personalized routing (needs engine cost hooks)", s.personalizeRouting, enabled = false) { }
            }
            Text("Map data © OpenStreetMap contributors · tiles by Protomaps · routing by Valhalla / GraphHopper on your own server",
                style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
        }
    }
}

@Composable
private fun UrlField(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        trailingIcon = { if (text != value) TextButton(onClick = { onCommit(text.trim()) }) { Text("Apply") } })
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (enabled) HanselColors.Text else HanselColors.Muted, modifier = Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
