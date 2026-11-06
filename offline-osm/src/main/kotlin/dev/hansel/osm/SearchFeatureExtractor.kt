package dev.hansel.osm

import java.io.File
import kotlin.math.floor

/** One row for the on-device search index (the Android side writes these into SQLite FTS). */
data class SearchFeature(
    /** 'n', 'w' or 'r'. */
    val osmType: Char,
    val osmId: Long,
    val name: String,
    val altNames: List<String>,
    /** "city","town","village","hamlet","suburb","neighbourhood","street","address","poi:<amenity-or-shop-value>". */
    val category: String,
    val lat: Double,
    val lon: Double,
    val houseNumber: String?,
    val street: String?,
    val city: String?,
    val postcode: String?,
    /** 0 best: cities, then towns, villages, hamlets, suburbs, neighbourhoods, streets, POIs, addresses. */
    val rank: Int,
)

fun interface SearchFeatureSink {
    fun accept(f: SearchFeature)
}

/**
 * Streams a PBF twice and emits searchable features.
 *
 * Pass 1 emits node features directly (place=*, named POIs, addresses) and records, for ways and relations, the
 * node ids whose coordinates stand in for the geometry: first + middle node of a named highway, first node of a
 * named building/POI/address way, the `label`/`admin_centre` node of a place relation. Pass 2 only decodes node
 * blocks, fills a sorted `LongArray` -> `DoubleArray` coordinate table by binary search, and then emits the
 * way/relation features. Street segments are collapsed to one feature per (name, addr:city or addr:postcode or
 * 0.05 degree cell) at the mean of their proxy points.
 *
 * Memory is bounded by the number of *named* ways and address ways (a few hundred thousand for a state), stored in
 * primitive arrays with all strings interned in one pool; no full node table is ever built.
 */
class SearchFeatureExtractor {

    data class Stats(
        val features: Long,
        val nodes: Long,
        val ways: Long,
        val relations: Long,
        /** Way/relation features dropped because their proxy node was not in the file (should be ~0 for Geofabrik). */
        val unresolved: Long,
    )

    fun extract(pbf: File, sink: SearchFeatureSink, progress: (Long) -> Unit = {}): Stats {
        val pool = StringPool()
        val pending = PendingStore(pool)
        val placeNodeIds = HashSet<Long>()
        var processed = 0L
        var nextProgress = PROGRESS_EVERY
        var features = 0L
        var nodes = 0L; var ways = 0L; var relations = 0L

        // ---- pass 1 ----
        PbfReader.open(pbf).use { reader ->
            for (e in reader.entities()) {
                when (e) {
                    is OsmEntity.Node -> { nodes++; if (e.tags.isNotEmpty() && visitNode(e, sink, placeNodeIds)) features++ }
                    is OsmEntity.Way -> { ways++; if (e.tags.isNotEmpty()) visitWay(e, pending) }
                    is OsmEntity.Relation -> { relations++; if (e.tags.isNotEmpty()) visitRelation(e, pending, placeNodeIds) }
                }
                if (++processed >= nextProgress) { progress(processed); nextProgress += PROGRESS_EVERY }
            }
        }

        // ---- coordinate table ----
        val needed = pending.neededNodeIds()
        val lat = DoubleArray(needed.size) { Double.NaN }
        val lon = DoubleArray(needed.size) { Double.NaN }

        // ---- pass 2: nodes only ----
        if (needed.isNotEmpty()) {
            PbfReader.open(pbf).use { reader ->
                val sorted = "Sort.Type_then_ID" in reader.header.optionalFeatures
                outer@ while (true) {
                    val block = reader.nextPrimitiveBlock() ?: break
                    for (e in PbfReader.decodeBlock(block)) {
                        if (e !is OsmEntity.Node) { if (sorted) break@outer else continue }
                        val i = needed.binarySearch(e.id)
                        if (i >= 0) { lat[i] = e.lat; lon[i] = e.lon }
                        if (++processed >= nextProgress) { progress(processed); nextProgress += PROGRESS_EVERY }
                    }
                }
            }
        }

        // ---- resolve + emit ----
        var unresolved = 0L
        val streets = LinkedHashMap<String, StreetAgg>()
        for (row in 0 until pending.size) {
            val i1 = needed.binarySearch(pending.node1[row])
            var la = if (i1 >= 0) lat[i1] else Double.NaN
            var lo = if (i1 >= 0) lon[i1] else Double.NaN
            if (pending.kind[row] == KIND_STREET) {
                val i2 = needed.binarySearch(pending.node2[row])
                val la2 = if (i2 >= 0) lat[i2] else Double.NaN
                val lo2 = if (i2 >= 0) lon[i2] else Double.NaN
                when {
                    la.isNaN() && la2.isNaN() -> { unresolved++; continue }
                    la.isNaN() -> { la = la2; lo = lo2 }
                    !la2.isNaN() -> { la = (la + la2) / 2; lo = (lo + lo2) / 2 }
                }
                val name = pool[pending.name[row]]
                val city = pool.orNull(pending.city[row])
                val postcode = pool.orNull(pending.postcode[row])
                val scope = city ?: postcode ?: cell(la, lo)
                val key = name + '\u0000' + scope
                val agg = streets.getOrPut(key) { StreetAgg(pending.osmId[row], name, city, postcode) }
                agg.add(la, lo, pool.orNull(pending.alt[row]))
            } else {
                if (la.isNaN()) { unresolved++; continue }
                val category = pool[pending.category[row]]
                sink.accept(
                    SearchFeature(
                        osmType = pending.osmType[row].toInt().toChar(),
                        osmId = pending.osmId[row],
                        name = pool[pending.name[row]],
                        altNames = splitAlt(pool.orNull(pending.alt[row]), pool[pending.name[row]]),
                        category = category,
                        lat = la, lon = lo,
                        houseNumber = pool.orNull(pending.houseNumber[row]),
                        street = pool.orNull(pending.street[row]),
                        city = city(pool.orNull(pending.city[row])),
                        postcode = pool.orNull(pending.postcode[row]),
                        rank = rankOf(category),
                    ),
                )
                features++
            }
        }
        for (agg in streets.values) {
            sink.accept(
                SearchFeature(
                    osmType = 'w', osmId = agg.osmId, name = agg.name, altNames = agg.altNames.filter { it != agg.name },
                    category = CATEGORY_STREET, lat = agg.sumLat / agg.count, lon = agg.sumLon / agg.count,
                    houseNumber = null, street = null, city = agg.city, postcode = agg.postcode, rank = rankOf(CATEGORY_STREET),
                ),
            )
            features++
        }
        progress(processed)
        return Stats(features, nodes, ways, relations, unresolved)
    }

