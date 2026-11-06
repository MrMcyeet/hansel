package dev.hansel.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.ItemList
import dev.hansel.car.CarUi.titledWithBack
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry

/**
 * One-tap hazard report (DESIGN §7.4: quick categories, no typing while driving). A tap hands the category and the
 * current snapped position to [CarHooks.reportSink] and pops back to the map.
 */
class ReportScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val list = ItemList.Builder()
        HazardCategory.entries.take(limit).forEach { category ->
            list.addItem(
                Row.Builder()
                    .setTitle(category.label)
                    .setOnClickListener(OnClickListener { report(category) })
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .titledWithBack(carContext, "Report")
            .setSingleList(list.build())
            .build()
    }

    private fun report(category: HazardCategory) {
        val state = NavigationHostRegistry.session?.state?.value
        val position = when (state) {
            is NavState.Navigating -> state.progress?.snapped?.point ?: state.lastFix?.point
            else -> NavigationHostRegistry.locationSource?.lastKnown?.point
        }
        runCatching { CarHooks.reportSink.report(category, position, System.currentTimeMillis()) }
        CarToast.makeText(carContext, "Reported: ${category.label}", CarToast.LENGTH_SHORT).show()
        finish()
    }
}
