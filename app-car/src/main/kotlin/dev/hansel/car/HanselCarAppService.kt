package dev.hansel.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point the Android Auto host binds to (declared in this module's manifest with the NAVIGATION category).
 *
 * Host validation: debug builds accept every host so the Desktop Head Unit and sideloaded hosts work. For a release
 * build use the library's allowlist of known hosts instead:
 *
 * ```
 * HostValidator.Builder(applicationContext)
 *     .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
 *     .build()
 * ```
 *
 * (`hosts_allowlist_sample` ships with androidx.car.app and lists the Google Android Auto / Automotive host
 * package + signature pairs; `addAllowedHost(packageName, digest)` adds any extra host.)
 */
class HanselCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        // Cluster displays (SessionInfo.DISPLAY_TYPE_CLUSTER) would get a map-only session; v1 serves the main display
        // only and lets the host fall back to the Trip pushed via NavigationManager for the cluster.
        return HanselCarSession()
    }
}
