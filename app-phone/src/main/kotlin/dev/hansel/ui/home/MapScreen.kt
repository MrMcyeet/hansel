package dev.hansel.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.hansel.map.CameraMode
import dev.hansel.map.HanselMap
import dev.hansel.map.MapGestures
import dev.hansel.map.ProtomapsStyle
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import dev.hansel.navigation.NavState
import dev.hansel.ui.nav.ManeuverBanner
import dev.hansel.ui.nav.NavigationBar
import dev.hansel.ui.preview.EditorPanel
import dev.hansel.ui.preview.RoutePreviewPanel
import dev.hansel.ui.search.SearchMode
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun MapScreen(
    vm: MainViewModel,
    onSearch: (SearchMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEngineLab: () -> Unit,
    onOpenTrafficLab: () -> Unit,
    onOpenLearningLab: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val nav by vm.navState.collectAsState()
    val pos by vm.position.collectAsState()
    val settings by vm.settings.collectAsState()
    val cameraMode by vm.cameraMode.collectAsState()
    val toast by vm.toasts.collectAsState()
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.hansel.HanselApp).graph
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.consumeToast() } }

    val overlay = remember(ui, nav, pos) { vm.overlay() }
    val navigating = nav is NavState.Navigating
    val previewing = !navigating && ui.editor != null
    var pendingTap by remember { mutableStateOf<LatLng?>(null) }
    val bottomInsetPx = with(LocalDensity.current) { (if (navigating) 150.dp else 0.dp).roundToPx() }

    Box(Modifier.fillMaxSize().background(HanselColors.Ink)) {
        val offlineStatus by graph.offline.status.collectAsState()
        val offlineGen by graph.offline.generation.collectAsState()
        val styleUrl = remember(settings.mapTheme, settings.protomapsKey, offlineStatus.hasTiles, offlineGen) {
            graph.offline.styleFile(settings.mapTheme)?.let { "file://" + it.absolutePath } ?: ProtomapsStyle.url(settings.mapTheme, settings.protomapsKey)
        }
        HanselMap(
            styleUrl = styleUrl,
            overlay = overlay,
            cameraMode = cameraMode,
            bottomInsetPx = bottomInsetPx,
            gestures = MapGestures(
                onRouteTap = { id, at -> vm.onRouteTapped(id, at) },
                // A plain tap does nothing; dropping a pin is long-press (see the Home panel hint).
                onMapTap = { },
                onLongPress = { p ->
                    when {
                        previewing && ui.editMode -> vm.addThroughPoint(p)
                        previewing -> { vm.setEditMode(true); vm.addThroughPoint(p) }
                        !navigating -> pendingTap = p
                    }
                },
                onDragShaping = { grab, drop -> vm.dragShaping(grab, drop) },
                onUserMovedCamera = { vm.onUserMovedCamera() },
                dragEnabled = previewing && ui.editMode,
            ),
            modifier = Modifier.fillMaxSize(),
        )

        // ---- top chrome ----
        Column(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
            when {
                navigating -> ManeuverBanner(nav as NavState.Navigating, settings.units)
                nav is NavState.Arrived && !previewing -> {
                    Surface(shape = RoundedCornerShape(16.dp), color = HanselColors.TealDeep, modifier = Modifier.fillMaxWidth()) {
                        Text("Arrived", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.titleMedium, color = HanselColors.Teal)
                    }
                    Spacer(Modifier.height(8.dp))
                    HomeHeader(onSearch = { onSearch(SearchMode.DESTINATION) }, onOpenSettings = onOpenSettings,
                        onOpenEngineLab = onOpenEngineLab, onOpenTrafficLab = onOpenTrafficLab, onOpenLearningLab = onOpenLearningLab, onOpenDownloads = onOpenDownloads)
                }
                previewing -> PreviewHeader(ui.destination?.name ?: "Route", editMode = ui.editMode,
                    onClose = { vm.clearRoute() }, onToggleEdit = { vm.setEditMode(!ui.editMode) })
                else -> HomeHeader(onSearch = { onSearch(SearchMode.DESTINATION) }, onOpenSettings = onOpenSettings,
                    onOpenEngineLab = onOpenEngineLab, onOpenTrafficLab = onOpenTrafficLab, onOpenLearningLab = onOpenLearningLab, onOpenDownloads = onOpenDownloads)
            }
        }

        // ---- recenter ----
        if (cameraMode == CameraMode.FREE) {
            IconButton(onClick = { vm.recenter() }, modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp)
                .background(HanselColors.Panel, RoundedCornerShape(12.dp))) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recenter", tint = HanselColors.Teal)
            }
        }

        // ---- bottom panel ----
        Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)) {
            SnackbarHost(snackbar)
            when {
                navigating -> NavigationBar(vm, nav as NavState.Navigating, settings.units)
                previewing && ui.editMode -> EditorPanel(vm, ui, settings.units, onAddStop = { onSearch(SearchMode.ADD_STOP) })
                previewing -> RoutePreviewPanel(vm, ui, settings)
                else -> HomePanel(vm, onSearch = { onSearch(SearchMode.DESTINATION) }, onOpenDownloads = onOpenDownloads)
            }
        }

        pendingTap?.let { p ->
            AlertDialog(
                onDismissRequest = { pendingTap = null },
                title = { Text("Route here?") },
                text = { Text("%.5f, %.5f".format(p.lat, p.lon), color = HanselColors.Muted) },
                confirmButton = { Button(onClick = { vm.chooseDestination(Place("tap:${p}", "Dropped pin", point = p, source = PlaceSource.MAP_TAP)); pendingTap = null }) { Text("Go") } },
                dismissButton = { TextButton(onClick = { pendingTap = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun HomeHeader(onSearch: () -> Unit, onOpenSettings: () -> Unit, onOpenEngineLab: () -> Unit, onOpenTrafficLab: () -> Unit, onOpenLearningLab: () -> Unit, onOpenDownloads: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f).height(52.dp).clickable(onClick = onSearch),
            shape = RoundedCornerShape(16.dp), color = HanselColors.Panel, tonalElevation = 4.dp,
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = HanselColors.Teal)
                Spacer(Modifier.width(12.dp))
                Text("Where to?", color = HanselColors.Muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.background(HanselColors.Panel, RoundedCornerShape(16.dp))) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = HanselColors.Text)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Engine lab") }, onClick = { menu = false; onOpenEngineLab() })
                DropdownMenuItem(text = { Text("Traffic lab") }, onClick = { menu = false; onOpenTrafficLab() })
                DropdownMenuItem(text = { Text("Learning lab") }, onClick = { menu = false; onOpenLearningLab() })
                DropdownMenuItem(text = { Text("Offline data") }, onClick = { menu = false; onOpenDownloads() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
            }
        }
    }
}

@Composable
private fun PreviewHeader(title: String, editMode: Boolean, onClose: () -> Unit, onToggleEdit: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = HanselColors.Panel, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Clear route", tint = HanselColors.Text) }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            IconButton(onClick = onToggleEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit route", tint = if (editMode) HanselColors.Teal else HanselColors.Text)
            }
        }
    }
}