    private fun visitNode(n: OsmEntity.Node, sink: SearchFeatureSink, placeNodeIds: HashSet<Long>): Boolean {
        val t = n.tags
        val name = t["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val place = t["place"]
        val category: String = when {
            place != null && place in PLACE_CATEGORIES && name != null -> place
            name != null && poiCategory(t) != null -> poiCategory(t)!!
            t["addr:housenumber"] != null && t["addr:street"] != null -> CATEGORY_ADDRESS
            else -> return false
        }
        if (category in PLACE_CATEGORIES) placeNodeIds += n.id
        sink.accept(
            SearchFeature(
                osmType = 'n', osmId = n.id,
                name = name ?: "${t["addr:housenumber"]} ${t["addr:street"]}",
                altNames = splitAlt(altNames(t), name),
                category = category, lat = n.lat, lon = n.lon,
                houseNumber = t["addr:housenumber"], street = t["addr:street"], city = city(t["addr:city"]), postcode = t["addr:postcode"],
                rank = rankOf(category),
            ),
        )
        return true
    }

    private fun visitWay(w: OsmEntity.Way, pending: PendingStore) {
        if (w.refs.isEmpty()) return
        val t = w.tags
        val name = t["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val highway = t["highway"]
        if (highway != null) {
            if (name == null || highway in NON_STREET_HIGHWAYS) return
            pending.addStreet(w.id, w.refs[0], w.refs[w.refs.size / 2], name, altNames(t), t["addr:city"], t["addr:postcode"])
            return
        }
        val poi = poiCategory(t)
        val category = when {
            name != null && poi != null -> poi
            name != null && t.containsKey("building") -> "poi:building"
            t["addr:housenumber"] != null && t["addr:street"] != null -> CATEGORY_ADDRESS
            else -> return
        }
        pending.addPoint(
            'w', w.id, w.refs[0], name ?: "${t["addr:housenumber"]} ${t["addr:street"]}", altNames(t), category,
            t["addr:housenumber"], t["addr:street"], t["addr:city"], t["addr:postcode"],
        )
    }

    private fun visitRelation(r: OsmEntity.Relation, pending: PendingStore, placeNodeIds: HashSet<Long>) {
        val t = r.tags
        val place = t["place"] ?: return
        if (place !in PLACE_CATEGORIES) return
        val name = t["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val label = r.members.firstOrNull { it.type == EntityType.NODE && it.role == "label" }
            ?: r.members.firstOrNull { it.type == EntityType.NODE && it.role == "admin_centre" }
            ?: return // multipolygon geometry is not resolved; the place node (if any) already covers it
        if (label.ref in placeNodeIds) return // the node itself was emitted as this place in pass 1
        pending.addPoint('r', r.id, label.ref, name, altNames(t), place, null, null, t["addr:city"], t["addr:postcode"])
    }

    private class StreetAgg(val osmId: Long, val name: String, val city: String?, val postcode: String?) {
        var sumLat = 0.0; var sumLon = 0.0; var count = 0
        val altNames = LinkedHashSet<String>()
        fun add(lat: Double, lon: Double, alt: String?) {
            sumLat += lat; sumLon += lon; count++
            if (alt != null) altNames += splitAlt(alt)
        }
    }

    /** Interned strings; index -1 means null. */
    private class StringPool {
        private val index = HashMap<String, Int>()
        private val list = ArrayList<String>()
        fun intern(s: String?): Int = if (s == null) -1 else index.getOrPut(s) { list += s; list.size - 1 }
        operator fun get(i: Int): String = list[i]
        fun orNull(i: Int): String? = if (i < 0) null else list[i]
    }

    /** Column-oriented store of way/relation features awaiting coordinates. */
    private class PendingStore(private val pool: StringPool) {
        var size = 0; private set
        var kind = ByteArray(1024)
        var osmType = ByteArray(1024)
        var osmId = LongArray(1024)
        var node1 = LongArray(1024)
        var node2 = LongArray(1024)
        var name = IntArray(1024)
        var alt = IntArray(1024)
        var category = IntArray(1024)
        var houseNumber = IntArray(1024)
        var street = IntArray(1024)
        var city = IntArray(1024)
        var postcode = IntArray(1024)

        private fun grow() {
            if (size < kind.size) return
            val n = kind.size * 2
            kind = kind.copyOf(n); osmType = osmType.copyOf(n); osmId = osmId.copyOf(n)
            node1 = node1.copyOf(n); node2 = node2.copyOf(n)
            name = name.copyOf(n); alt = alt.copyOf(n); category = category.copyOf(n)
            houseNumber = houseNumber.copyOf(n); street = street.copyOf(n); city = city.copyOf(n); postcode = postcode.copyOf(n)
        }

        fun addStreet(id: Long, n1: Long, n2: Long, nm: String, altNames: String?, cty: String?, pc: String?) {
            grow()
            kind[size] = KIND_STREET; osmType[size] = 'w'.code.toByte(); osmId[size] = id
            node1[size] = n1; node2[size] = n2
            name[size] = pool.intern(nm); alt[size] = pool.intern(altNames); category[size] = pool.intern(CATEGORY_STREET)
            houseNumber[size] = -1; street[size] = -1; city[size] = pool.intern(cty); postcode[size] = pool.intern(pc)
            size++
        }

        fun addPoint(type: Char, id: Long, n1: Long, nm: String, altNames: String?, cat: String, hn: String?, st: String?, cty: String?, pc: String?) {
            grow()
            kind[size] = KIND_POINT; osmType[size] = type.code.toByte(); osmId[size] = id
            node1[size] = n1; node2[size] = 0
            name[size] = pool.intern(nm); alt[size] = pool.intern(altNames); category[size] = pool.intern(cat)
            houseNumber[size] = pool.intern(hn); street[size] = pool.intern(st); city[size] = pool.intern(cty); postcode[size] = pool.intern(pc)
            size++
        }

        /** Sorted, de-duplicated node ids referenced by any pending row. */
        fun neededNodeIds(): LongArray {
            var n = 0
            for (i in 0 until size) n += if (kind[i] == KIND_STREET) 2 else 1
            val ids = LongArray(n)
            var k = 0
            for (i in 0 until size) {
                ids[k++] = node1[i]
                if (kind[i] == KIND_STREET) ids[k++] = node2[i]
            }
            ids.sort()
            var w = 0
            for (i in ids.indices) if (i == 0 || ids[i] != ids[i - 1]) ids[w++] = ids[i]
            return if (w == ids.size) ids else ids.copyOf(w)
        }
    }

    companion object {
        const val CATEGORY_STREET = "street"
        const val CATEGORY_ADDRESS = "address"
        val PLACE_CATEGORIES: List<String> = listOf("city", "town", "village", "hamlet", "suburb", "neighbourhood")
        private val POI_KEYS = listOf("amenity", "shop", "tourism", "leisure")
        private val NON_STREET_HIGHWAYS = setOf("proposed", "construction", "abandoned", "razed", "platform", "bus_stop", "elevator")
        private val ALT_NAME_KEYS = listOf("alt_name", "short_name", "official_name", "old_name", "loc_name", "name:en")
        private const val PROGRESS_EVERY = 100_000L
        private const val KIND_POINT: Byte = 0
        private const val KIND_STREET: Byte = 1
        private const val CELL_DEGREES = 0.05

        fun rankOf(category: String): Int = when (category) {
            "city" -> 0
            "town" -> 1
            "village" -> 2
            "hamlet" -> 3
            "suburb" -> 4
            "neighbourhood" -> 5
            CATEGORY_STREET -> 6
            CATEGORY_ADDRESS -> 8
            else -> 7 // poi:*
        }

        private fun poiCategory(t: Map<String, String>): String? {
            for (k in POI_KEYS) {
                val v = t[k] ?: continue
                if (v == "yes" || v == "no") continue
                return "poi:$v"
            }
            return null
        }

        private fun altNames(t: Map<String, String>): String? {
            var out: StringBuilder? = null
            for (k in ALT_NAME_KEYS) {
                val v = t[k] ?: continue
                if (out == null) out = StringBuilder(v) else out.append(';').append(v)
            }
            return out?.toString()
        }

        private fun splitAlt(joined: String?, name: String? = null): List<String> =
            joined?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() && it != name }?.distinct() ?: emptyList()

        private fun city(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }

        private fun cell(lat: Double, lon: Double): String =
            "${floor(lat / CELL_DEGREES).toInt()},${floor(lon / CELL_DEGREES).toInt()}"
    }
}
