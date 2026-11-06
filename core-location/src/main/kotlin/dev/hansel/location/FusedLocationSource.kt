package dev.hansel.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.hansel.model.Fix
import dev.hansel.model.LatLng
import dev.hansel.navigation.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [LocationSource] over Google's fused provider at high accuracy. One upstream subscription is shared by every
 * collector (map, navigation session, foreground service, recorder) and torn down [stopTimeoutMillis] after the last
 * one leaves, so a configuration change does not restart GPS.
 *
 * Permissions: the caller must have granted `ACCESS_FINE_LOCATION` (see [LocationPermissions]) before collecting
 * [fixes]; this class does not check and the provider will throw `SecurityException` otherwise.
 *
 * Raw measurements only: speed and bearing are passed through as the provider reports them, never clamped (DESIGN §6.1).
 */
class FusedLocationSource(
    context: Context,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 1_000,
    private val minUpdateMillis: Long = 500,
    private val stopTimeoutMillis: Long = 5_000,
) : LocationSource {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @Volatile override var lastKnown: Fix? = null
        private set

    @SuppressLint("MissingPermission") // caller checks LocationPermissions.hasFine before collecting
    private val upstream: Flow<Fix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(minUpdateMillis)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) trySend(loc.toFix())
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    override val fixes: Flow<Fix> = upstream
        .onEach { lastKnown = it }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = stopTimeoutMillis, replayExpirationMillis = 0), replay = 1)

    /** Seeds [lastKnown] from the provider's cached location so the map can center before the stream warms up. */
    @SuppressLint("MissingPermission")
    suspend fun primeLastKnown(): Fix? {
        val cached = suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        val fix = cached?.toFix() ?: return lastKnown
        if (lastKnown == null || (lastKnown?.timeMillis ?: 0) < fix.timeMillis) lastKnown = fix
        return lastKnown
    }

    companion object {
        fun Location.toFix(): Fix = Fix(
            point = LatLng(latitude, longitude),
            timeMillis = time,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            speedMps = if (hasSpeed()) speed else null,
            bearing = if (hasBearing()) bearing else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            provider = provider,
            isMock = isMock, // API 31+ (minSdk 31)
        )
    }
}
