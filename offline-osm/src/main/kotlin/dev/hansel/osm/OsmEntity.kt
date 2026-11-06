package dev.hansel.osm

/** OSM entity types in the order a sorted PBF stores them (`Sort.Type_then_ID`). */
enum class EntityType { NODE, WAY, RELATION }

/**
 * One OpenStreetMap entity as decoded from (or about to be written to) a PBF. Metadata (version, timestamp,
 * user, changeset) is deliberately dropped: nothing in Hansel needs it and skipping it keeps merged files smaller.
 */
sealed class OsmEntity {
    abstract val id: Long
    abstract val tags: Map<String, String>
    abstract val type: EntityType

    data class Node(
        override val id: Long,
        val lat: Double,
        val lon: Double,
        override val tags: Map<String, String> = emptyMap(),
    ) : OsmEntity() {
        override val type: EntityType get() = EntityType.NODE
    }

    class Way(
        override val id: Long,
        /** Node ids in order. A [LongArray] rather than a `List<Long>` so a 2,000-node way does not box 2,000 Longs. */
        val refs: LongArray,
        override val tags: Map<String, String> = emptyMap(),
    ) : OsmEntity() {
        override val type: EntityType get() = EntityType.WAY
        override fun equals(other: Any?): Boolean =
            other is Way && other.id == id && other.refs.contentEquals(refs) && other.tags == tags
        override fun hashCode(): Int = (id.hashCode() * 31 + refs.contentHashCode()) * 31 + tags.hashCode()
        override fun toString(): String = "Way(id=$id, refs=${refs.toList()}, tags=$tags)"
    }

    data class Relation(
        override val id: Long,
        val members: List<Member>,
        override val tags: Map<String, String> = emptyMap(),
    ) : OsmEntity() {
        override val type: EntityType get() = EntityType.RELATION
    }

    data class Member(val type: EntityType, val ref: Long, val role: String)
}
