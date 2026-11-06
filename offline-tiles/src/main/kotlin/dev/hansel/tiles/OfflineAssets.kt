package dev.hansel.tiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** One file to fetch for offline rendering. [relativePath] is relative to the assets directory. */
data class AssetDownload(val url: String, val relativePath: String)

data class AssetProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val skipped: Int, val current: String?)

/**
 * Plans and downloads the glyphs and sprites a localized style needs.
 *
 * Sizes measured on 2026-11-05 against protomaps.github.io/basemaps-assets (all 256 ranges per font):
 * - Noto Sans Regular 6.24 MB, Noto Sans Italic 1.22 MB, Noto Sans Medium 3.61 MB,
 *   Noto Sans Devanagari Regular v1 6.63 MB -> 17.7 MB / 1024 files for the full set of the four fonts
 *   the Protomaps styles use. Most ranges above U+2FFF are 26-29 byte stubs (Noto Sans has no CJK).
 * - [BASIC_LATIN_PLUS] (ranges 0-2559 and 8192-8447: Latin, Greek, Cyrillic, Hebrew, Arabic, Devanagari,
 *   general punctuation) -> 3.5 MB / 44 files.
 * - Sprites: ~52 KB per theme (dark.json 3.5 KB, dark.png 16 KB, dark@2x.json 3.6 KB, dark@2x.png 28 KB).
 */
object OfflineAssets {
    /** Every glyph range MapLibre may request: `0-255` ... `65280-65535`. */
    val ALL_RANGES: List<String> = (0 until 256).map { "${it * 256}-${it * 256 + 255}" }

    /** Ranges 0-255 ... 2304-2559 (U+0000-U+09FF) plus 8192-8447 (general punctuation, dashes, quotes). */
    val BASIC_LATIN_PLUS: List<String> = (0 until 10).map { "${it * 256}-${it * 256 + 255}" } + "8192-8447"

    val SPRITE_SUFFIXES = listOf(".json", ".png", "@2x.json", "@2x.png")

    fun plan(styleJson: String, ranges: List<String> = ALL_RANGES): List<AssetDownload> {
        val style = Json.parseToJsonElement(styleJson).jsonObject
        val out = ArrayList<AssetDownload>()

        val glyphs = style["glyphs"]?.jsonPrimitive?.content
        if (glyphs != null) {
            for (stack in OfflineStyle.fontStacks(style)) {
                val encoded = URLEncoder.encode(stack, "UTF-8").replace("+", "%20")
                for (range in ranges) {
                    out += AssetDownload(
                        url = glyphs.replace("{fontstack}", encoded).replace("{range}", range),
                        relativePath = "${OfflineStyle.GLYPHS_SUBDIR}/$stack/$range.pbf",
                    )
                }
            }
        }

        val spriteUrls: List<String> = when (val s = style["sprite"]) {
            null -> emptyList()
            is JsonPrimitive -> listOf(s.content)
            is JsonArray -> s.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.content }
            else -> emptyList()
        }
        for (sprite in spriteUrls) {
            val theme = OfflineStyle.spriteTheme(sprite)
            for (suffix in SPRITE_SUFFIXES) {
                out += AssetDownload(sprite + suffix, "${OfflineStyle.SPRITES_SUBDIR}/$theme$suffix")
            }
        }
        return out
    }

    /**
     * Download every planned file under [destDir] (skipping existing non-empty files) with [concurrency]
     * parallel requests. Files are written to a `.part` sibling and renamed on success. Returns the number
     * of files downloaded (not counting skipped ones).
     */
    suspend fun download(
        plan: List<AssetDownload>,
        client: OkHttpClient,
        destDir: File,
        progress: (AssetProgress) -> Unit = {},
        concurrency: Int = 4,
    ): Int {
        val mutex = Mutex()
        var done = 0
        var skipped = 0
        var bytes = 0L
        var downloaded = 0
        val work = Channel<AssetDownload>(Channel.UNLIMITED)
        for (a in plan) work.send(a)
        work.close()
        coroutineScope {
            repeat(concurrency.coerceAtLeast(1)) {
                launch {
                    for (a in work) {
                        val target = File(destDir, a.relativePath)
                        val fetched = withContext(Dispatchers.IO) {
                            if (target.exists() && target.length() > 0) return@withContext -1L
                            target.parentFile?.mkdirs()
                            val part = File(target.path + ".part")
                            val body = fetch(client, a.url)
                            part.writeBytes(body)
                            if (!part.renameTo(target)) {
                                target.delete()
                                if (!part.renameTo(target)) throw IOException("cannot write ${target.path}")
                            }
                            body.size.toLong()
                        }
                        mutex.withLock {
                            done++
                            if (fetched < 0) skipped++ else { bytes += fetched; downloaded++ }
                            progress(AssetProgress(done, plan.size, bytes, skipped, a.relativePath))
                        }
                    }
                }
            }
        }
        return downloaded
    }

    private fun fetch(client: OkHttpClient, url: String): ByteArray {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.code == 429 || resp.code >= 500) throw IOException("HTTP ${resp.code} for $url")
                    if (!resp.isSuccessful) throw NonRetryable("HTTP ${resp.code} for $url")
                    return resp.body.bytes()
                }
            } catch (e: NonRetryable) {
                throw IOException(e.message)
            } catch (e: IOException) {
                last = e
                if (attempt < 2) Thread.sleep(500L shl attempt)
            }
        }
        throw last ?: IOException("failed: $url")
    }

    private class NonRetryable(msg: String) : RuntimeException(msg)
}
