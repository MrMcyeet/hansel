package dev.hansel.routing.graphhopper

import com.graphhopper.json.Statement
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadEnvironment
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.Toll
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.CustomModel
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.JsonFeature
import com.graphhopper.util.JsonFeatureCollection
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Parser + evaluator unit tests on a two-edge in-memory graph; no OSM import, no Janino. */
class InterpretedCustomModelTest {
    private val carAccess = VehicleAccess.create("car")
    private val carSpeed = VehicleSpeed.create("car", 5, 5.0, true)
    private val roadClass = RoadClass.create()
    private val toll = Toll.create()
    private val surface = Surface.create()
    private val roadEnv = RoadEnvironment.create()
    private val maxSpeed = MaxSpeed.create()
    private val em: EncodingManager = EncodingManager.start()
        .add(carAccess).add(carSpeed).add(roadClass).add(toll).add(surface).add(roadEnv).add(maxSpeed).build()
    private val graph: BaseGraph = BaseGraph.Builder(em).create()

    /** Edge 0: motorway, toll, fwd 100 / bwd 80 km/h, car access both ways, at 30.30,-89.10 → 30.31,-89.09. */
    private val motorway: EdgeIteratorState
    /** Edge 1: residential gravel, 30 km/h, only forward access, far to the east. */
    private val gravel: EdgeIteratorState

    init {
        val na = graph.nodeAccess
        na.setNode(0, 30.30, -89.10); na.setNode(1, 30.31, -89.09)
        na.setNode(2, 30.30, -88.50); na.setNode(3, 30.30, -88.49)
        motorway = graph.edge(0, 1).setDistance(1500.0)
            .set(carAccess, true, true).set(carSpeed, 100.0, 80.0)
            .set(roadClass, RoadClass.MOTORWAY).set(toll, Toll.ALL).set(surface, Surface.ASPHALT).set(roadEnv, RoadEnvironment.ROAD)
            .set(maxSpeed, 110.0, 90.0)
        gravel = graph.edge(2, 3).setDistance(1000.0)
            .set(carAccess, true, false).set(carSpeed, 30.0, 30.0)
            .set(roadClass, RoadClass.RESIDENTIAL).set(toll, Toll.NO).set(surface, Surface.GRAVEL).set(roadEnv, RoadEnvironment.ROAD)
            .set(maxSpeed, MaxSpeed.MAXSPEED_MISSING, MaxSpeed.MAXSPEED_MISSING)
    }

    private fun model(priority: List<Statement> = emptyList(), speed: List<Statement> = listOf(Statement.If("true", Statement.Op.LIMIT, "car_average_speed")), areas: JsonFeatureCollection? = null): CustomModel =
        CustomModel().apply {
            distanceInfluence = 90.0
            speed.forEach { addToSpeed(it) }
            priority.forEach { addToPriority(it) }
            if (areas != null) setAreas(areas)
        }

    private fun priorityOf(condition: String, edge: EdgeIteratorState, reverse: Boolean = false, factor: String = "0.5"): Double =
        InterpretedCustomModel.compile(model(priority = listOf(Statement.If(condition, Statement.Op.MULTIPLY, factor))), em).priority.get(edge, reverse)

    private fun assertCondition(condition: String, edge: EdgeIteratorState, expected: Boolean, reverse: Boolean = false) =
        assertEquals(if (expected) 0.5 else 1.0, priorityOf(condition, edge, reverse), "'$condition' on edge ${edge.edge} reverse=$reverse")

    @Test
    fun carJsonSemantics() {
        val m = InterpretedCustomModel.compile(GhProfile.carCustomModel(), em)
        assertEquals(100.0, m.speed.get(motorway, false))
        assertEquals(80.0, m.speed.get(motorway, true))
        assertEquals(30.0, m.speed.get(gravel, false))
        assertEquals(1.0, m.priority.get(gravel, false))
        assertEquals(0.0, m.priority.get(gravel, true), "no backward access → priority 0")
        assertEquals(carSpeed.maxOrMaxStorableDecimal, m.maxSpeed)
        assertEquals(1.0, m.maxPriority)
        assertEquals(90.0, m.distanceInfluence)
        assertEquals(InterpretedCustomModel.DEFAULT_HEADING_PENALTY, m.headingPenaltySeconds)
        val p = m.toParameters()
        assertEquals(m.maxSpeed, p.maxSpeedCalc.calcMax())
        assertEquals(m.maxPriority, p.maxPrioCalc.calcMax())
        assertEquals(0.0, p.turnPenaltyMapping.get(graph, graph.edgeAccess, 0, 1, 1))
    }

    @Test
    fun booleanConditions() {
        assertCondition("true", motorway, true)
        assertCondition("false", motorway, false)
        assertCondition("car_access", gravel, true)
        assertCondition("car_access", gravel, false, reverse = true)
        assertCondition("!car_access", gravel, true, reverse = true)
        assertCondition("backward_car_access", gravel, false)
        assertCondition("!backward_car_access", gravel, true)
    }

