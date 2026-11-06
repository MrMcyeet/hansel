package dev.hansel

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import dev.hansel.location.LocationPermissions
import dev.hansel.model.LatLng
import dev.hansel.model.Place
import dev.hansel.model.PlaceSource
import dev.hansel.ui.HanselNavHost
import dev.hansel.ui.theme.HanselTheme
import dev.hansel.vm.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory((application as HanselApp).graph, this) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        setContent {
            HanselTheme {
                HanselNavHost(vm)
                LaunchedEffect(intent) { handleGeoIntent(intent) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGeoIntent(intent)
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = ArrayList<String>()
        if (!LocationPermissions.hasFine(this)) wanted += LocationPermissions.required
        if (Build.VERSION.SDK_INT >= 33 && !LocationPermissions.hasNotificationPermission(this)) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    /** geo:lat,lon or geo:0,0?q=lat,lon(label) from other apps. */
    private fun handleGeoIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "geo") return
        val raw = data.schemeSpecificPart ?: return
        val q = raw.substringAfter("q=", "").substringBefore("&")
        val coords = (if (q.isNotEmpty()) q else raw).substringBefore("?").substringBefore("(")
        val parts = coords.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return
        val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return
        if (lat == 0.0 && lon == 0.0) return
        val label = raw.substringAfter("(", "").substringBefore(")").ifBlank { "Shared location" }
        vm.chooseDestination(Place("geo:$lat,$lon", label, point = LatLng(lat, lon), source = PlaceSource.MAP_TAP))
    }
}
