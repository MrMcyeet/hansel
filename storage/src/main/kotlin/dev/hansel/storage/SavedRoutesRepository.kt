package dev.hansel.storage

import dev.hansel.model.Ids
import dev.hansel.model.RouteRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** A named route template the user can re-run (origin, stops, locks, avoid zones, options). */
data class SavedRoute(
    val id: String,
    val name: String,
    val request: RouteRequest,
    val createdAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
)

class SavedRoutesRepository(
    private val db: HanselDatabase,
    private val json: Json = StorageJson,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.savedRoutes()

    /** Persists [request] under [name]; returns the new saved-route id. */
    suspend fun save(name: String, request: RouteRequest): String {
        val id = Ids.newId("saved")
        val now = clock()
        dao.upsert(
            SavedRouteEntity(
                id = id, name = name.trim().ifEmpty { "Saved route" },
                requestJson = json.encodeToString(RouteRequest.serializer(), request),
                createdAtMillis = now, lastUsedAtMillis = now, useCount = 0,
            ),
        )
        return id
    }

    /** Most recently used first. Rows whose JSON no longer decodes are skipped rather than crashing the list. */
    fun list(): Flow<List<SavedRoute>> = dao.observeAll().map { rows -> rows.mapNotNull(::toDomain) }

    suspend fun get(id: String): SavedRoute? = dao.get(id)?.let(::toDomain)

    /** Marks the route as used now (bumps `useCount`, moves it to the top of the list). */
    suspend fun touch(id: String) = dao.touch(id, clock())

    suspend fun rename(id: String, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: String) = dao.delete(id)

    private fun toDomain(e: SavedRouteEntity): SavedRoute? {
        val request = runCatching { json.decodeFromString(RouteRequest.serializer(), e.requestJson) }.getOrNull() ?: return null
        return SavedRoute(e.id, e.name, request, e.createdAtMillis, e.lastUsedAtMillis, e.useCount)
    }
}
