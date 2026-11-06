package dev.hansel.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** What the user asked the build process to do. Serialized to `job.json` so the service can restart it. */
@Serializable
data class OfflineJobRequest(
    /** Region ids from the catalog (e.g. "us/mississippi"). The build covers the union of these and already-installed ones. */
    val regionIds: List<String>,
    val tilesMaxZoom: Int = 15,
    val protomapsKey: String,
    val styleThemes: List<String> = listOf("dark", "light"),
    /** Skip phases whose outputs are already current (used for "add a region" and retries). */
    val incremental: Boolean = true,
    val requestedAtMillis: Long = System.currentTimeMillis(),
)

internal val OfflineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

/** Atomic small-file persistence shared by both processes. */
internal object JsonFiles {
    inline fun <reified T> read(file: File): T? = runCatching { if (file.isFile) OfflineJson.decodeFromString<T>(file.readText()) else null }.getOrNull()

    inline fun <reified T> write(file: File, value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(OfflineJson.encodeToString(value))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
