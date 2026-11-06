package dev.hansel.routing.graphhopper

import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.util.Constants
import com.graphhopper.util.CustomModel
import com.graphhopper.util.TurnCostsConfig
import java.io.File

/**
 * The single source of truth for how the on-device GraphHopper graph is built and opened. The importer (offline
 * module) and [LocalGraphHopperEngine] must use exactly this: GraphHopper refuses to `load()` a graph whose stored
 * `profiles` string or CH profile hash differs from the configured one, and the hash covers the profile name,
 * weighting, turn-cost config and the custom model's text.
 *
 * Mirrors `dev-server/graphhopper/config.yml` (GraphHopper 11.x): one `car` profile with the built-in `car.json`
 * custom model, motorcar turn restrictions with 60 s U-turn cost, CH ("speed mode") prepared, no LM.
 *
 * ## Why `newGraphHopper` instead of `GraphHopper()`
 * GraphHopper compiles every custom model (including the profile's own) into a Java class with Janino at run time.
 * ART cannot define JVM bytecode classes, so on Android that path throws. [HanselGraphHopper] swaps in
 * [InterpretedWeightingFactory], which evaluates the custom-model statements directly (see
 * `InterpretedCustomModel.kt`) and only falls back to Janino for syntax it does not understand. Both the importer
 * (CH preparation needs the weighting) and the engine must use it.
 */
object GhProfile {
    const val NAME = "car"

    /** From `dev-server/graphhopper/config.yml`: what `car.json` needs plus what the request-time models reference. */
    const val ENCODED_VALUES =
        "car_access, car_average_speed, road_access, road_class, road_class_link, road_environment, roundabout, toll, surface, max_speed, ferry_speed"

    /** Car-only graph: skipping non-motorised ways shrinks the graph and speeds up import + CH preparation. */
    val IGNORED_HIGHWAYS = listOf("footway", "construction", "cycleway", "path", "steps", "pedestrian", "bridleway")

    const val MIN_NETWORK_SIZE = 200

    /** Flexible-mode guard rails (config.yml `routing.*`). */
    const val MAX_VISITED_NODES = 5_000_000
    const val NON_CH_MAX_WAYPOINT_DISTANCE_M = 1_000_000
    const val ROUTING_TIMEOUT_MS = 60_000L

    val TURN_COST_VEHICLE_TYPES = listOf("motorcar", "motor_vehicle")
    const val U_TURN_COSTS_S = 60

    /**
     * Identical to GraphHopper 11.1's bundled `com/graphhopper/custom_models/car.json`, written out so the profile
     * hash does not depend on a classpath resource or on Jackson's number formatting.
     */
    fun carCustomModel(): CustomModel = CustomModel().apply {
        distanceInfluence = 90.0
        addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
        addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
    }

    fun carProfile(): Profile = Profile(NAME)
        .setCustomModel(carCustomModel())
        .setTurnCostsConfig(TurnCostsConfig(TURN_COST_VEHICLE_TYPES, U_TURN_COSTS_S))

    fun chProfile(): CHProfile = CHProfile(NAME)

    /**
     * Opaque identifier of "what built this graph" for `OfflineManifest.graphBuildConfig`: a new value means the
     * active graph must be rebuilt before the engine can load it.
     */
    fun buildConfigId(): String =
        "gh${Constants.VERSION};profile=${carProfile().getVersion()};ev=${ENCODED_VALUES.replace(" ", "")};ch=edge"

