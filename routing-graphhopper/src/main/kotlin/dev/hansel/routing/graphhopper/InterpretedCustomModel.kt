package dev.hansel.routing.graphhopper

import com.graphhopper.json.MinMax
import com.graphhopper.json.Statement
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EncodedValue
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.IntEncodedValue
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.routing.weighting.custom.CustomWeightingHelper
import com.graphhopper.util.CustomModel
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.JsonFeature
import com.graphhopper.util.shapes.Polygon
import org.locationtech.jts.geom.Polygonal
import org.locationtech.jts.geom.prep.PreparedPolygon
import java.util.BitSet

/**
 * Thrown by [InterpretedCustomModel.compile] for syntax the interpreter does not understand (as opposed to a model
 * GraphHopper itself would reject, which is a plain [IllegalArgumentException]). [InterpretedWeightingFactory] may
 * answer it with a Janino fallback.
 */
class UnsupportedCustomModelException(message: String) : IllegalArgumentException(message)

/**
 * A [CustomModel] evaluated by interpretation instead of GraphHopper's Janino code generation, so it works on ART.
 *
 * GraphHopper 11.1 (`CustomModelParser`) turns each `speed`/`priority` statement list into Java source, compiles it
 * with Janino and loads the class at run time; Android cannot define JVM bytecode. This class parses the statements
 * once (at weighting creation, never per edge) into a small expression tree and evaluates it per edge with the same
 * semantics as the generated code:
 *
 * - `speed` starts at `CustomWeightingHelper.GLOBAL_MAX_SPEED` (999) and `priority` at 1; statements are applied in
 *   order, grouped into `if / else_if / else` chains. `multiply_by` → `value *= x`, `limit_to` → `value = min(value, x)`,
 *   `add` → `value += x`, `do` → a nested block.
 * - Encoded values are read with `edge.get(ev)` or `edge.getReverse(ev)` depending on `reverse`; `backward_<ev>` flips it.
 * - `in_<area>` tests the edge geometry against the area polygon exactly like `CustomWeightingHelper.in`, cached per
 *   edge id because the search visits edges repeatedly.
 * - The `max speed` / `max priority` needed by [CustomWeighting.Parameters] are computed like
 *   `CustomWeightingHelper.calcMaxSpeed/calcMaxPriority` + `FindMinMax` (which for arithmetic value expressions uses a
 *   Janino `ExpressionEvaluator`, hence re-implemented here).
 *
 * ## Supported grammar
 * Conditions: `true`, `false`, `<bool_ev>`, `!cond`, `<enum_ev> == CONST`, `<enum_ev> != CONST` (either side),
 * numeric comparisons `< <= > >= == !=` between numeric expressions (numbers, int/decimal encoded values,
 * `edge.getDistance()`, `+ - * / %`), `in_<area>`, `backward_<ev>`, `&&`, `||` and parentheses.
 * Values: a number, `Infinity`, an int/decimal encoded value, or `+ - *` arithmetic containing at most one encoded
 * value (GraphHopper's own restriction, which is also what makes the min/max bound exact).
 *
 * Not supported (→ [UnsupportedCustomModelException]): `turn_penalty` statements (`change_angle`, `prev_*`),
 * string encoded values, method calls other than `edge.getDistance()`, `Math.*`, `country.ordinal()`.
 */
