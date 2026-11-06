package dev.hansel.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Trip
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.hansel.navigation.NavigationHostRegistry

/**
 * One connection to a head unit. Owns the map [CarMapRenderer] (registered as the host's SurfaceCallback) and the
 * [NavigationManager] handshake; the screen stack is rooted at [CarNavigationScreen]. The phone and this session are
 * both projections of the single [dev.hansel.navigation.NavigationSession] in [NavigationHostRegistry] (DESIGN §8).
 */
class HanselCarSession : Session() {

    val mapRenderer: CarMapRenderer by lazy { CarMapRenderer() }
    private val navigationManager: NavigationManager by lazy { carContext.getCarService(NavigationManager::class.java) }
    private var navigationStartedSent = false

    private val navCallback = object : NavigationManagerCallback {
        override fun onStopNavigation() {
            // Host (e.g. "stop navigation" via Assistant, or another nav app taking over) asked us to stop.
            navigationStartedSent = false
            NavigationHostRegistry.session?.stop()
        }

        override fun onAutoDriveEnabled() {
            // Play-review hook: the app's location pipeline is expected to simulate driving the active route.
            CarHooks.autoDriveRequested = true
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(mapRenderer)
                navigationManager.setNavigationManagerCallback(ContextCompat.getMainExecutor(carContext), navCallback)
                mapRenderer.onDarkModeChanged(carContext.isDarkMode)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                CarHooks.autoDriveRequested = false
                if (navigationStartedSent) runCatching { navigationManager.navigationEnded() }
                navigationStartedSent = false
                runCatching { navigationManager.clearNavigationManagerCallback() }
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = CarNavigationScreen(carContext, this)

    override fun onNewIntent(intent: Intent) {
        // Navigation intents (geo:, google.navigation:) are a phone-side concern in v1: the phone picks the
        // destination and the car mirrors it. Nothing to do here beyond staying on the root screen.
        carContext.getCarService(androidx.car.app.ScreenManager::class.java).popToRoot()
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        mapRenderer.onDarkModeChanged(carContext.isDarkMode)
    }

    // ---- NavigationManager plumbing used by the screen ---------------------------------------------------------

    fun notifyNavigating(active: Boolean) {
        if (active && !navigationStartedSent) {
            navigationStartedSent = true
            runCatching { navigationManager.navigationStarted() }
        } else if (!active && navigationStartedSent) {
            navigationStartedSent = false
            runCatching { navigationManager.navigationEnded() }
        }
    }

    fun updateTrip(trip: Trip) {
        if (navigationStartedSent) runCatching { navigationManager.updateTrip(trip) }
    }
}
