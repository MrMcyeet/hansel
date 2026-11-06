package dev.hansel.guidance

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin TTS wrapper; uses the device engine (offline-capable) so guidance keeps talking in airplane mode. */
class Speaker(context: Context) {
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            while (pending.isNotEmpty()) say(pending.removeFirst())
        }
    }

    @Volatile var enabled: Boolean = true

    fun say(text: String) {
        if (!enabled) return
        if (!ready) { pending.addLast(text); return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hansel-${System.nanoTime()}")
    }

    fun shutdown() = tts.shutdown()
}