    /**
     * Full configuration for both import and load.
     * @param graphDir the GraphHopper graph folder (`OfflineLayout.activeGraph` or `buildGraph`).
     * @param osmFile the merged `.osm.pbf` (importer) or an `.osm`/`.osm.xml` (tests); null when only loading.
     * @param dataAccess `MMAP` for loading on the phone (the graph stays off-heap; `setAllowWrites(false)` turns it into
     *   `MMAP_RO`). `RAM_STORE` is faster for import on machines with heap to spare.
     * @param minNetworkSize import-only (not part of the profile hash): subnetworks with fewer edges are removed.
     *   Tests with tiny hand-written maps pass 0; real imports keep [MIN_NETWORK_SIZE].
     */
    fun config(graphDir: File, osmFile: File? = null, dataAccess: String = "MMAP", minNetworkSize: Int = MIN_NETWORK_SIZE): GraphHopperConfig =
        GraphHopperConfig().apply {
            putObject("graph.location", graphDir.absolutePath)
            if (osmFile != null) putObject("datareader.file", osmFile.absolutePath)
            putObject("graph.dataaccess.default_type", dataAccess)
            putObject("graph.encoded_values", ENCODED_VALUES)
            putObject("import.osm.ignored_highways", IGNORED_HIGHWAYS.joinToString(","))
            putObject("prepare.min_network_size", minNetworkSize)
            putObject("prepare.subnetworks.threads", 1)
            putObject("prepare.ch.threads", 1)
            putObject("routing.max_visited_nodes", MAX_VISITED_NODES)
            putObject("routing.non_ch.max_waypoint_distance", NON_CH_MAX_WAYPOINT_DISTANCE_M)
            putObject("routing.timeout_ms", ROUTING_TIMEOUT_MS)
            putObject("datareader.preferred_language", "en")
            setProfiles(listOf(carProfile()))
            setCHProfiles(listOf(chProfile()))
        }

    /**
     * A GraphHopper instance configured with [config]. The importer calls `importOrLoad()`/`importAndClose()` on it;
     * the engine calls `setAllowWrites(false)` then `load()`.
     * @param strictInterpreter see [HanselGraphHopper].
     * @param minNetworkSize see [config].
     * @param onStage import-pipeline stage callback (see [GraphImporter.Stage]); ignored when only loading.
     */
    fun newGraphHopper(
        graphDir: File,
        osmFile: File? = null,
        dataAccess: String = "MMAP",
        strictInterpreter: Boolean = false,
        minNetworkSize: Int = MIN_NETWORK_SIZE,
        onStage: ((GraphImporter.Stage) -> Unit)? = null,
    ): GraphHopper = HanselGraphHopper(strictInterpreter, onStage).init(config(graphDir, osmFile, dataAccess, minNetworkSize))
}

/**
 * [GraphHopper] whose weightings are produced by [InterpretedWeightingFactory] (no Janino, so it works on ART).
 *
 * GraphHopper 11.1 routes every weighting creation (`checkProfilesConsistency`, CH preparation, the `Router`) through
 * the protected `createWeightingFactory()`, so overriding it is the single seam needed.
 *
 * @param strictInterpreter when true, a custom model the interpreter cannot evaluate is an error instead of a
 *   Janino fallback; tests use it to prove the models the app sends never need Janino.
 * @param onStage called from the protected import hooks (`importOSM`, `postImportOSM`, `cleanUp`, `loadOrPrepareCH`,
 *   `flush`) so [GraphImporter] can report progress without an slf4j binding.
 */
class HanselGraphHopper(
    private val strictInterpreter: Boolean = false,
    private val onStage: ((GraphImporter.Stage) -> Unit)? = null,
) : GraphHopper() {
    override fun createWeightingFactory(): WeightingFactory =
        InterpretedWeightingFactory(baseGraph, encodingManager, janinoFallback = !strictInterpreter)

    override fun importOSM() {
        onStage?.invoke(GraphImporter.Stage.READING_OSM)
        super.importOSM()
    }

    override fun postImportOSM() {
        onStage?.invoke(GraphImporter.Stage.BUILDING_GRAPH)
        super.postImportOSM()
    }

    override fun cleanUp() {
        onStage?.invoke(GraphImporter.Stage.SUBNETWORKS)
        super.cleanUp()
    }

    override fun loadOrPrepareCH(closeEarly: Boolean) {
        onStage?.invoke(GraphImporter.Stage.PREPARING_CH)
        super.loadOrPrepareCH(closeEarly)
    }

    override fun flush() {
        onStage?.invoke(GraphImporter.Stage.FLUSHING)
        super.flush()
    }
}
