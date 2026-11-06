package dev.hansel.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.hansel.HanselApp
import dev.hansel.offline.JobProgress
import dev.hansel.offline.OfflineManager
import dev.hansel.ui.lab.LabCard
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.hansel.osm.Region as CatalogRegion

/**
 * DESIGN §10 "Downloads" / §4.2: pick regions (your state is pre-selected), see sizes, build, watch progress, manage
 * what's installed. Everything is built on the phone; nothing is served from anywhere but Geofabrik/Protomaps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDataScreen(vm: MainViewModel, onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as HanselApp).graph
    val offline = graph.offline
    val status by offline.status.collectAsState()
    val settings by vm.settings.collectAsState()
    val pos by vm.position.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(containerColor = HanselColors.Ink, topBar = {
        TopAppBar(title = { Text("Offline data") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status.job?.let { job -> JobCard(job, onCancel = { offline.cancel() }, onDismiss = { offline.dismissJob() }) }

            LabCard("Installed") {
                val m = status.manifest
                if (m.regions.isEmpty()) Text("Nothing yet.", color = HanselColors.Muted)
                m.regions.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.region.name)
                            Text("${mb(r.bytes)} MB extract · ${date(r.downloadedAtMillis)}" + if (r.region.id !in m.graphRegionIds) " · not in the routing graph yet" else "",
                                color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { scope.launch { offline.remove(r.region.id) } }, enabled = !status.isBusy) { Text("Remove", color = HanselColors.Red) }
                    }
                }
                if (m.regions.isNotEmpty()) {
                    Text(buildString {
                        append(if (status.hasGraph) "Routing graph ✓ ${mb(m.graphBytes)} MB" else "Routing graph ✗")
                        append(" · ")
                        append(if (status.hasSearch) "Search ✓ ${"%,d".format(m.searchIndexRows)} places" else "Search ✗")
                        append(" · ")
                        append(if (status.hasTiles) "Map tiles ✓ ${mb(m.tilesBytes)} MB (z≤${m.tilesMaxZoom})" else "Map tiles ✗")
                    }, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("${mb(offline.bytesUsed())} MB on device · ${mb(offline.layout.freeBytes())} MB free", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { offline.rebuild(settings.protomapsKey, settings.tilesMaxZoom) }, enabled = !status.isBusy) { Text(if (status.staleRegionIds.isNotEmpty() || !status.isSelfContained) "Build now" else "Refresh data") }
                        TextButton(onClick = { scope.launch { offline.wipe() } }, enabled = !status.isBusy) { Text("Delete everything", color = HanselColors.Red) }
                    }
                }
            }

            AddRegionsCard(offline, pos?.point, installed = status.manifest.regions.map { it.region.id }, busy = status.isBusy,
                tilesMaxZoom = settings.tilesMaxZoom,
                onInstall = { ids -> offline.install(ids, settings.protomapsKey, settings.tilesMaxZoom) })

            LabCard("Map detail") {
                Text("Tile zoom cap. 15 is Protomaps' maximum (house-level detail) and roughly doubles the download of 14.", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (z in listOf(13, 14, 15)) FilterChip(selected = settings.tilesMaxZoom == z, onClick = { scope.launch { graph.settingsStore.update { it.copy(tilesMaxZoom = z) } } }, label = { Text("z$z") })
                }
            }
            Text("Road data © OpenStreetMap contributors (ODbL) via Geofabrik · tiles from Protomaps' public daily build · builds run on this phone only.",
                style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
        }
    }
}

@Composable
fun JobCard(job: JobProgress, onCancel: () -> Unit, onDismiss: () -> Unit) {
    LabCard(when (job.phase) {
        JobProgress.Phase.DONE -> "Build finished"
        JobProgress.Phase.FAILED -> "Build failed"
        JobProgress.Phase.CANCELLED -> "Build cancelled"
        else -> "Building · ${job.phase.name.lowercase().replace('_', ' ')}"
    }) {
        Text(job.detail, color = if (job.phase == JobProgress.Phase.FAILED) HanselColors.Red else HanselColors.Text)
        if (!job.isTerminal) {
            if (job.fraction != null) LinearProgressIndicator(progress = { job.fraction!!.toFloat() }, modifier = Modifier.fillMaxWidth(), color = HanselColors.Teal)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = HanselColors.Teal)
            Text("${(System.currentTimeMillis() - job.startedAtMillis) / 60_000} min elapsed · keep the phone plugged in; the build continues in the background",
                color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onCancel) { Text("Cancel", color = HanselColors.Red) }
        } else {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
fun AddRegionsCard(offline: OfflineManager, here: dev.hansel.model.LatLng?, installed: List<String>, busy: Boolean, tilesMaxZoom: Int, onInstall: (List<String>) -> Unit, title: String = "Add regions") {
    val suggested = remember(here) { offline.suggestedRegions(here) }
    val all = remember { offline.catalog.usStates().sortedBy { it.name } }
    var selected by remember(suggested) { mutableStateOf(suggested.firstOrNull()?.let { setOf(it.id) } ?: emptySet()) }
    var query by remember { mutableStateOf("") }
    var estimate by remember { mutableStateOf<OfflineManager.Estimate?>(null) }
    var estimating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selected, tilesMaxZoom) {
        estimate = null
        if (selected.isEmpty()) return@LaunchedEffect
        estimating = true
        estimate = runCatching { offline.estimate(selected.toList(), tilesMaxZoom) }.getOrNull()
        estimating = false
    }

    LabCard(title) {
        if (suggested.isNotEmpty()) {
            Text("Around you", style = MaterialTheme.typography.labelSmall)
            suggested.forEach { r -> RegionRow(r, r.id in installed, r.id in selected, r == suggested.first()) { selected = selected.toggle(r.id) } }
        } else if (here == null) {
            Text("Waiting for a GPS fix to suggest your state…", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Other states") },
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        if (query.length >= 2) {
            all.filter { it.name.contains(query, ignoreCase = true) && it !in suggested }.take(8).forEach { r ->
                RegionRow(r, r.id in installed, r.id in selected, false) { selected = selected.toggle(r.id) }
            }
        }
        val chosen = selected.filter { it !in installed }
        Spacer(Modifier.height(4.dp))
        when {
            estimating -> Text("Estimating size…", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
            estimate != null -> {
                val e = estimate!!
                val total = e.downloadBytes + (e.tilesBytes ?: 0L)
                Text("≈ ${mb(e.downloadBytes)} MB road data + ${e.tilesBytes?.let { "${mb(it)} MB" } ?: "?"} map tiles to download · ≈ ${mb(e.graphBytesApprox)} MB graph built on the phone · roughly ${e.minutesApprox} min on Wi-Fi",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (e.freeBytes < total * 2 + e.graphBytesApprox) Text("Not enough free space (${mb(e.freeBytes)} MB).", color = HanselColors.Red, style = MaterialTheme.typography.labelSmall)
            }
        }
        Button(onClick = { onInstall(chosen) }, enabled = chosen.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (installed.isEmpty()) "Download and build" else "Add ${chosen.size} region${if (chosen.size == 1) "" else "s"} and rebuild")
        }
    }
}

@Composable
private fun RegionRow(r: CatalogRegion, installed: Boolean, checked: Boolean, isHome: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked || installed, onCheckedChange = { if (!installed) onToggle() }, enabled = !installed)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name + if (isHome) "  · you're here" else "", color = if (isHome) HanselColors.Teal else HanselColors.Text)
            Text((r.approxBytes?.let { "≈ ${mb(it)} MB extract" } ?: "") + if (installed) " · installed" else "", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
internal fun mb(bytes: Long) = bytes / 1_048_576
private fun date(ms: Long) = SimpleDateFormat("MMM d", Locale.US).format(Date(ms))