class InterpretedCustomModel private constructor(
    private val speedGroups: List<Group>,
    private val priorityGroups: List<Group>,
    val maxSpeed: Double,
    val maxPriority: Double,
    val distanceInfluence: Double,
    val headingPenaltySeconds: Double,
) {
    /** `CustomWeighting.EdgeToDoubleMapping` for the speed (km/h). */
    val speed: CustomWeighting.EdgeToDoubleMapping = CustomWeighting.EdgeToDoubleMapping { edge, reverse ->
        var value = GLOBAL_MAX_SPEED
        for (g in speedGroups) value = g.apply(value, edge, reverse)
        value
    }

    /** `CustomWeighting.EdgeToDoubleMapping` for the priority. */
    val priority: CustomWeighting.EdgeToDoubleMapping = CustomWeighting.EdgeToDoubleMapping { edge, reverse ->
        var value = GLOBAL_PRIORITY
        for (g in priorityGroups) value = g.apply(value, edge, reverse)
        value
    }

    /** No `turn_penalty` support: 0 like GraphHopper's generated class without turn-penalty statements. */
    val turnPenalty: CustomWeighting.TurnPenaltyMapping = CustomWeighting.TurnPenaltyMapping { _, _, _, _, _ -> 0.0 }

    fun toParameters(): CustomWeighting.Parameters = CustomWeighting.Parameters(
        speed, CustomWeighting.MaxCalc { maxSpeed },
        priority, CustomWeighting.MaxCalc { maxPriority },
        turnPenalty, distanceInfluence, headingPenaltySeconds,
    )

    // ---------------------------------------------------------------------------------------------------------------
    // Statement tree

    /** One `if / else_if* / else?` chain. Mirrors `CustomModelParser.splitIntoGroup` + the generated if/else chain. */
    private class Group(val branches: List<Branch>) {
        fun apply(start: Double, edge: EdgeIteratorState, reverse: Boolean): Double {
            for (b in branches) {
                if (b.condition == null || b.condition.eval(edge, reverse)) return b.action.apply(start, edge, reverse)
            }
            return start
        }
    }

    private class Branch(val isElse: Boolean, val conditionText: String, val condition: Cond?, val action: Action)

    private sealed interface Action {
        fun apply(start: Double, edge: EdgeIteratorState, reverse: Boolean): Double
        fun minMax(start: MinMax): MinMax
    }

    private class OpAction(val op: Statement.Op, val value: Num, val statement: Statement) : Action {
        override fun apply(start: Double, edge: EdgeIteratorState, reverse: Boolean): Double {
            val v = value.eval(edge, reverse)
            return when (op) {
                Statement.Op.MULTIPLY -> start * v
                Statement.Op.LIMIT -> kotlin.math.min(start, v)
                Statement.Op.ADD -> start + v
                Statement.Op.DO -> throw IllegalStateException("DO without block")
            }
        }

        override fun minMax(start: MinMax): MinMax {
            val res = op.apply(start, value.minMax())
            if (res.max < 0) throw IllegalArgumentException("statement resulted in negative value: $statement")
            return res
        }
    }

    private class BlockAction(val groups: List<Group>) : Action {
        override fun apply(start: Double, edge: EdgeIteratorState, reverse: Boolean): Double {
            var v = start
            for (g in groups) v = g.apply(v, edge, reverse)
            return v
        }

        override fun minMax(start: MinMax): MinMax {
            val copy = MinMax(start.min, start.max)
            for (g in groups) findMinMaxForGroup(copy, g)
            return copy
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Expression tree

    private sealed interface Expr

    private sealed interface Cond : Expr {
        fun eval(edge: EdgeIteratorState, reverse: Boolean): Boolean
    }

    private sealed interface Num : Expr {
        fun eval(edge: EdgeIteratorState, reverse: Boolean): Double
        /** The encoded value this expression reads, if any (values may read at most one). */
        fun encodedValues(): Set<EncodedValue>
        /** Evaluate with every encoded value replaced by [bound] (for min/max bounds). */
        fun evalBound(bound: Double): Double
        fun minMax(): MinMax {
            val evs = encodedValues()
            if (evs.isEmpty()) { val c = evalBound(0.0); return MinMax(c, c) }
            val ev = evs.single()
            val a = evalBound(maxOf(ev)); val b = evalBound(minOf(ev))
            return MinMax(kotlin.math.min(a, b), kotlin.math.max(a, b))
        }
    }

    /** An identifier that is neither an encoded value nor an area: only meaningful as an enum constant. */
    private class EnumConstRef(val name: String) : Expr

    private class EnumEv(val ev: EnumEncodedValue<*>, val flip: Boolean) : Expr {
        fun read(edge: EdgeIteratorState, reverse: Boolean): Enum<*> =
            if (reverse xor flip) edge.getReverse(ev) else edge.get(ev)
    }

    private class BoolConst(val value: Boolean) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = value
    }

    private class BoolEv(val ev: BooleanEncodedValue, val flip: Boolean) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) =
            if (reverse xor flip) edge.getReverse(ev) else edge.get(ev)
    }

    private class Not(val inner: Cond) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = !inner.eval(edge, reverse)
    }

    private class And(val l: Cond, val r: Cond) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = l.eval(edge, reverse) && r.eval(edge, reverse)
    }

    private class Or(val l: Cond, val r: Cond) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = l.eval(edge, reverse) || r.eval(edge, reverse)
    }

    private class EnumCompare(val ev: EnumEv, val constant: Enum<*>, val equal: Boolean) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = (ev.read(edge, reverse) == constant) == equal
    }

    private class NumCompare(val l: Num, val op: String, val r: Num) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean): Boolean {
            val a = l.eval(edge, reverse); val b = r.eval(edge, reverse)
            return when (op) {
                "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b
                "==" -> a == b; "!=" -> a != b
                else -> throw IllegalStateException(op)
            }
        }
    }

    /** `in_<area>`: edge geometry intersects the polygon; cached per edge id. */
    private class InArea(val polygon: Polygon) : Cond {
        private val computed = BitSet()
        private val inside = BitSet()

        override fun eval(edge: EdgeIteratorState, reverse: Boolean): Boolean {
            val id = edge.edge
            if (id < 0) return CustomWeightingHelper.`in`(polygon, edge)
            synchronized(computed) {
                if (computed.get(id)) return inside.get(id)
                val r = CustomWeightingHelper.`in`(polygon, edge)
                computed.set(id)
                if (r) inside.set(id)
                return r
            }
        }
    }

    private class NumConst(val value: Double) : Num {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = value
        override fun encodedValues(): Set<EncodedValue> = emptySet()
        override fun evalBound(bound: Double) = value
    }

    private class DecimalEv(val ev: DecimalEncodedValue, val flip: Boolean) : Num {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) =
            if (reverse xor flip) edge.getReverse(ev) else edge.get(ev)
        override fun encodedValues(): Set<EncodedValue> = setOf(ev)
        override fun evalBound(bound: Double) = bound
    }

    private class IntEv(val ev: IntEncodedValue, val flip: Boolean) : Num {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) =
            (if (reverse xor flip) edge.getReverse(ev) else edge.get(ev)).toDouble()
        override fun encodedValues(): Set<EncodedValue> = setOf(ev)
        override fun evalBound(bound: Double) = bound
    }

    private object EdgeDistance : Num {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = edge.distance
        override fun encodedValues(): Set<EncodedValue> = emptySet()
        override fun evalBound(bound: Double) = throw UnsupportedCustomModelException("edge.getDistance() is not allowed in a value expression")
    }

    private class Neg(val inner: Num) : Num {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = -inner.eval(edge, reverse)
        override fun encodedValues() = inner.encodedValues()
        override fun evalBound(bound: Double) = -inner.evalBound(bound)
    }

    private class Arith(val l: Num, val op: Char, val r: Num) : Num {
        private fun calc(a: Double, b: Double) = when (op) {
            '+' -> a + b; '-' -> a - b; '*' -> a * b; '/' -> a / b; '%' -> a % b
            else -> throw IllegalStateException(op.toString())
        }
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = calc(l.eval(edge, reverse), r.eval(edge, reverse))
        override fun encodedValues() = l.encodedValues() + r.encodedValues()
        override fun evalBound(bound: Double) = calc(l.evalBound(bound), r.evalBound(bound))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tokenizer + recursive-descent parser (precedence: || < && < == != < < <= > >= < + - < * / % < unary < primary)

    private class Token(val kind: Kind, val text: String) {
        enum class Kind { NUMBER, IDENT, OP, STRING, EOF }
        override fun toString() = text
    }

    private class Parser(private val expression: String, private val lookup: EncodedValueLookup, private val areas: Map<String, InArea>) {
        private val tokens = tokenize(expression)
        private var pos = 0

        private fun peek(): Token = tokens[pos]
        private fun next(): Token = tokens[pos++]
        private fun accept(vararg ops: String): String? {
            val t = peek()
            if (t.kind == Token.Kind.OP && t.text in ops) { pos++; return t.text }
            return null
        }
        private fun expect(op: String) {
            if (accept(op) == null) fail("expected '$op' but found '${peek()}'")
        }
        private fun fail(msg: String): Nothing = throw UnsupportedCustomModelException("$msg in expression \"$expression\"")

        fun parseCondition(): Cond {
            val e = parseOr()
            if (peek().kind != Token.Kind.EOF) fail("unexpected '${peek()}'")
            return asCond(e)
        }

        fun parseValue(): Num {
            val e = parseOr()
            if (peek().kind != Token.Kind.EOF) fail("unexpected '${peek()}'")
            val n = asNum(e)
            if (n.encodedValues().size > 1) fail("only a single encoded value is allowed in a value expression")
            if (n.containsOp('/') || n.containsOp('%')) fail("invalid operation in value expression")
            return n
        }

        private fun Num.containsOp(c: Char): Boolean = when (this) {
            is Arith -> op == c || l.containsOp(c) || r.containsOp(c)
            is Neg -> inner.containsOp(c)
            else -> false
        }

        private fun asCond(e: Expr): Cond = e as? Cond ?: fail("expected a boolean but found ${describe(e)}")
        private fun asNum(e: Expr): Num = e as? Num ?: fail("expected a number but found ${describe(e)}")
        private fun describe(e: Expr): String = when (e) {
            is EnumConstRef -> "unknown identifier '${e.name}'"
            is EnumEv -> "enum encoded value '${e.ev.name}'"
            is Num -> "a numeric expression"
            is Cond -> "a boolean expression"
        }

        private fun parseOr(): Expr {
            var l = parseAnd()
            while (accept("||") != null) l = Or(asCond(l), asCond(parseAnd()))
            return l
        }

        private fun parseAnd(): Expr {
            var l = parseEquality()
            while (accept("&&") != null) l = And(asCond(l), asCond(parseEquality()))
            return l
        }

        private fun parseEquality(): Expr {
            var l = parseRelational()
            while (true) {
                val op = accept("==", "!=") ?: return l
                val r = parseRelational()
                l = compare(l, op, r)
            }
        }

        private fun parseRelational(): Expr {
            var l = parseAdditive()
            while (true) {
                val op = accept("<", "<=", ">", ">=") ?: return l
                val r = parseAdditive()
                l = NumCompare(asNum(l), op, asNum(r))
            }
        }

        private fun compare(l: Expr, op: String, r: Expr): Cond {
            val equal = op == "=="
            if (l is EnumEv || r is EnumEv) {
                val (ev, other) = if (l is EnumEv) l to r else (r as EnumEv) to l
                val name = (other as? EnumConstRef)?.name
                    ?: fail("'${ev.ev.name}' can only be compared with one of its constants")
                val constant: Enum<*> = ev.ev.values.firstOrNull { it.name == name }
                    ?: fail("'$name' is not a value of '${ev.ev.name}' (${ev.ev.values.joinToString { it.name }})")
                return EnumCompare(ev, constant, equal)
            }
            if (l is Cond && r is Cond) return if (equal) BoolEquals(l, r) else Not(BoolEquals(l, r))
            return NumCompare(asNum(l), op, asNum(r))
        }

        private fun parseAdditive(): Expr {
            var l = parseMultiplicative()
            while (true) {
                val op = accept("+", "-") ?: return l
                l = Arith(asNum(l), op[0], asNum(parseMultiplicative()))
            }
        }

        private fun parseMultiplicative(): Expr {
            var l = parseUnary()
            while (true) {
                val op = accept("*", "/", "%") ?: return l
                l = Arith(asNum(l), op[0], asNum(parseUnary()))
            }
        }

        private fun parseUnary(): Expr {
            if (accept("!") != null) return Not(asCond(parseUnary()))
            if (accept("-") != null) return Neg(asNum(parseUnary()))
            if (accept("+") != null) return asNum(parseUnary())
            return parsePrimary()
        }

        private fun parsePrimary(): Expr {
            val t = next()
            return when (t.kind) {
                Token.Kind.NUMBER -> NumConst(t.text.toDouble())
                Token.Kind.OP -> if (t.text == "(") { val e = parseOr(); expect(")"); e } else fail("unexpected '${t.text}'")
                Token.Kind.STRING -> fail("string literals are not supported")
                Token.Kind.EOF -> fail("unexpected end")
                Token.Kind.IDENT -> identifier(t.text)
            }
        }

        private fun identifier(name: String): Expr {
            when (name) {
                "true" -> return BoolConst(true)
                "false" -> return BoolConst(false)
                "Infinity" -> return NumConst(Double.POSITIVE_INFINITY)
                "edge" -> {
                    expect("."); val m = next(); expect("("); expect(")")
                    if (m.text == "getDistance") return EdgeDistance
                    fail("edge.${m.text}() is not supported")
                }
                "Math" -> fail("Math.* is not supported")
            }
            if (name.startsWith(IN_AREA_PREFIX)) {
                val id = name.substring(IN_AREA_PREFIX.length)
                return areas[id] ?: throw IllegalArgumentException("Area '$id' wasn't found")
            }
            var evName = name
            var flip = false
            if (name.startsWith(BACKWARD_PREFIX) && !lookup.hasEncodedValue(name)) {
                evName = name.substring(BACKWARD_PREFIX.length); flip = true
            }
            if (lookup.hasEncodedValue(evName)) {
                val ev = lookup.getEncodedValue(evName, EncodedValue::class.java)
                if (peek().kind == Token.Kind.OP && peek().text == ".") fail("method calls on '$evName' are not supported")
                return when (ev) {
                    is BooleanEncodedValue -> BoolEv(ev, flip)
                    is EnumEncodedValue<*> -> EnumEv(ev, flip)
                    is DecimalEncodedValue -> DecimalEv(ev, flip)
                    is IntEncodedValue -> IntEv(ev, flip)
                    else -> fail("encoded value '$evName' of type ${ev.javaClass.simpleName} is not supported")
                }
            }
            if (name.startsWith(PREV_PREFIX) || name == CHANGE_ANGLE) fail("turn-penalty variable '$name' is not supported")
            if (name.any { it.isLowerCase() } || peek().let { it.kind == Token.Kind.OP && it.text == "." }) {
                fail("'$name' is not an encoded value of this graph")
            }
            return EnumConstRef(name)
        }

        companion object {
            private val OPS = listOf("==", "!=", "<=", ">=", "&&", "||", "<", ">", "!", "(", ")", "+", "-", "*", "/", "%", ".")

            fun tokenize(s: String): List<Token> {
                val out = ArrayList<Token>()
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c.isWhitespace() -> i++
                        c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                            val start = i
                            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                                        ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
                            out += Token(Token.Kind.NUMBER, s.substring(start, i))
                        }
                        c.isLetter() || c == '_' || c == '$' -> {
                            val start = i
                            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
                            out += Token(Token.Kind.IDENT, s.substring(start, i))
                        }
                        c == '"' || c == '\'' -> {
                            val end = s.indexOf(c, i + 1)
                            if (end < 0) throw UnsupportedCustomModelException("unterminated string in \"$s\"")
                            out += Token(Token.Kind.STRING, s.substring(i + 1, end)); i = end + 1
                        }
                        else -> {
                            val op = OPS.firstOrNull { s.startsWith(it, i) }
                                ?: throw UnsupportedCustomModelException("unexpected character '$c' in \"$s\"")
                            out += Token(Token.Kind.OP, op); i += op.length
                        }
                    }
                }
                out += Token(Token.Kind.EOF, "<end>")
                return out
            }
        }
    }

    private class BoolEquals(val l: Cond, val r: Cond) : Cond {
        override fun eval(edge: EdgeIteratorState, reverse: Boolean) = l.eval(edge, reverse) == r.eval(edge, reverse)
    }

    companion object {
        /** `CustomWeightingHelper.GLOBAL_MAX_SPEED` (package-private there). */
        const val GLOBAL_MAX_SPEED = 999.0
        /** `CustomWeightingHelper.GLOBAL_PRIORITY`. */
        const val GLOBAL_PRIORITY = 1.0
        private const val IN_AREA_PREFIX = "in_"
        private const val BACKWARD_PREFIX = "backward_"
        private const val PREV_PREFIX = "prev_"
        private const val CHANGE_ANGLE = "change_angle"
        /** `Parameters.Routing.DEFAULT_HEADING_PENALTY`, what `CustomModelParser` uses when the model has none. */
        const val DEFAULT_HEADING_PENALTY = 300.0

        /**
         * Parses and validates [model] against [lookup]. Throws [UnsupportedCustomModelException] for syntax the
         * interpreter cannot handle and [IllegalArgumentException] for models GraphHopper would reject too.
         */
        fun compile(model: CustomModel, lookup: EncodedValueLookup): InterpretedCustomModel {
            if (model.turnPenalty.isNotEmpty()) throw UnsupportedCustomModelException("'turn_penalty' statements are not supported by the interpreter")
            val areas = buildAreas(model)

            // Mirrors CustomModelParser.createClazz: the first speed group must bound the speed in every branch.
            if (model.speed.isEmpty()) throw IllegalArgumentException("At least one initial statement under 'speed' is required.")
            val firstGroup = splitIntoGroups(model.speed).first()
            if (firstGroup.size > 1) {
                val last = firstGroup.last()
                if (last.operation() != Statement.Op.LIMIT || last.keyword() != Statement.Keyword.ELSE)
                    throw IllegalArgumentException("The first group needs to end with an 'else' (or contain a single unconditional 'if' statement).")
            } else {
                val only = firstGroup.first()
                if (!("true" == only.condition() && only.operation() == Statement.Op.LIMIT && only.keyword() == Statement.Keyword.IF))
                    throw IllegalArgumentException("The first group needs to contain a single unconditional 'if' statement (or end with an 'else').")
            }
            for (st in model.priority) if (st.operation() == Statement.Op.ADD) throw IllegalArgumentException("'priority' statement must not have the operation 'add'")

            val speedGroups = splitIntoGroups(model.speed).map { parseGroup(it, lookup, areas) }
            val priorityGroups = splitIntoGroups(model.priority).map { parseGroup(it, lookup, areas) }

            // CustomWeightingHelper.calcMaxSpeed
            val speedMinMax = MinMax(0.0, GLOBAL_MAX_SPEED)
            for (g in speedGroups) findMinMaxForGroup(speedMinMax, g)
            if (speedMinMax.min < 0) throw IllegalArgumentException("speed has to be >=0 but can be negative (${speedMinMax.min})")
            if (speedMinMax.max <= 0) throw IllegalArgumentException("maximum speed has to be >0 but was ${speedMinMax.max}")
            if (speedMinMax.max == GLOBAL_MAX_SPEED) throw IllegalArgumentException("The first statement for 'speed' must be unconditionally to reduce the speed and e.g. not '${model.speed[0]}'")

            // CustomWeightingHelper.calcMaxPriority
            val prioMinMax = MinMax(0.0, GLOBAL_PRIORITY)
            if (model.priority.isNotEmpty()) {
                val first = model.priority[0]
                if ("true" == first.condition() && !first.isBlock && first.value() != null && lookup.hasEncodedValue(first.value())) {
                    prioMinMax.max = lookup.getDecimalEncodedValue(first.value()).maxOrMaxStorableDecimal
                }
            }
            for (g in priorityGroups) findMinMaxForGroup(prioMinMax, g)
            if (prioMinMax.min < 0) throw IllegalArgumentException("priority has to be >=0 but can be negative (${prioMinMax.min})")
            if (prioMinMax.max < 0) throw IllegalArgumentException("maximum priority has to be >=0 but was ${prioMinMax.max}")

            return InterpretedCustomModel(
                speedGroups, priorityGroups, speedMinMax.max, prioMinMax.max,
                model.distanceInfluence ?: 0.0, model.headingPenalty ?: DEFAULT_HEADING_PENALTY,
            )
        }

        /** One [InArea] (with its per-edge cache) per area, shared by every `in_<id>` occurrence in the model. */
        private fun buildAreas(model: CustomModel): Map<String, InArea> {
            val features: Map<String, JsonFeature> = CustomModel.getAreasAsMap(model.areas)
            return features.mapValues { (id, f) ->
                // GraphHopper validates the generated variable name `in_<id>`, not the bare id.
                if (!JsonFeature.isValidId(IN_AREA_PREFIX + id)) throw IllegalArgumentException("Area has invalid name: $id")
                val geometry = f.geometry ?: throw IllegalArgumentException("Area '$id' does not contain a geometry")
                if (geometry !is Polygonal) throw IllegalArgumentException("Currently only type=Polygon is supported for areas but was ${geometry.geometryType}")
                if (f.bBox != null) throw IllegalArgumentException("Bounding box of area $id must be empty")
                InArea(Polygon(PreparedPolygon(geometry)))
            }
        }

        /** `CustomModelParser.splitIntoGroup`: a new group starts at every `if`. */
        private fun splitIntoGroups(statements: List<Statement>): List<List<Statement>> {
            val groups = ArrayList<MutableList<Statement>>()
            for (st in statements) {
                when (st.keyword()) {
                    Statement.Keyword.IF -> groups += mutableListOf(st)
                    Statement.Keyword.ELSEIF, Statement.Keyword.ELSE -> {
                        if (groups.isEmpty()) throw IllegalArgumentException("Every group must start with an if-statement")
                        groups.last() += st
                    }
                    null -> throw IllegalArgumentException("The statement must be either 'if', 'else_if' or 'else'")
                }
            }
            return groups
        }

        private fun parseGroup(group: List<Statement>, lookup: EncodedValueLookup, areas: Map<String, InArea>): Group {
            val branches = group.mapIndexed { i, st ->
                val isElse = st.keyword() == Statement.Keyword.ELSE
                if (isElse && i != group.lastIndex) throw IllegalArgumentException("'else' must be the last statement of a group: $st")
                if (isElse && st.condition() != null && st.condition().isNotEmpty()) throw IllegalArgumentException("condition must be empty but was ${st.condition()}")
                val cond = if (isElse) null else Parser(st.condition() ?: "", lookup, areas).parseCondition()
                val action: Action = if (st.isBlock) {
                    if (st.operation() != Statement.Op.DO) throw IllegalArgumentException("block statement must use 'do': $st")
                    BlockAction(splitIntoGroups(st.doBlock()).map { parseGroup(it, lookup, areas) })
                } else {
                    val op = st.operation() ?: throw IllegalArgumentException("statement without operation: $st")
                    if (op == Statement.Op.DO) throw IllegalArgumentException("'do' needs a block: $st")
                    val value = Parser(st.value() ?: throw IllegalArgumentException("statement without value: $st"), lookup, areas).parseValue()
                    if (op == Statement.Op.ADD && value.minMax().min < 0) throw IllegalArgumentException("The value for the 'add' operation must be positive, but was: ${st.value()}")
                    OpAction(op, value, st)
                }
                Branch(isElse, st.condition() ?: "", cond, action)
            }
            return Group(branches)
        }

        /** `FindMinMax.findMinMaxForGroup`: union of all branch outcomes (plus the untouched input when there is no else). */
        private fun findMinMaxForGroup(minMax: MinMax, group: Group) {
            val first = group.branches.first()
            if (!first.isElse && first.conditionText.trim() == "true") {
                val res = first.action.minMax(minMax)
                minMax.min = res.min; minMax.max = res.max
                return
            }
            val groupMinMax = MinMax(Double.MAX_VALUE, 0.0)
            var hasElse = false
            for (b in group.branches) {
                if (b.isElse) hasElse = true
                val res = b.action.minMax(minMax)
                groupMinMax.min = kotlin.math.min(groupMinMax.min, res.min)
                groupMinMax.max = kotlin.math.max(groupMinMax.max, res.max)
            }
            if (!hasElse) {
                groupMinMax.min = kotlin.math.min(groupMinMax.min, minMax.min)
                groupMinMax.max = kotlin.math.max(groupMinMax.max, minMax.max)
            }
            minMax.min = groupMinMax.min; minMax.max = groupMinMax.max
        }

        /** `ValueExpressionVisitor.getMin/getMax`. */
        private fun minOf(ev: EncodedValue): Double = when (ev) {
            is DecimalEncodedValue -> ev.minStorableDecimal
            is IntEncodedValue -> ev.minStorableInt.toDouble()
            else -> throw IllegalArgumentException("Cannot use non-number data '${ev.name}' in value expression")
        }

        private fun maxOf(ev: EncodedValue): Double = when (ev) {
            is DecimalEncodedValue -> ev.maxOrMaxStorableDecimal
            is IntEncodedValue -> ev.maxOrMaxStorableInt.toDouble()
            else -> throw IllegalArgumentException("Cannot use non-number data '${ev.name}' in value expression")
        }
    }
}
