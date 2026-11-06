package dev.hansel.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.hansel.ui.home.MapScreen
import dev.hansel.ui.lab.EngineLabScreen
import dev.hansel.ui.lab.PlaceholderLabScreen
import dev.hansel.ui.offline.OfflineDataScreen
import dev.hansel.ui.offline.OfflineDataScreen
import dev.hansel.ui.search.SearchMode
import dev.hansel.ui.search.SearchScreen
import dev.hansel.ui.settings.SettingsScreen
import dev.hansel.vm.MainViewModel

object Routes {
    const val MAP = "map"
    const val SEARCH = "search/{mode}"
    fun search(mode: SearchMode) = "search/${mode.name}"
    const val SETTINGS = "settings"
    const val ENGINE_LAB = "lab/engine"
    const val TRAFFIC_LAB = "lab/traffic"
    const val LEARNING_LAB = "lab/learning"
    const val DOWNLOADS = "downloads"
}

/** The map screen is the single long-lived host (so the MapView survives); everything else is pushed on top. */
@Composable
fun HanselNavHost(vm: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAP) {
        composable(Routes.MAP) {
            MapScreen(
                vm = vm,
                onSearch = { mode -> nav.navigate(Routes.search(mode)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenEngineLab = { nav.navigate(Routes.ENGINE_LAB) },
                onOpenTrafficLab = { nav.navigate(Routes.TRAFFIC_LAB) },
                onOpenLearningLab = { nav.navigate(Routes.LEARNING_LAB) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
            )
        }
        composable(Routes.SEARCH) { entry ->
            val mode = SearchMode.valueOf(entry.arguments?.getString("mode") ?: SearchMode.DESTINATION.name)
            SearchScreen(vm, mode, onDone = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(vm, onBack = { nav.popBackStack() }, onOpenOffline = { nav.navigate(Routes.DOWNLOADS) }) }
        composable(Routes.ENGINE_LAB) { EngineLabScreen(vm, onBack = { nav.popBackStack() }) }
        composable(Routes.TRAFFIC_LAB) {
            PlaceholderLabScreen("Traffic lab", "Phase 4", listOf(
                "HERE / TomTom adapters behind one TrafficSource interface",
                "Coverage, freshness and provenance per observation; fresh / stale / unavailable badges",
                "Side-by-side overlays over a familiar corridor and an unfamiliar one",
                "Personal hazard reports with TTL; never labelled live when cached",
            ), onBack = { nav.popBackStack() })
        }
        composable(Routes.LEARNING_LAB) {
            PlaceholderLabScreen("Learning lab", "Phase 5", listOf(
                "Opt-in trip recorder is already wired (Settings → Record trips); samples keep raw speeds",
                "Directional segment catalog and map matching",
                "Per-segment p50/p80 traversal times by time-of-week bucket",
                "Separate toggles: personalized ETA vs personalized route selection",
                "Observed-behavior ETA vs legal-speed-constrained profile, always labelled",
            ), onBack = { nav.popBackStack() })
        }
        composable(Routes.DOWNLOADS) { OfflineDataScreen(vm, onBack = { nav.popBackStack() }) }
    }
}