    @Test
    fun enumConditions() {
        assertCondition("road_class == MOTORWAY", motorway, true)
        assertCondition("road_class == MOTORWAY", gravel, false)
        assertCondition("road_class != MOTORWAY", gravel, true)
        assertCondition("MOTORWAY == road_class", motorway, true)
        assertCondition("toll == ALL", motorway, true)
        assertCondition("toll == ALL", gravel, false)
        assertCondition("road_environment == FERRY", motorway, false)
        assertCondition("surface == GRAVEL", gravel, true)
    }

    @Test
    fun numericConditions() {
        assertCondition("car_average_speed > 50", motorway, true)
        assertCondition("car_average_speed >= 100", motorway, true)
        assertCondition("car_average_speed >= 100", motorway, false, reverse = true)
        assertCondition("car_average_speed < 100", motorway, false)
        assertCondition("car_average_speed <= 100", motorway, true)
        assertCondition("car_average_speed == 30", gravel, true)
        assertCondition("car_average_speed != 30", gravel, false)
        assertCondition("max_speed < 100", motorway, false)
        assertCondition("max_speed < 100", motorway, true, reverse = true)
        assertCondition("edge.getDistance() > 1200", motorway, true)
        assertCondition("edge.getDistance() > 1200", gravel, false)
        assertCondition("car_average_speed * 2 > 150", motorway, true)
        assertCondition("-car_average_speed < 0", motorway, true)
    }

    @Test
    fun logicalCombinationsAndParentheses() {
        assertCondition("road_class == MOTORWAY && toll == ALL", motorway, true)
        assertCondition("road_class == MOTORWAY && toll == NO", motorway, false)
        assertCondition("road_class == MOTORWAY || toll == NO", motorway, true)
        assertCondition("surface == GRAVEL || surface == DIRT || surface == SAND", gravel, true)
        assertCondition("surface == GRAVEL || surface == DIRT || surface == SAND", motorway, false)
        assertCondition("(road_class == MOTORWAY || toll == NO) && car_average_speed > 50", motorway, true)
        assertCondition("(road_class == MOTORWAY || toll == NO) && car_average_speed > 50", gravel, false)
        assertCondition("!(road_class == MOTORWAY && toll == ALL)", motorway, false)
        assertCondition("!(road_class == MOTORWAY) && !car_access", gravel, true, reverse = true)
        // && binds tighter than ||
        assertCondition("false && true || true", motorway, true)
        assertCondition("true || false && false", motorway, true)
    }

    @Test
    fun operatorsAndChains() {
        val m = InterpretedCustomModel.compile(model(
            speed = listOf(
                Statement.If("true", Statement.Op.LIMIT, "car_average_speed"),
                Statement.If("road_class == MOTORWAY", Statement.Op.MULTIPLY, "0.9"),
                Statement.ElseIf("surface == GRAVEL", Statement.Op.LIMIT, "20"),
                Statement.Else(Statement.Op.ADD, "5"),
                Statement.If("toll == ALL", Statement.Op.ADD, "car_average_speed * 0.1"),
            ),
            priority = listOf(
                Statement.If("road_class == MOTORWAY", Statement.Op.MULTIPLY, "0.5"),
                Statement.Else(Statement.Op.MULTIPLY, "0.8"),
                Statement.If("!car_access", Statement.Op.MULTIPLY, "0"),
            ),
        ), em)
        assertEquals(100 * 0.9 + 100 * 0.1, m.speed.get(motorway, false), 1e-9)
        assertEquals(80 * 0.9 + 80 * 0.1, m.speed.get(motorway, true), 1e-9)
        assertEquals(20.0, m.speed.get(gravel, false))
        assertEquals(0.5, m.priority.get(motorway, false))
        assertEquals(0.8, m.priority.get(gravel, false))
        assertEquals(0.0, m.priority.get(gravel, true))
        // max speed: limit(999, maxEV) → branch max(0.9·max, 20, max+5) → + 0.1·max when toll
        val maxEv = carSpeed.maxOrMaxStorableDecimal
        assertEquals(maxEv + 5 + maxEv * 0.1, m.maxSpeed, 1e-9)
        assertEquals(0.8, m.maxPriority, 1e-9)
    }

    @Test
    fun blocks() {
        val m = InterpretedCustomModel.compile(model(
            priority = listOf(
                Statement.If("road_class == MOTORWAY", listOf(
                    Statement.If("toll == ALL", Statement.Op.MULTIPLY, "0.5"),
                    Statement.Else(Statement.Op.MULTIPLY, "0.7"),
                )),
                Statement.Else(listOf(Statement.If("true", Statement.Op.MULTIPLY, "0.9"))),
            ),
        ), em)
        assertEquals(0.5, m.priority.get(motorway, false))
        assertEquals(0.9, m.priority.get(gravel, false))
        assertEquals(0.9, m.maxPriority, 1e-9)
    }

