package com.vanbler.intercom

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around the platform TextToSpeech engine.
 *
 * One instance is created in MainActivity and shared by the Speak screen.
 * Pitch and voice are applied per-synthesis (baked into the generated WAV),
 * which is exactly the "Generate captures the settings" behavior we want.
 */
class TtsSynthesizer(context: Context) {

    @Volatile private var ready = false
    private val pending = mutableListOf<() -> Unit>()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pending.forEach { it() }
            pending.clear()
        }
    }

    /** Runs [block] now if the engine is ready, otherwise when init completes. */
    fun whenReady(block: () -> Unit) {
        if (ready) block() else pending.add(block)
    }

    /** Locally-installed voices, best quality first. Network voices are excluded. */
    fun voices(): List<Voice> =
        try {
            tts.voices
                ?.filter { !it.isNetworkConnectionRequired }
                ?.sortedWith(
                    compareBy({ it.locale.toLanguageTag() }, { -it.quality }, { it.name })
                )
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Synthesize [text] with [voice] and [pitch] into a temp WAV in [cacheDir].
     * Suspends until the engine reports done; throws on any failure.
     */
    suspend fun synthesizeToWav(
        text: String,
        voice: Voice,
        pitch: Float,
        cacheDir: File
    ): File = suspendCancellableCoroutine { cont ->
        tts.voice = voice
        tts.setPitch(pitch)

        val out = File(cacheDir, "tts_${System.currentTimeMillis()}.wav")
        val id = "intercom_tts"

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (cont.isActive) cont.resume(out)
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                if (cont.isActive) cont.resumeWithException(RuntimeException("TTS synthesis failed"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (cont.isActive) {
                    cont.resumeWithException(RuntimeException("TTS synthesis failed (code $errorCode)"))
                }
            }
        })

        val result = tts.synthesizeToFile(text, Bundle(), out, id)
        if (result != TextToSpeech.SUCCESS && cont.isActive) {
            cont.resumeWithException(
                RuntimeException("synthesizeToFile rejected — check the voice/text")
            )
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
