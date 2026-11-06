package dev.hansel.offline

import kotlinx.coroutines.flow.StateFlow

/** What the UI needs from the offline data pipeline; the app binds it to [OfflinePipeline] + the WorkManager job. */
interface OfflineController {
    val status: StateFlow<OfflineStatus>
    val regions: List<Region>
    data class Estimate(val downloadBytes: Long, val diskBytes: Long, val freeBytes: Long) {
        val fits: Boolean get() = freeBytes > diskBytes + 512L * 1024 * 1024
    }
    fun estimate(regionIds: List<String>): Estimate
    /** Starts (or re-runs) the install job for exactly these regions; runs in the background with a notification. */
    fun install(regionIds: List<String>)
    fun cancel()
    suspend fun removeRegion(regionId: String)
    suspend fun deleteAll()
}
