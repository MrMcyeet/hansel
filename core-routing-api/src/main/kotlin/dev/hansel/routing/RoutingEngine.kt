package dev.hansel.routing

import dev.hansel.model.EngineId
import dev.hansel.model.RouteRequest
import dev.hansel.model.RoutingResult

/** What a backend can do natively. The orchestrator and corridor-lock controller fill in the rest above the engine. */
data class EngineCapabilities(
    val nativeAlternatives: Boolean,
    val throughPoints: Boolean,
    val viaPoints: Boolean,
    val avoidPolygons: Boolean,
    /** Can personalized per-edge costs be injected *into the search* (DESIGN §6.4)? Phase 5 gate. */
    val customEdgeCosts: Boolean,
    val onDevice: Boolean,
    val maxWaypoints: Int = 50,
)

/**
 * One routing backend. Implementations translate [RouteRequest] to their own API and back to the engine-independent
 * [RoutingResult]. They must never throw for routing failures; those come back as [dev.hansel.model.RoutingError].
 */
interface RoutingEngine {
    val id: EngineId
    val capabilities: EngineCapabilities
    suspend fun route(request: RouteRequest): RoutingResult

    /** Cheap liveness check used by the Engine Lab and settings screen. */
    suspend fun healthCheck(): EngineHealth
}

data class EngineHealth(val reachable: Boolean, val detail: String, val latencyMillis: Long = 0)
