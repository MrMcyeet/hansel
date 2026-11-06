package dev.hansel.map

import android.content.Context
import dev.hansel.model.BoundingBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Map tiles stay online, but two things make them survive without a connection (what the owner asked for):
 *  1. the **ambient cache** — every tile the map has shown is kept, up to [setAmbientCacheBytes]; and
 *  2. **saved areas** — explicit MapLibre offline regions ("pull the map for this area now"), listed and deletable.
 *
 * Both are the stock MapLibre offline database, so a style change invalidates nothing and the renderer reads them
 * transparently. Tiles come from Protomaps' hosted API, whose free tier is a soft 1M requests/month: zoom is capped
 * at [MAX_SAVED_ZOOM] so a county-sized save is tens of thousands of tiles, not millions.
 */
class MapOfflineStore(context: Context) {
    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SavedArea(val id: Long, val name: String, val bounds: BoundingBox, val minZoom: Double, val maxZoom: Double,
                         val completedTiles: Long, val requiredTiles: Long, val bytes: Long, val complete: Boolean, val downloading: Boolean)

    @Serializable
    private data class Meta(val name: String, val createdAtMillis: Long)

    private val _areas = MutableStateFlow<List<SavedArea>>(emptyList())
    val areas: StateFlow<List<SavedArea>> = _areas

    suspend fun setAmbientCacheBytes(bytes: Long) = suspendCancellableCoroutine<Unit> { cont ->
        manager.setMaximumAmbientCacheSize(bytes, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onError(message: String) { if (cont.isActive) cont.resume(Unit) }
        })
    }

    suspend fun clearAmbientCache() = suspendCancellableCoroutine<Unit> { cont ->
        manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onError(message: String) { if (cont.isActive) cont.resume(Unit) }
        })
    }

    private suspend fun regions(): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) { if (cont.isActive) cont.resume(offlineRegions?.toList() ?: emptyList()) }
            override fun onError(error: String) { if (cont.isActive) cont.resume(emptyList()) }
        })
    }

    private suspend fun status(r: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) { if (cont.isActive) cont.resume(status) }
            override fun onError(error: String?) { if (cont.isActive) cont.resume(null) }
        })
    }

    suspend fun refresh(): List<SavedArea> {
        val out = regions().map { r ->
            val d = r.definition
            val st = status(r)
            val meta = runCatching { json.decodeFromString<Meta>(String(r.metadata ?: ByteArray(0))) }.getOrNull()
            val b = d.bounds
            val box = if (b == null) BoundingBox(0.0, 0.0, 0.0, 0.0) else BoundingBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
            SavedArea(
                id = r.id, name = meta?.name ?: "Area ${r.id}",
                bounds = box,
                minZoom = d.minZoom, maxZoom = d.maxZoom,
                completedTiles = st?.completedTileCount ?: 0, requiredTiles = st?.requiredResourceCount ?: 0,
                bytes = st?.completedResourceSize ?: 0, complete = st?.isComplete ?: false,
                downloading = st?.downloadState == OfflineRegion.STATE_ACTIVE,
            )
        }
        _areas.value = out
        return out
    }

    /**
     * Save tiles for [bounds] at zooms [minZoom]..[maxZoom]. Progress is pushed through [onProgress] as
     * (completedTiles, requiredTiles, bytes). Returns the region id.
     */
    suspend fun saveArea(styleUrl: String, name: String, bounds: BoundingBox, pixelRatio: Float,
                         minZoom: Double = 6.0, maxZoom: Double = MAX_SAVED_ZOOM,
                         onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }): Result<Long> {
        val def = OfflineTilePyramidRegionDefinition(styleUrl, LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west), minZoom, maxZoom, pixelRatio)
        val meta = json.encodeToString(Meta(name, System.currentTimeMillis())).toByteArray()
        val region = suspendCancellableCoroutine<Result<OfflineRegion>> { cont ->
            manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) { if (cont.isActive) cont.resume(Result.success(offlineRegion)) }
                override fun onError(error: String) { if (cont.isActive) cont.resume(Result.failure(IllegalStateException(error))) }
            })
        }.getOrElse { return Result.failure(it) }
        val done = suspendCancellableCoroutine<Result<Unit>> { cont ->
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    onProgress(status.completedTileCount, status.requiredResourceCount, status.completedResourceSize)
                    if (status.isComplete && cont.isActive) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); cont.resume(Result.success(Unit)) }
                }
                override fun onError(error: OfflineRegionError) {
                    if (cont.isActive) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); cont.resume(Result.failure(IllegalStateException("${error.reason}: ${error.message}"))) }
                }
                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    if (cont.isActive) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); cont.resume(Result.failure(IllegalStateException("tile limit $limit exceeded"))) }
                }
            })
            cont.invokeOnCancellation { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
        refresh()
        return done.map { region.id }
    }

    suspend fun delete(id: Long): Boolean {
        val r = regions().firstOrNull { it.id == id } ?: return false
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { if (cont.isActive) cont.resume(true) }
                override fun onError(error: String) { if (cont.isActive) cont.resume(false) }
            })
        }
        refresh()
        return ok
    }

    companion object {
        /** z14 shows every residential street in Protomaps' basemap; z15 quadruples the tile count for little gain. */
        const val MAX_SAVED_ZOOM = 14.0
        const val DEFAULT_AMBIENT_CACHE_BYTES = 512L * 1024 * 1024
    }
}
