package dev.hansel.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hansel.model.Route
import dev.hansel.navigation.DistanceUnits
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry
import dev.hansel.navigation.NavigationSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root screen: a [NavigationTemplate] over the custom map surface, driven by `NavigationHostRegistry.session.state`.
 *
 * Template per state (all within the navigation action-strip quota of 4 actions / 1 primary):
 * - Idle: MessageInfo "Start a route on your phone", strip = [Recenter].
 * - Previewing: MessageInfo "Ready to go" + selected route summary, strip = [Start*, Routes].
 * - Navigating: RoutingInfo (current + next step) + destination TravelEstimate, strip = [Stop, Reroute, Routes, Report].
 * - Arrived: MessageInfo "Arrived", strip = [Done].
 *
 * The same step/estimate is pushed through `NavigationManager.updateTrip` so the cluster and HUN show guidance.
 */
class CarNavigationScreen(
    carContext: CarContext,
    private val carSession: HanselCarSession,
) : Screen(carContext) {

    private val units: DistanceUnits get() = NavigationHostRegistry.units
    private val session: NavigationSession? get() = NavigationHostRegistry.session
    private var lastTemplateKey: Any? = null
    private var inPanMode = false

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var s = session
                while (s == null) { // App has not registered the session yet (cold start from the car).
                    delay(500)
                    s = session
                }
                s.state.collect { onState(it, s) }
            }
        }
    }

    private fun onState(state: NavState, s: NavigationSession) {
        carSession.mapRenderer.submit(state, s.activeRequest)
        val navigating = state is NavState.Navigating
        carSession.notifyNavigating(navigating)
        if (state is NavState.Navigating) {
            carSession.updateTrip(CarTemplates.trip(state, units, s.activeRequest?.destination?.name))
        }
        val key = templateKey(state)
        if (key != lastTemplateKey) {
            lastTemplateKey = key
            invalidate()
        }
    }

    /** Anything that changes what the template shows; progress-only changes that don't alter text are skipped. */
    private fun templateKey(state: NavState): Any = when (state) {
        NavState.Idle -> "idle"
        is NavState.Previewing -> listOf("preview", state.routes.map { it.id }, state.selectedIndex)
        is NavState.Arrived -> listOf("arrived", state.route.id)
        is NavState.Navigating -> {
            val p = state.progress
            listOf(
                "nav", state.route.id, state.rerouting, state.alternatives.map { it.id },
                p?.upcomingManeuverIndex, p?.let { units.display(it.distanceToUpcomingMeters) },
                p?.let { units.display(it.distanceRemainingMeters) }, p?.let { (it.durationRemainingSeconds / 60).toInt() },
            )
        }
    }

    override fun onGetTemplate(): Template {
        val s = session
        val state = s?.state?.value ?: NavState.Idle
        val b = NavigationTemplate.Builder()
        if (carContext.carAppApiLevel >= 2) {
            b.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            b.setPanModeListener { panning ->
                inPanMode = panning
                if (!panning) carSession.mapRenderer.resetView()
            }
        }
        when (state) {
            NavState.Idle -> b
                .setNavigationInfo(
                    MessageInfo.Builder("Start a route on your phone")
                        .setText("Hansel mirrors the phone's active route here.")
                        .build(),
                )
                .setActionStrip(strip(action("Recenter") { carSession.mapRenderer.resetView() }))

            is NavState.Previewing -> {
                val r = state.selected
                b.setNavigationInfo(
                    MessageInfo.Builder("Ready to go")
                        .setText("${routeLabel(r, state.selectedIndex)} · ${units.eta(r.durationSeconds)} · ${units.display(r.distanceMeters)}")
                        .build(),
                )
                val actions = mutableListOf(
                    action("Start", primary = true) { s?.start() },
                )
                if (state.routes.size > 1) actions += action("Routes") { screenManager.push(AlternativesScreen(carContext, preview = true)) }
                b.setActionStrip(strip(*actions.toTypedArray()))
            }

            is NavState.Navigating -> {
                val p = state.progress
                if (p == null || state.rerouting) {
                    b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                } else {
                    b.setNavigationInfo(CarTemplates.routingInfo(p, units))
                    b.setDestinationTravelEstimate(CarTemplates.destinationEstimate(p, units))
                }
                val actions = mutableListOf(
                    action("Stop") { s?.stop() },
                    action("Reroute") {
                        s?.forceReroute("car")
                        CarToast.makeText(carContext, "Rerouting", CarToast.LENGTH_SHORT).show()
                    },
                )
                if (state.alternatives.isNotEmpty()) actions += action("Routes") { screenManager.push(AlternativesScreen(carContext, preview = false)) }
                actions += action("Report") { screenManager.push(ReportScreen(carContext)) }
                b.setActionStrip(strip(*actions.toTypedArray()))
            }

            is NavState.Arrived -> b
                .setNavigationInfo(
                    MessageInfo.Builder("Arrived")
                        .setText(s?.activeRequest?.destination?.name ?: "You have reached your destination.")
                        .build(),
                )
                .setActionStrip(strip(action("Done", primary = true) { s?.stop() }))
        }
        return b.build()
    }

    private fun strip(vararg actions: Action): ActionStrip {
        val sb = ActionStrip.Builder()
        actions.take(4).forEach { sb.addAction(it) } // navigation template quota: 4 actions
        return sb.build()
    }

    private fun action(title: String, primary: Boolean = false, onClick: () -> Unit): Action {
        val ab = Action.Builder().setTitle(title).setOnClickListener(OnClickListener { onClick() })
        if (primary && carContext.carAppApiLevel >= 4) ab.setFlags(Action.FLAG_PRIMARY) // FLAG_PRIMARY is Car API 4+
        return ab.build()
    }

    companion object {
        fun routeLabel(r: Route, index: Int): String = r.label?.takeIf { it.isNotBlank() } ?: "Route ${index + 1}"
    }
}
