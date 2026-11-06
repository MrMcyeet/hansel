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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hansel.HanselApp
import dev.hansel.offline.JobProgress
import dev.hansel.offline.OfflineStatus
import dev.hansel.ui.lab.LabCard
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun bytesText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "${b / (1024 * 1024)} MB"
    else -> "${b / 1024} KB"
}

/**
 * DESIGN §10 "Downloads", reshaped for self-contained operation: pick states → the phone downloads the OSM extracts,
 * builds the routing graph and the search index itself. Map tiles are a separate section (ambient cache + saved areas).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDataScreen(vm: MainViewModel, onBack: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as HanselApp).graph
    val ctl = graph.offline
    val status by ctl.status.collectAsState()
    val settings by vm.settings.collectAsState()
    val areas by graph.mapOffline.areas.collectAsState()
    val scope = rememberCoroutineScope()
    val installed = remember(status) { status.manifest.regions.map { it.region.id }.toSet() }
    var selected by remember(installed) { mutableStateOf(installed) }
    val estimate = remember(selected) { ctl.estimate(selected.toList()) }
    LaunchedEffect(Unit) { graph.mapOffline.refresh() }

    Scaffold(containerColor = HanselColors.Ink, topBar = {
        TopAppBar(title = { Text("Offline data") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HanselColors.Ink))
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StatusCard(status, onCancel = { ctl.cancel() }) }

            item {
                LabCard("Road data · states") {
                    Text("Pick the states you drive in. The phone downloads the map extracts and builds its own routing graph and address index — nothing runs on a PC. Plug in and stay on Wi-Fi; three states take roughly half an hour to an hour.",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("Download ${bytesText(estimate.downloadBytes)} · about ${bytesText(estimate.diskBytes)} on disk · ${bytesText(estimate.freeBytes)} free",
                        color = if (estimate.fits) HanselColors.Text else HanselColors.Red, style = MaterialTheme.typography.bodyMedium)
                    val changed = selected != installed || status.staleRegionIds.isNotEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { ctl.install(selected.toList()) }, enabled = selected.isNotEmpty() && !status.isBusy && estimate.fits && (changed || !status.hasGraph)) {
                            Text(if (!status.hasGraph) "Download & build" else if (changed) "Update" else "Up to date")
                        }
                        Spacer(Modifier.width(8.dp))
                        if (status.hasGraph) TextButton(onClick = { scope.launch { ctl.deleteAll() } }, enabled = !status.isBusy) { Text("Delete all", color = HanselColors.Red) }
                    }
                }
            }

            val gulf = ctl.regions.filter { it.id in setOf("mississippi", "louisiana", "alabama") }
            val rest = ctl.regions.filter { it !in gulf }.sortedBy { it.name }
            items(gulf + rest, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = r.id in selected, enabled = !status.isBusy, onCheckedChange = { on -> selected = if (on) selected + r.id else selected - r.id })
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = HanselColors.Text)
                        val inst = status.manifest.regions.firstOrNull { it.region.id == r.id }
                        Text(if (inst != null) "installed · ${bytesText(inst.bytes)}" + (if (r.id in status.staleRegionIds) " · not in graph yet" else "") else "~${bytesText(r.approxBytes)}",
                            color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (r.id in installed) IconButton(onClick = { scope.launch { ctl.removeRegion(r.id) } }, enabled = !status.isBusy) {
                        Icon(Icons.Default.Delete, "Remove", tint = HanselColors.Red)
                    }
                }
            }

            item {
                LabCard("Map tiles") {
                    Text("Tiles come from Protomaps online. Everything you look at is kept in a cache; use ⋮ → \"Save map for this view\" on the map to pull an area down ahead of time.",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("Cache size", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mb in listOf(256, 512, 1024, 2048)) FilterChip(selected = settings.mapCacheMb == mb,
                            onClick = { scope.launch { graph.settingsStore.update { it.copy(mapCacheMb = mb) }; graph.mapOffline.setAmbientCacheBytes(mb.toLong() shl 20) } },
                            label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") })
                    }
                    TextButton(onClick = { scope.launch { graph.mapOffline.clearAmbientCache() } }) { Text("Clear cached tiles") }
                    if (areas.isEmpty()) Text("No saved areas.", color = HanselColors.Muted)
                    for (a in areas) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = HanselColors.Text)
                                Text((if (a.complete) "saved" else if (a.downloading) "downloading ${a.completedTiles}/${a.requiredTiles}" else "partial ${a.completedTiles}/${a.requiredTiles}") + " · ${bytesText(a.bytes)} · z${a.minZoom.toInt()}–${a.maxZoom.toInt()}",
                                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { scope.launch { graph.mapOffline.delete(a.id) } }) { Icon(Icons.Default.Delete, "Delete area", tint = HanselColors.Red) }
                        }
                    }
                }
            }
            item {
                Text("Map data © OpenStreetMap contributors (ODbL), extracts by Geofabrik. Routing graph built on this phone with GraphHopper.",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(status: OfflineStatus, onCancel: () -> Unit) {
    LabCard("Status") {
        val job = status.job
        when {
            job != null && status.isBusy -> {
                Text(phaseLabel(job.phase), style = MaterialTheme.typography.titleMedium, color = HanselColors.Teal)
                Text(job.detail, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                if (job.fraction != null) LinearProgressIndicator(progress = { job.fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = HanselColors.Teal)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = HanselColors.Teal)
                if (job.bytesTotal > 0) Text("${bytesText(job.bytesDone)} / ${bytesText(job.bytesTotal)}", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onCancel) { Text("Cancel", color = HanselColors.Red) }
            }
            status.hasGraph -> {
                val m = status.manifest
                Text("Ready · ${m.graphRegionIds.joinToString(", ") { it.replaceFirstChar(Char::uppercase) }}", style = MaterialTheme.typography.titleMedium, color = HanselColors.Teal)
                Text("Graph ${bytesText(m.graphBytes)} built ${m.graphBuiltAtMillis?.let { SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(it)) }} · ${m.searchIndexRows} places indexed",
                    color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                if (status.staleRegionIds.isNotEmpty()) Text("Downloaded but not built yet: ${status.staleRegionIds.joinToString()} — tap Update.", color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall)
            }
            else -> Text("No road data on this phone yet. Routing and search need it; the map works without it.", color = HanselColors.Amber)
        }
        status.lastError?.let { Text("Last error: $it", color = HanselColors.Red, style = MaterialTheme.typography.labelSmall) }
    }
}

private fun phaseLabel(p: JobProgress.Phase) = when (p) {
    JobProgress.Phase.DOWNLOADING -> "Downloading map extracts"
    JobProgress.Phase.VERIFYING -> "Verifying"
    JobProgress.Phase.MERGING -> "Merging regions"
    JobProgress.Phase.INDEXING_SEARCH -> "Building address index"
    JobProgress.Phase.IMPORTING_GRAPH -> "Building routing graph"
    JobProgress.Phase.PREPARING_CH -> "Speeding up routing (contraction)"
    JobProgress.Phase.ACTIVATING -> "Activating"
    JobProgress.Phase.DONE -> "Done"
    JobProgress.Phase.FAILED -> "Failed"
}
