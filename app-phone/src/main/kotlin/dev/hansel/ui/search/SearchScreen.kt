package dev.hansel.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.hansel.HanselApp
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import dev.hansel.ui.theme.HanselColors
import dev.hansel.vm.MainViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

enum class SearchMode { DESTINATION, ADD_STOP }

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(vm: MainViewModel, mode: SearchMode, onDone: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as HanselApp).graph
    val recents by graph.recentPlaces.recents(15).collectAsState(initial = emptyList())
    val saved by graph.recentPlaces.saved().collectAsState(initial = emptyList())
    val pos by vm.position.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val queries = remember { MutableStateFlow("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        queries.debounce(350).collectLatest { q ->
            if (q.length < 3) { results = emptyList(); return@collectLatest }
            loading = true; error = null
            val geocoder = graph.geocoder()
            results = geocoder.search(q, pos?.point, limit = 10)
            loading = false
            if (results.isEmpty()) error = "No results (is the Nominatim server reachable? Settings → Servers)"
        }
    }

    fun pick(p: Place) {
        when (mode) { SearchMode.DESTINATION -> vm.chooseDestination(p); SearchMode.ADD_STOP -> vm.addStop(p) }
        onDone()
    }

    Scaffold(containerColor = HanselColors.Ink) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it; queries.value = it },
                    modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    placeholder = { Text(if (mode == SearchMode.ADD_STOP) "Add a stop" else "Address or place") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { queries.value = query }),
                )
            }
            if (loading) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Searching…", color = HanselColors.Muted)
            }
            error?.let { Text(it, color = HanselColors.Amber, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall) }
            LazyColumn {
                if (results.isNotEmpty()) {
                    items(results, key = { it.id }) { p -> PlaceRow(p, ::pick) }
                } else {
                    if (saved.isNotEmpty()) { item { SectionLabel("Saved") }; items(saved, key = { "s" + it.id }) { p -> PlaceRow(p, ::pick) } }
                    if (recents.isNotEmpty()) { item { SectionLabel("Recent") }; items(recents, key = { "r" + it.id }) { p -> PlaceRow(p, ::pick) } }
                }
            }
        }
    }
}

@Composable private fun SectionLabel(s: String) { Text(s, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }

@Composable
private fun PlaceRow(p: Place, onPick: (Place) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onPick(p) }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (p.source) { PlaceSource.SAVED -> Icons.Default.Star; PlaceSource.RECENT -> Icons.Default.History; else -> Icons.Default.Place }
            Icon(icon, contentDescription = null, tint = HanselColors.Teal)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(p.name, color = HanselColors.Text, maxLines = 1)
                p.address?.let { Text(it, color = HanselColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
        HorizontalDivider(color = HanselColors.Line)
    }
}
