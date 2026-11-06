package dev.hansel.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.hansel.model.Route
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry

/**
 * One-tap route alternatives (DESIGN §8). In preview mode the rows are the computed routes and a tap selects one;
 * during guidance the rows are the alternatives still on offer and a tap switches to it (`session.switchTo`).
 * Row count is capped by the host's list content limit.
 */
class AlternativesScreen(carContext: CarContext, private val preview: Boolean) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val session = NavigationHostRegistry.session
        val units = NavigationHostRegistry.units
        val state = session?.state?.value
        val routes: List<Route> = when {
            state is NavState.Previewing -> state.routes
            state is NavState.Navigating -> listOf(state.route) + state.alternatives
            else -> emptyList()
        }
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val activeId = when (state) {
            is NavState.Previewing -> state.selected.id
            is NavState.Navigating -> state.route.id
            else -> null
        }

        val list = ItemList.Builder().setNoItemsMessage("No alternatives right now")
        routes.take(limit).forEachIndexed { i, r ->
            val title = CarNavigationScreen.routeLabel(r, i) + if (r.id == activeId) "  (current)" else ""
            val row = Row.Builder()
                .setTitle(title)
                .addText("${units.eta(r.durationSeconds)} · ${units.display(r.distanceMeters)}" + tags(r))
                .setOnClickListener(OnClickListener {
                    if (preview) session?.select(i) else if (r.id != activeId) session?.switchTo(r)
                    finish()
                })
            list.addItem(row.build())
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(if (preview) "Choose a route" else "Switch route")
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            )
            .setSingleList(list.build())
            .build()
    }

    private fun tags(r: Route): String {
        val t = buildList {
            if (r.summary.hasTolls) add("tolls")
            if (r.summary.hasFerry) add("ferry")
            if (r.summary.hasUnpaved) add("unpaved")
        }
        return if (t.isEmpty()) "" else " · " + t.joinToString(", ")
    }
}
