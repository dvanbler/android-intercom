package com.vanbler.intercom

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioStreamer(private val udpSender: UdpSender, private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 32000
        private const val MAX_CHUNK_SIZE = 548
        private const val WAV_HEADER_SIZE = 44
    }

    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val reverb = ReverbProcessor(delaySamples = 3200, decay = 0.3f)
    private val fadeInBuffer = ByteArray(128) { i -> i.toByte() }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun streamWavThenMic(resId: Int) {
        launchExclusive {
            streamPcmInternal(loadPcmFromWav(resId), toSpeaker = true, toUdp = true)
            if (isActive) streamMicInternal()
        }
    }

    fun streamWav(resId: Int) {
        launchExclusive {
            streamPcmInternal(loadPcmFromWav(resId), toSpeaker = true, toUdp = true)
        }
    }

    fun streamMic() {
        launchExclusive {
            streamMicInternal()
        }
    }

    /**
     * Local-only preview (speaker, no UDP). [onCompleted] fires only on natural
     * completion — NOT on cancellation — so the caller can resume the receiver
     * without fighting an interrupting action that owns its own pause/resume.
     */
    fun previewPcm(pcm: ByteArray, onCompleted: () -> Unit) {
        launchExclusive {
            streamPcmInternal(pcm, toSpeaker = true, toUdp = false)
            if (isActive) onCompleted()
        }
    }

    /**
     * Hold-to-send: optional chime (from a raw WAV res) then the speech buffer,
     * both to speaker + UDP. Releasing the button calls [stop], which cancels
     * mid-stream — the kill switch. If released during the chime, the isActive
     * gate skips the speech entirely.
     */
    fun sendSpeech(speechPcm: ByteArray, chimeResId: Int?) {
        launchExclusive {
            if (chimeResId != null) {
                streamPcmInternal(loadPcmFromWav(chimeResId), toSpeaker = true, toUdp = true)
            }
            if (isActive) {
                streamPcmInternal(speechPcm, toSpeaker = true, toUdp = true)
            }
        }
    }

    fun stop() {
        reverb.reset()
        activeJob?.cancel()
        activeJob = null
    }

    // -------------------------------------------------------------------------
    // Internal streaming logic
    // -------------------------------------------------------------------------

    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch(block = block)
    }

    /**
     * Streams a pre-converted 32kHz/8-bit/unsigned PCM buffer.
     *   toSpeaker — play locally via AudioTrack (writer thread + queue)
     *   toUdp     — send 548-byte packets over UDP, paced to 32kHz
     * Pacing (the busy-wait) is kept on always so local playback runs at correct
     * real-time speed and the UDP feed never bursts.
     */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun CoroutineScope.streamPcmInternal(
        pcmData: ByteArray,
        toSpeaker: Boolean,
        toUdp: Boolean
    ) {
        Thread.sleep(50)

        var audioTrack: AudioTrack? = null
        var audioThread: ExecutorCoroutineDispatcher? = null
        var audioJob: Job? = null
        val audioQueue = LinkedBlockingQueue<ByteArray>()

        if (toSpeaker) {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_8BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_8BIT
                    )
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setContext(context)
                    }
                }
                .build()

            audioTrack.play()

            val dispatcher = newSingleThreadContext("AudioTrackWriter")
            audioThread = dispatcher
            val track = audioTrack
            audioJob = launch(dispatcher) {
                while (isActive) {
                    val chunk = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    track.write(chunk, 0, chunk.size)
                }
            }
        }

        val startTime = System.nanoTime()
        var offset = 0

        if (toUdp) udpSender.send(fadeInBuffer)

        try {
            while (isActive && offset < pcmData.size) {
                val end = minOf(offset + MAX_CHUNK_SIZE, pcmData.size)
                val chunkLen = end - offset
                val chunk = ByteArray(chunkLen).also {
                    pcmData.copyInto(it, destinationOffset = 0, startIndex = offset, endIndex = end)
                }

                val sleepUntil = startTime + (offset * 1_000_000_000L / SAMPLE_RATE)
                while (System.nanoTime() < sleepUntil) { /* busy wait */ }

                if (toUdp) udpSender.send(chunk)
                if (toSpeaker) audioQueue.offer(chunk)

                offset += chunkLen
            }
        } finally {
            if (toSpeaker) {
                audioJob?.cancel()
                runBlocking { audioJob?.join() }
                audioThread?.close()
                audioTrack?.stop()
                audioTrack?.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun CoroutineScope.streamMicInternal() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_8BIT
        )
        val bufferSize = maxOf(minBuffer, MAX_CHUNK_SIZE)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_8BIT,
            bufferSize
        )

        audioRecord.startRecording()
        val buffer = ByteArray(MAX_CHUNK_SIZE)

        udpSender.send(fadeInBuffer)

        try {
            while (isActive) {
                val bytesRead = audioRecord.read(buffer, 0, MAX_CHUNK_SIZE)
                if (bytesRead > 0) {
                    reverb.process(buffer, bytesRead)
                    udpSender.send(buffer)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun loadPcmFromWav(resId: Int): ByteArray {
        val raw = context.resources.openRawResource(resId).readBytes()
        // Data chunk size is at offset 40, little-endian
        val dataSize = (raw[40].toInt() and 0xFF) or
                ((raw[41].toInt() and 0xFF) shl 8) or
                ((raw[42].toInt() and 0xFF) shl 16) or
                ((raw[43].toInt() and 0xFF) shl 24)
        return raw.copyOfRange(WAV_HEADER_SIZE, WAV_HEADER_SIZE + dataSize)
    }
}
