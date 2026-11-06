package dev.hansel.ui.nav

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hansel.navigation.DistanceUnits
import dev.hansel.navigation.NavState
import dev.hansel.navigation.glyph
import dev.hansel.ui.preview.RouteCard
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DESIGN §10 "Navigation": clear next turn, distance, then-next. */
@Composable
fun ManeuverBanner(nav: NavState.Navigating, units: DistanceUnits) {
    val p = nav.progress
    Surface(shape = RoundedCornerShape(18.dp), color = HanselColors.TealDeep, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            if (p == null) { Text("Acquiring position…", color = HanselColors.Text); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.upcoming.type.glyph(), fontSize = 40.sp, color = HanselColors.Teal, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(units.display(p.distanceToUpcomingMeters), style = MaterialTheme.typography.headlineMedium, color = HanselColors.Text)
                    Text(p.upcoming.instruction, style = MaterialTheme.typography.bodyMedium, color = HanselColors.Text, maxLines = 2)
                }
            }
            p.following?.let { f ->
                Spacer(Modifier.height(6.dp))
                Text("then ${f.type.glyph()} ${f.instruction}", style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted, maxLines = 1)
            }
            if (nav.rerouting) Text("Rerouting (${nav.rerouteReason})…", color = HanselColors.Amber, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun NavigationBar(vm: MainViewModel, nav: NavState.Navigating, units: DistanceUnits) {
    var showAlternatives by remember { mutableStateOf(false) }
    val p = nav.progress
    Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = HanselColors.Panel, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (showAlternatives && nav.alternatives.isNotEmpty()) {
                Text("Alternatives", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nav.alternatives, key = { it.id }) { r ->
                        RouteCard(r, selected = false, units = units, compact = true, onClick = { vm.switchToAlternative(r); showAlternatives = false })
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val eta = p?.let { System.currentTimeMillis() + (it.durationRemainingSeconds * 1000).toLong() }
                    Text(eta?.let { SimpleDateFormat("h:mm a", Locale.US).format(Date(it)) } ?: "—", style = MaterialTheme.typography.headlineMedium, color = HanselColors.Teal)
                    Text(p?.let { "${units.eta(it.durationRemainingSeconds)} · ${units.display(it.distanceRemainingMeters)}" } ?: "", color = HanselColors.Muted)
                }
                TextButton(onClick = { showAlternatives = !showAlternatives }, enabled = nav.alternatives.isNotEmpty()) { Text("Alt (${nav.alternatives.size})") }
                TextButton(onClick = { vm.forceReroute() }) { Text("Reroute") }
                Button(onClick = { vm.stopNavigation() }, colors = ButtonDefaults.buttonColors(containerColor = HanselColors.Red, contentColor = HanselColors.Text)) {
                    Text("End", fontWeight = FontWeight.SemiBold)
                }
            }
            Text("Traffic: unavailable · Report: coming in Phase 4", style = MaterialTheme.typography.labelSmall, color = HanselColors.Muted)
        }
    }
}
