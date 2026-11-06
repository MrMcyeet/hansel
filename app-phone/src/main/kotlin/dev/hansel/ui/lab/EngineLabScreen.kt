package dev.hansel.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import dev.hansel.routing.EngineHealth
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * DESIGN §10 "Engine lab". One on-device engine ships today (GraphHopper on a locally built graph), so this shows its
 * health and ETA scorecard; the shadow/side-by-side plumbing in the orchestrator stays ready for a second engine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineLabScreen(vm: MainViewModel, onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as HanselApp).graph
    val offline by graph.offline.status.collectAsState()
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<EngineHealth?>(null) }
    var scores by remember { mutableStateOf<List<EngineScore>>(emptyList()) }
    var records by remember { mutableStateOf(0) }

    suspend fun refresh() {
        health = graph.engines()[EngineId.GRAPHHOPPER]?.let { e -> runCatching { e.healthCheck() }.getOrElse { EngineHealth(false, it.message ?: "error") } }
        val all = graph.abStore.all()
        records = all.size
        scores = Scorecard.compute(all)
    }
    LaunchedEffect(offline.manifest.graphBuiltAtMillis) { refresh() }

    Scaffold(
        containerColor = HanselColors.Ink,
        topBar = {
            TopAppBar(title = { Text("Engine lab") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink), actions = { TextButton(onClick = { scope.launch { refresh() } }) { Text("Refresh") } })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabCard("On-device GraphHopper") {
                val h = health
                when {
                    !offline.hasGraph -> Text("No routing graph installed. Open Offline data to build one.", color = HanselColors.Amber)
                    h == null -> Text("Loading graph…", color = HanselColors.Muted)
                    else -> {
                        Text(if (h.reachable) "Loaded · ${h.latencyMillis} ms" else "Failed to load", color = if (h.reachable) HanselColors.Teal else HanselColors.Red)
                        Text(h.detail, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("Graph: ${offline.manifest.graphRegionIds.joinToString(", ") { it.substringAfter('/') }} · ${offline.manifest.graphBytes / 1_048_576} MB · ${offline.manifest.graphBuildConfig ?: "—"}",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            LabCard("Last request") {
                val r = ui.result
                if (r == null) Text("Route something first.", color = HanselColors.Muted) else {
                    Text("${r.primaryEngine.name.lowercase()} · ${if (r.primaryRaw.onDevice) "on-device" else "server"} · ${r.primaryRaw.latencyMillis} ms · ${r.routes.size} routes shown, ${r.dropped.size} dropped")
                    r.dropped.forEach { Text("dropped: ${it.reason}", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall) }
                    (r.primaryRaw.warnings + r.routes.flatMap { it.warnings }).distinct().forEach { Text("warning: $it", color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall) }
                }
            }
            LabCard("ETA scorecard ($records logged requests)") {
                if (scores.isEmpty()) Text("No completed trips yet. Turn on Record trips in Settings and drive a route to completion.", color = HanselColors.Muted)
                for (s in scores) {
                    Text("${s.engine.name.lowercase()} · n=${s.samples}", style = MaterialTheme.typography.titleMedium)
                    Text("MAE ${(s.maeSeconds / 60).roundToInt()} min · median ${(s.medianAbsErrorSeconds / 60).roundToInt()} min · p90 ${(s.p90AbsErrorSeconds / 60).roundToInt()} min · bias ${(s.biasSeconds / 60).roundToInt()} min · latency ${s.meanLatencyMillis.roundToInt()} ms",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Text("A second on-device engine (a native Valhalla build) would light up the shadow and side-by-side modes already in the orchestrator (DESIGN §9).",
                color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
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
