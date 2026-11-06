package dev.hansel.car

import dev.hansel.model.LatLng

/** Quick-report categories offered on the car screen (DESIGN §7.4: one-tap, no free text while driving). */
enum class HazardCategory(val label: String) {
    POLICE("Police"),
    CRASH("Crash"),
    DEBRIS("Debris on road"),
    VEHICLE_ON_SHOULDER("Vehicle on shoulder"),
    ROADWORK("Roadwork"),
    CLOSURE("Closure"),
}

/** Receives a one-tap hazard report from the car UI. The app wires this to its personal-reports store. */
fun interface CarReportSink {
    fun report(category: HazardCategory, position: LatLng?, timeMillis: Long)
}

/**
 * Process-wide hooks the Application sets before Android Auto connects. Everything has a safe default so the car
 * service works (with no-op side effects) even if the app forgets to configure it.
 */
object CarHooks {
    /** Default: drop reports. */
    @Volatile var reportSink: CarReportSink = CarReportSink { _, _, _ -> }

    /**
     * Factory for the map renderer drawn on the car Surface. Default is the Canvas renderer; swap in a MapLibre-backed
     * [CarSurfaceRenderer] once it is proven on a head unit (see README "Swapping in MapLibre").
     */
    @Volatile var rendererFactory: () -> CarSurfaceRenderer = { CanvasSurfaceRenderer() }

    /**
     * Set by the host's `onAutoDriveEnabled` (Play-review "auto drive" mode). The app's location pipeline may read this
     * to simulate driving along the active route; the car module only records the request.
     */
    @Volatile var autoDriveRequested: Boolean = false

    /** Set true for left-hand-traffic regions so roundabouts map to the clockwise car maneuver types. */
    @Volatile var drivesOnLeft: Boolean = false
}
