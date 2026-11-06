package dev.hansel.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hansel.model.WaypointType
import dev.hansel.navigation.DistanceUnits
import dev.hansel.ui.home.NamePrompt
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import dev.hansel.vm.RoutingUi

/**
 * DESIGN §10 "Route editor": waypoint list with ordering, via/stop toggle, segment lock, avoid, undo/redo, save.
 * Map gestures while this panel is up: long-press = route through here; drag the route = shaping point;
 * tap the route twice = select a stretch for the actions below.
 */
@Composable
fun EditorPanel(vm: MainViewModel, ui: RoutingUi, units: DistanceUnits, onAddStop: () -> Unit) {
    val ed = ui.editor ?: return
    val req = ed.request
    var savePrompt by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = HanselColors.Panel, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ed.isCustom) "Custom route" else "Edit route", style = MaterialTheme.typography.titleMedium,
                    color = if (ed.isCustom) HanselColors.Teal else HanselColors.Text)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.undo() }, enabled = ed.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { vm.redo() }, enabled = ed.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                TextButton(onClick = { vm.resetShaping() }, enabled = ed.isCustom) { Text("Reset") }
            }
            if (ui.loading) Text("Re-routing…", style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
            ui.error?.let { Text(it, color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall) }

            // ---- stretch actions ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                when {
                    ui.hasStretch -> {
                        FilledTonalButton(onClick = { vm.preferStretch() }) { Text("Prefer this road") }
                        FilledTonalButton(onClick = { vm.avoidStretch() }) { Text("Avoid") }
                        FilledTonalButton(onClick = { vm.keepUpToStretchEnd() }) { Text("Keep up to here") }
                    }
                    ui.stretchStart != null -> Text("Tap the route again to end the stretch", color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                    else -> Text("Long-press: route through here · drag the route to shape it · tap the route twice to pick a stretch",
                        color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (ui.stretchStart != null) TextButton(onClick = { vm.clearStretch() }) { Text("Clear selection") }

            // ---- waypoints ----
            val mid = req.intermediate
            LazyColumn(Modifier.heightIn(max = 190.dp).padding(top = 4.dp)) {
                items(mid, key = { it.id }) { wp ->
                    val idx = mid.indexOf(wp)
                    val isStop = wp.type == WaypointType.STOP || wp.type == WaypointType.STOP_THROUGH
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        AssistChip(onClick = { vm.toggleStop(wp.id) }, label = { Text(if (isStop) "Stop" else if (wp.isShaping) "Shape" else "Via") })
                        Spacer(Modifier.width(8.dp))
                        Text(wp.name ?: "%.4f, %.4f".format(wp.point.lat, wp.point.lon), modifier = Modifier.weight(1f), maxLines = 1,
                            color = if (isStop) HanselColors.Text else HanselColors.Teal)
                        IconButton(onClick = { vm.moveWaypoint(wp.id, idx - 1) }, enabled = idx > 0) { Icon(Icons.Default.ArrowUpward, "Earlier") }
                        IconButton(onClick = { vm.moveWaypoint(wp.id, idx + 1) }, enabled = idx < mid.lastIndex) { Icon(Icons.Default.ArrowDownward, "Later") }
                        IconButton(onClick = { vm.removeWaypoint(wp.id) }) { Icon(Icons.Default.Delete, "Remove", tint = HanselColors.Red) }
                    }
                }
                items(req.locks, key = { "lock-" + it.id }) { lock ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        AssistChip(onClick = {}, label = { Text("Locked") })
                        Spacer(Modifier.width(8.dp))
                        Text(lock.label ?: "${lock.anchors.size} anchors", modifier = Modifier.weight(1f), color = HanselColors.Amber, maxLines = 1)
                        IconButton(onClick = { vm.removeLock(lock.id) }) { Icon(Icons.Default.Delete, "Remove lock", tint = HanselColors.Red) }
                    }
                }
                items(req.avoidZones, key = { "zone-" + it.id }) { z ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        AssistChip(onClick = {}, label = { Text("Avoid") })
                        Spacer(Modifier.width(8.dp))
                        Text(z.label ?: "area", modifier = Modifier.weight(1f), color = HanselColors.Red, maxLines = 1)
                        IconButton(onClick = { vm.removeAvoidZone(z.id) }) { Icon(Icons.Default.Delete, "Remove zone", tint = HanselColors.Red) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddStop) { Text("Add stop") }
                TextButton(onClick = { savePrompt = true }) { Text("Save route") }
                Spacer(Modifier.weight(1f))
                ui.selectedRoute?.let { Text(units.eta(it.durationSeconds), style = MaterialTheme.typography.titleMedium) }
                Button(onClick = { vm.setEditMode(false) }) { Text("Done") }
            }
        }
    }
    if (savePrompt) NamePrompt("Save route as", onConfirm = { vm.saveCurrentRoute(it); savePrompt = false }, onDismiss = { savePrompt = false })
}