@Composable
private fun HomePanel(vm: MainViewModel, onSearch: () -> Unit, onOpenDownloads: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.hansel.HanselApp).graph
    val recents by graph.recentPlaces.recents(8).collectAsState(initial = emptyList())
    val saved by graph.savedRoutes.list().collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    val offline by graph.offline.status.collectAsState()
    val pos by vm.position.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = HanselColors.Panel, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            StatusLine(settings.demoMode, offline)
            // First-launch setup (DESIGN §4.2): your state is pre-selected from GPS; nothing is installed until you say so.
            if (!offline.isSelfContained && !offline.isBusy && !settings.demoMode && !settings.setupDismissed && offline.manifest.regions.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                dev.hansel.ui.offline.AddRegionsCard(graph.offline, pos?.point, installed = emptyList(), busy = false, tilesMaxZoom = settings.tilesMaxZoom,
                    onInstall = { ids -> graph.offline.install(ids, settings.protomapsKey, settings.tilesMaxZoom) }, title = "Set up this phone for offline use")
                Row {
                    TextButton(onClick = { scope.launch { graph.settingsStore.update { it.copy(setupDismissed = true) } } }) { Text("Later") }
                    TextButton(onClick = onOpenDownloads) { Text("More options") }
                }
            } else if (offline.isBusy) {
                Spacer(Modifier.height(10.dp))
                offline.job?.let { dev.hansel.ui.offline.JobCard(it, onCancel = { graph.offline.cancel() }, onDismiss = { graph.offline.dismissJob() }) }
            } else if (!offline.isSelfContained && !settings.demoMode) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = HanselColors.PanelHi, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDownloads)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(if (offline.lastError != null) "Offline build failed" else "Offline data incomplete", style = MaterialTheme.typography.titleMedium, color = HanselColors.Amber)
                        Text(offline.lastError ?: "Open Offline data to finish setting up routing, search and map tiles.", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                    }
                }
            }
            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Recent", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recents, key = { it.id }) { p ->
                        FilledTonalButton(onClick = { vm.chooseDestination(p) }) { Text(p.name, maxLines = 1) }
                    }
                }
            }
            if (saved.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Saved routes", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(saved, key = { it.id }) { r ->
                        FilledTonalButton(onClick = { vm.loadSavedRoute(r.request) }) { Text(r.name, maxLines = 1) }
                    }
                }
            }
            if (recents.isEmpty() && saved.isEmpty() && (offline.isSelfContained || settings.demoMode)) {
                Spacer(Modifier.height(10.dp))
                Text("Search, or long-press the map to drop a pin.", color = HanselColors.Muted)
            }
        }
    }
}

@Composable
fun StatusLine(demoMode: Boolean, offline: dev.hansel.offline.OfflineStatus) {
    val (dot, text) = when {
        demoMode -> HanselColors.Amber to "Demo mode · fake engine"
        offline.isSelfContained -> HanselColors.Teal to "Offline · ${offline.manifest.graphRegionIds.joinToString(", ") { it.substringAfter('/').replace('-', ' ').replaceFirstChar(Char::uppercase) }}"
        offline.isBusy -> HanselColors.Amber to "Building offline data…"
        offline.hasGraph -> HanselColors.Amber to "Routing offline · map tiles online"
        else -> HanselColors.Red to "No offline data yet · routing unavailable"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).background(dot, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
    }
}

/** Shared tiny name prompt used by "save route". */
@Composable
fun NamePrompt(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private val transparent = Color.Transparent
