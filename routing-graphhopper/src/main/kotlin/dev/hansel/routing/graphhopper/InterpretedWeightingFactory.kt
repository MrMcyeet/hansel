package dev.hansel.routing.graphhopper

import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.routing.weighting.custom.CustomWeighting2
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.CustomModel
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters
import com.graphhopper.util.TurnCostsConfig

/**
 * Drop-in replacement for GraphHopper 11.1's `DefaultWeightingFactory` that builds the `CustomWeighting` from an
 * [InterpretedCustomModel] instead of `CustomModelParser.createWeightingParameters` (Janino). Everything else —
 * merging the request `custom_model` into the profile's model, `heading_penalty`, the turn-cost provider, `cm_version`
 * — mirrors `DefaultWeightingFactory.createWeighting` as disassembled from the 11.1 jar.
 *
 * @param janinoFallback when true, a model the interpreter cannot parse is delegated to `DefaultWeightingFactory`
 *   (works on the JVM, throws on ART); when false it is an [IllegalArgumentException] naming the expression.
 */
class InterpretedWeightingFactory(
    private val graph: BaseGraph,
    private val encodingManager: EncodingManager,
    private val janinoFallback: Boolean,
) : WeightingFactory {
    private val janino: WeightingFactory by lazy { DefaultWeightingFactory(graph, encodingManager) }

    override fun createWeighting(profile: Profile, requestHints: PMap, disableTurnCosts: Boolean): Weighting {
        val hints = PMap().apply { putAll(profile.hints); putAll(requestHints) }
        val weighting = profile.weighting.lowercase()
        if (weighting.isEmpty()) throw IllegalArgumentException("You have to specify a weighting")
        if (weighting != CustomWeighting.NAME) {
            // GraphHopper 11 only knows weighting=custom; let it produce its own migration messages.
            return janino.createWeighting(profile, requestHints, disableTurnCosts)
        }

        val queryModel = requestHints.getObject<CustomModel?>(CustomModel.KEY, null)
        val tcc = profile.turnCostsConfig
        if (tcc != null && !tcc.isAllowTurnPenaltyInRequest && queryModel != null && queryModel.turnPenalty.isNotEmpty()) {
            throw IllegalArgumentException("The turn_penalty section of the custom model is not allowed in the request for profile '${profile.name}'")
        }
        val model = CustomModel.merge(profile.customModel, queryModel)
        if (requestHints.has(Parameters.Routing.HEADING_PENALTY)) {
            model.headingPenalty = requestHints.getDouble(Parameters.Routing.HEADING_PENALTY, Parameters.Routing.DEFAULT_HEADING_PENALTY)
        }

        val interpreted = try {
            InterpretedCustomModel.compile(model, encodingManager)
        } catch (e: UnsupportedCustomModelException) {
            if (janinoFallback) return janino.createWeighting(profile, requestHints, disableTurnCosts)
            throw IllegalArgumentException("custom model not supported by the on-device interpreter: ${e.message}", e)
        }
        val params = interpreted.toParameters()

        val turnCostProvider: TurnCostProvider = if (profile.hasTurnCosts() && !disableTurnCosts) {
            val turnRestrictionEnc = encodingManager.getTurnBooleanEncodedValue(TurnRestriction.key(profile.name))
                ?: throw IllegalArgumentException("Cannot find turn restriction encoded value for profile '${profile.name}'. Make sure the profile has turn_costs enabled.")
            val uTurnCosts = hints.getInt(Parameters.Routing.U_TURN_COSTS, profile.turnCostsConfig.uTurnCosts)
            val config = TurnCostsConfig(profile.turnCostsConfig).setUTurnCosts(uTurnCosts)
            DefaultTurnCostProvider(turnRestrictionEnc, graph, config, params.turnPenaltyMapping)
        } else {
            if (model.turnPenalty.isNotEmpty()) throw IllegalArgumentException("The turn_penalty section of the custom model requires a profile with turn costs: '${profile.name}'")
            TurnCostProvider.NO_TURN_COST_PROVIDER
        }

        return if (hints.has(CM_VERSION)) {
            if (hints.getString(CM_VERSION, "") != "2") throw IllegalArgumentException("cm_version: \"2\" is required")
            CustomWeighting2(turnCostProvider, params)
        } else {
            CustomWeighting(turnCostProvider, params)
        }
    }

    private companion object {
        const val CM_VERSION = "cm_version"
    }
}
