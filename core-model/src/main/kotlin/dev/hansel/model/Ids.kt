package dev.hansel.model

import java.util.UUID

/** Collision-resistant IDs for requests, waypoints, locks, reports. */
object Ids {
    fun newId(prefix: String = ""): String = if (prefix.isEmpty()) UUID.randomUUID().toString() else "$prefix-${UUID.randomUUID()}"
}
