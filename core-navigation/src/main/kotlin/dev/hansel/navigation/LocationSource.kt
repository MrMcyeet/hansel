package dev.hansel.navigation

import dev.hansel.model.Fix
import kotlinx.coroutines.flow.Flow

/** Stream of position fixes. The Android implementation wraps fused location; tests feed canned fixes. */
interface LocationSource {
    /** Hot while collected; emits at ~1 Hz during navigation. Raw measurements, never clamped. */
    val fixes: Flow<Fix>
    /** Last fix seen, if any, for instant map centering before the stream warms up. */
    val lastKnown: Fix?
}

/**
 * Process-wide handle to the single active [NavigationSession] so the Android Auto service, the foreground
 * service and the phone UI all project the same state (DESIGN §8). The Application sets it at startup.
 */
object NavigationHostRegistry {
    @Volatile var session: NavigationSession? = null
    @Volatile var units: DistanceUnits = DistanceUnits.IMPERIAL
    @Volatile var locationSource: LocationSource? = null
}
