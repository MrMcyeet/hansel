package dev.hansel.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hansel.HanselApp
import dev.hansel.benchmark.EngineScore
import dev.hansel.benchmark.Scorecard
import dev.hansel.model.EngineId
import dev.hansel.storage.effectivePrimaryEngine
import dev.hansel.routing.ComparisonMode
import dev.hansel.routing.EngineHealth
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** DESIGN §10 "Engine lab": Valhalla / GraphHopper / side-by-side, discrepancy metrics, scorecard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineLabScreen(vm: MainViewModel, onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as HanselApp).graph
    val settings by vm.settings.collectAsState()
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<Map<EngineId, EngineHealth>>(emptyMap()) }
    var scores by remember { mutableStateOf<List<EngineScore>>(emptyList()) }
    var records by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val engines = graph.engines()
        val servers = settings.useExternalServers
        health = engines.filterKeys { it != EngineId.FAKE && (servers || it == EngineId.GRAPHHOPPER_LOCAL) }
            .mapValues { (_, e) -> runCatching { e.healthCheck() }.getOrElse { EngineHealth(false, it.message ?: "error") } }
        val all = graph.abStore.all()
        records = all.size
        scores = Scorecard.compute(all)
    }
    LaunchedEffect(settings.valhallaUrl, settings.graphhopperUrl, settings.useExternalServers) { refresh() }

    Scaffold(
        containerColor = HanselColors.Ink,
        topBar = {
            TopAppBar(title = { Text("Engine lab") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink), actions = { TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") } })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabCard("Engines") {
                for ((id, h) in health) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(engineLabel(id) + if (h.reachable) "  ·  OK, ${h.latencyMillis} ms" else "  ·  unavailable",
                            color = if (h.reachable) HanselColors.Teal else HanselColors.Red)
                        Text(h.detail, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (health.isEmpty()) Text("Checking…", color = HanselColors.Muted)
            }
            LabCard("Primary engine") {
                val choices = if (settings.useExternalServers) listOf(EngineId.GRAPHHOPPER_LOCAL, EngineId.VALHALLA, EngineId.GRAPHHOPPER) else listOf(EngineId.GRAPHHOPPER_LOCAL)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in choices) FilterChip(selected = settings.effectivePrimaryEngine == e, onClick = {
                        scope.launch { graph.settingsStore.update { it.copy(primaryEngine = e, shadowEngine = if (e == EngineId.VALHALLA) EngineId.GRAPHHOPPER else EngineId.VALHALLA) } }
                    }, label = { Text(engineLabel(e)) })
                }
                if (!settings.useExternalServers) Text("Valhalla and server GraphHopper appear here once \"Use servers on my LAN\" is on in Settings. Valhalla on-device needs a native port (later).",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            LabCard("Comparison mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in ComparisonMode.entries) FilterChip(selected = settings.comparisonMode == m, onClick = {
                        scope.launch { graph.settingsStore.update { it.copy(comparisonMode = m) } }
                    }, label = { Text(m.name.lowercase().replace('_', ' ')) })
                }
                Text(when (settings.comparisonMode) {
                    ComparisonMode.SINGLE -> "One engine, no comparison."
                    ComparisonMode.SHADOW -> "Compute both, navigate the primary. Unchosen predictions are never treated as outcomes."
                    ComparisonMode.SIDE_BY_SIDE -> "Pool both engines' candidates into the route cards; duplicates dropped by overlap."
                }, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            LabCard("Last request") {
                val r = ui.result
                if (r == null) Text("Route something first.", color = HanselColors.Muted) else {
                    Text("${r.primaryEngine.name.lowercase()} · ${r.primaryRaw.latencyMillis} ms · ${r.routes.size} routes shown, ${r.dropped.size} dropped")
                    r.discrepancy?.let { d ->
                        Text("vs ${d.shadowEngine.name.lowercase()}: overlap ${(d.overlap * 100).roundToInt()}%, ETA Δ ${(d.etaDeltaSeconds / 60).roundToInt()} min, distance Δ ${(d.distanceDeltaMeters / 1609.344 * 10).roundToInt() / 10.0} mi, latency ${d.shadowLatencyMillis} ms" + if (d.shadowFailed) " (shadow failed)" else "")
                    }
                    r.dropped.forEach { Text("dropped: ${it.reason}", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall) }
                    r.primaryRaw.warnings.forEach { Text("warning: $it", color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall) }
                }
            }
            LabCard("Scorecard ($records logged requests)") {
                if (scores.isEmpty()) Text("No completed trips yet. Turn on Record trips in Settings and drive a route to completion.", color = HanselColors.Muted)
                for (s in scores) {
                    Text("${s.engine.name.lowercase()} · n=${s.samples}", style = MaterialTheme.typography.titleMedium)
                    Text("MAE ${(s.maeSeconds / 60).roundToInt()} min · median ${(s.medianAbsErrorSeconds / 60).roundToInt()} min · p90 ${(s.p90AbsErrorSeconds / 60).roundToInt()} min · bias ${(s.biasSeconds / 60).roundToInt()} min · latency ${s.meanLatencyMillis.roundToInt()} ms",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

fun engineLabel(e: EngineId): String = when (e) {
    EngineId.GRAPHHOPPER_LOCAL -> "GraphHopper (on device)"
    EngineId.GRAPHHOPPER -> "GraphHopper (server)"
    EngineId.VALHALLA -> "Valhalla (server)"
    EngineId.FAKE -> "Demo"
}

@Composable
fun LabCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = HanselColors.Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderLabScreen(title: String, phase: String, items: List<String>, onBack: () -> Unit) {
    Scaffold(containerColor = HanselColors.Ink, topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink))
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$phase — not built yet", color = HanselColors.Amber, style = MaterialTheme.typography.titleMedium)
            items.forEach { Text("• $it", color = HanselColors.Text) }
        }
    }
}
