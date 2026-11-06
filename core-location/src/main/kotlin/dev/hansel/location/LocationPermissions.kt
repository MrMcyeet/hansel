package dev.hansel.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Permission checks. Background location is deliberately not in [required]; passive recording is a later opt-in. */
object LocationPermissions {
    /** What the app asks for up front: foreground fine + coarse. */
    val required: Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Asked only when the user turns on passive trip recording. Must be requested separately on API 30+. */
    const val BACKGROUND: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    fun hasFine(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasCoarse(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(context: Context): Boolean = granted(context, BACKGROUND)

    /** POST_NOTIFICATIONS is runtime on 33+; the foreground-service notification still shows without it, but ask anyway. */
    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Everything a navigation foreground service needs before it is started. */
    fun canStartNavigationService(context: Context): Boolean = hasFine(context)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
