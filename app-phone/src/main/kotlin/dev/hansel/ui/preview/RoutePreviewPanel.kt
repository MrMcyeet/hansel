package dev.hansel.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hansel.model.Route
import dev.hansel.navigation.DistanceUnits
import dev.hansel.storage.HanselSettings
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import dev.hansel.vm.RoutingUi
import kotlin.math.roundToInt

/** DESIGN §10 "Route preview": up to three candidate cards, provenance line, start. */
@Composable
fun RoutePreviewPanel(vm: MainViewModel, ui: RoutingUi, settings: HanselSettings) {
    Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = HanselColors.Panel, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                ui.loading && ui.routes.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = HanselColors.Teal)
                    Spacer(Modifier.width(12.dp)); Text("Routing…", color = HanselColors.Muted)
                }
                ui.error != null && ui.routes.isEmpty() -> {
                    Text(ui.error, color = HanselColors.Red)
                    Spacer(Modifier.height(8.dp))
                    Row { TextButton(onClick = { vm.retry() }) { Text("Retry") }; TextButton(onClick = { vm.clearRoute() }) { Text("Cancel") } }
                }
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ui.routes, key = { it.id }) { r ->
                            RouteCard(r, selected = r.id == ui.selectedRoute?.id, units = settings.units, onClick = { vm.selectRoute(r.id) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    ProvenanceLine(ui, settings)
                    if (ui.error != null) { Spacer(Modifier.height(6.dp)); Text(ui.error, color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.setEditMode(true) }) { Text("Edit route") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { vm.startNavigation() }, enabled = ui.selectedRoute != null, modifier = Modifier.height(48.dp)) {
                            Text("Start", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RouteCard(r: Route, selected: Boolean, units: DistanceUnits, onClick: () -> Unit, compact: Boolean = false) {
    Card(
        modifier = Modifier.width(if (compact) 180.dp else 220.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) HanselColors.PanelHi else HanselColors.Panel),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) HanselColors.Teal else HanselColors.Line),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(10.dp).height(10.dp).background(if (selected) HanselColors.Teal else HanselColors.Alt, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(8.dp))
                Text(units.eta(r.durationSeconds), style = MaterialTheme.typography.titleLarge, color = HanselColors.Text)
            }
            Text(units.display(r.distanceMeters) + (if (r.summary.hasTolls) " · tolls" else "") + (if (r.summary.hasFerry) " · ferry" else ""),
                color = HanselColors.Muted, style = MaterialTheme.typography.bodyMedium)
            Text(r.label ?: r.summary.majorRoads.take(2).joinToString(" · ").ifBlank { "route ${r.alternativeIndex + 1}" },
                color = HanselColors.Text, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            if (r.warnings.isNotEmpty()) Text(r.warnings.first(), color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ProvenanceLine(ui: RoutingUi, settings: HanselSettings) {
    val res = ui.result ?: return
    val sb = StringBuilder()
    sb.append(res.primaryEngine.name.lowercase().replaceFirstChar { it.uppercase() })
    if (res.primaryRaw.onDevice) sb.append(" · on-device") else sb.append(" · server")
    sb.append(" · ${res.primaryRaw.latencyMillis} ms")
    res.discrepancy?.let { d ->
        sb.append(" · shadow ${d.shadowEngine.name.lowercase()}: ")
        if (d.shadowFailed) sb.append("failed") else sb.append("${(d.overlap * 100).roundToInt()}% overlap, ETA ${if (d.etaDeltaSeconds >= 0) "+" else "−"}${(kotlin.math.abs(d.etaDeltaSeconds) / 60).roundToInt()} min")
    }
    sb.append(" · traffic: unavailable")
    if (ui.editor?.isCustom == true) sb.append(" · custom route")
    Text(sb.toString(), style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
}
