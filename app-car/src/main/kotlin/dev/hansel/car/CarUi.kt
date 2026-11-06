package dev.hansel.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ListTemplate

/** Small template helpers shared by the sub-screens. */
internal object CarUi {
    /**
     * `ListTemplate.Builder.setHeader(Header)` is Car API 7+; older hosts still understand the deprecated
     * `setTitle` + `setHeaderAction` pair, so pick by the host's API level.
     */
    @Suppress("DEPRECATION")
    fun ListTemplate.Builder.titledWithBack(carContext: CarContext, title: String): ListTemplate.Builder =
        if (carContext.carAppApiLevel >= 7) {
            setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        } else {
            setTitle(title).setHeaderAction(Action.BACK)
        }
}