    @Test
    fun areas() {
        val gf = GeometryFactory()
        fun box(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = gf.createPolygon(arrayOf(
            Coordinate(minLon, minLat), Coordinate(maxLon, minLat), Coordinate(maxLon, maxLat), Coordinate(minLon, maxLat), Coordinate(minLon, minLat)))
        val areas = JsonFeatureCollection().apply {
            features += JsonFeature("west", "Feature", null, box(30.29, -89.11, 30.32, -89.08), emptyMap())
            features += JsonFeature("nowhere", "Feature", null, box(31.0, -89.11, 31.1, -89.08), emptyMap())
        }
        val m = InterpretedCustomModel.compile(model(priority = listOf(
            Statement.If("in_west", Statement.Op.MULTIPLY, "0"),
            Statement.If("!in_nowhere", Statement.Op.MULTIPLY, "0.5"),
        ), areas = areas), em)
        assertEquals(0.0, m.priority.get(motorway, false))
        assertEquals(0.0, m.priority.get(motorway, true), "cached per edge, direction independent")
        assertEquals(0.5, m.priority.get(gravel, false))
        assertEquals(0.5, m.priority.get(gravel, false), "second lookup hits the cache")
        assertFailsWith<IllegalArgumentException> {
            InterpretedCustomModel.compile(model(priority = listOf(Statement.If("in_missing", Statement.Op.MULTIPLY, "0")), areas = areas), em)
        }
    }

    @Test
    fun unsupportedExpressionsAreUnsupportedCustomModelExceptions() {
        for (bad in listOf(
            "road_class.ordinal() > 3",
            "Math.sqrt(car_average_speed) > 5",
            "country == USA",
            "no_such_ev == 3",
            "car_average_speed > ",
            "road_class == 'motorway'",
            "road_class == NOT_A_VALUE",
            "change_angle > 10",
            "car_access +",
            "(car_access",
            "car_average_speed",
            "road_class",
        )) {
            assertFailsWith<UnsupportedCustomModelException>("expected failure for '$bad'") { priorityOf(bad, motorway) }
        }
        assertFailsWith<UnsupportedCustomModelException> {
            InterpretedCustomModel.compile(model(priority = listOf(Statement.If("true", Statement.Op.MULTIPLY, "car_average_speed * max_speed"))), em)
        }
        assertFailsWith<UnsupportedCustomModelException> {
            InterpretedCustomModel.compile(model(priority = listOf(Statement.If("true", Statement.Op.MULTIPLY, "1 / 2"))), em)
        }
        assertFailsWith<UnsupportedCustomModelException> {
            InterpretedCustomModel.compile(CustomModel(GhProfile.carCustomModel()).addToTurnPenalty(Statement.If("change_angle > 90", Statement.Op.ADD, "5")), em)
        }
    }

    @Test
    fun graphHopperValidationRulesAreMirrored() {
        // first speed statement must bound the speed
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(speed = listOf(Statement.If("road_class == MOTORWAY", Statement.Op.LIMIT, "100"))), em) }
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(speed = emptyList()), em) }
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(speed = listOf(Statement.If("true", Statement.Op.MULTIPLY, "0.5"))), em) }
        // priority must not use add; add must be positive; else must not have a condition; group must start with if
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(priority = listOf(Statement.If("true", Statement.Op.ADD, "1"))), em) }
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(speed = listOf(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"), Statement.If("true", Statement.Op.ADD, "-5"))), em) }
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(priority = listOf(Statement.Else(Statement.Op.MULTIPLY, "0.5"))), em) }
        // negative multiply → negative value
        assertFailsWith<IllegalArgumentException> { InterpretedCustomModel.compile(model(priority = listOf(Statement.If("true", Statement.Op.MULTIPLY, "-1"))), em) }
    }

    @Test
    fun strictFactoryRejectsUnsupportedModelAndLenientOneFallsBack() {
        val profile = GhProfile.carProfile()
        val hints = com.graphhopper.util.PMap().putObject(CustomModel.KEY, CustomModel().addToPriority(Statement.If("road_class.ordinal() > 3", Statement.Op.MULTIPLY, "0.5")))
        val strict = InterpretedWeightingFactory(graph, em, janinoFallback = false)
        val e = assertFailsWith<IllegalArgumentException> { strict.createWeighting(profile, hints, true) }
        assertTrue("road_class.ordinal()" in (e.message ?: ""), e.message)
        // Plain models work without turn costs, with the interpreter.
        val w = strict.createWeighting(profile, com.graphhopper.util.PMap(), true)
        assertTrue(w.calcEdgeWeight(motorway, false) > 0)
        assertTrue(w.calcEdgeWeight(gravel, true).isInfinite(), "no backward access")
        assertEquals("custom", w.name)
        // Lenient factory delegates to GraphHopper's Janino path (works on the JVM).
        val lenient = InterpretedWeightingFactory(graph, em, janinoFallback = true)
        val wj = lenient.createWeighting(profile, hints, true)
        assertTrue(wj.calcEdgeWeight(motorway, false) > 0)
    }
}
