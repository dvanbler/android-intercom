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
import kotlinx.coroutines.Dispatchers
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
        private const val CHUNK_SIZE = 548
        private const val WAV_HEADER_SIZE = 44
    }

    private var activeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val reverb = ReverbProcessor(delaySamples = 3200, decay = 0.3f)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun streamWavThenMic(resId: Int) {
        launchExclusive {
            streamWavFileInternal(resId)
            if (isActive) streamMicInternal()
        }
    }

    fun streamWav(resId: Int) {
        launchExclusive {
            streamWavFileInternal(resId)
        }
    }

    fun streamMic() {
        launchExclusive {
            streamMicInternal()
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.streamWavFileInternal(resId: Int) {
        val pcmData = loadPcmFromWav(resId)

        val audioTrack = AudioTrack.Builder()
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

        val audioQueue = LinkedBlockingQueue<ByteArray>()
        val audioThread = newSingleThreadContext("AudioTrackWriter")
        val audioJob = launch(audioThread) {
            while (isActive) {
                val chunk = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                audioTrack.write(chunk, 0, chunk.size)
            }
        }

        val startTime = System.nanoTime()
        var bytesSent = 0L
        var offset = 0

        try {
            while (isActive && offset < pcmData.size) {
                val end = minOf(offset + CHUNK_SIZE, pcmData.size)
                val chunk = ByteArray(CHUNK_SIZE).also {
                    pcmData.copyInto(it, destinationOffset = 0, startIndex = offset, endIndex = end)
                }

                val sleepUntil = startTime + (bytesSent * 1_000_000_000L / SAMPLE_RATE)
                while (System.nanoTime() < sleepUntil) { /* busy wait */ }

                udpSender.send(chunk)
                audioQueue.offer(chunk)

                bytesSent += CHUNK_SIZE
                offset += CHUNK_SIZE
            }
        } finally {
            audioJob.cancel()
            runBlocking { audioJob.join() }
            audioThread.close()
            audioTrack.stop()
            audioTrack.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun CoroutineScope.streamMicInternal() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_8BIT
        )
        val bufferSize = maxOf(minBuffer, CHUNK_SIZE)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_8BIT,
            bufferSize
        )

        audioRecord.startRecording()
        val buffer = ByteArray(CHUNK_SIZE)

        try {
            while (isActive) {
                val bytesRead = audioRecord.read(buffer, 0, CHUNK_SIZE)
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
        return raw.drop(WAV_HEADER_SIZE).toByteArray()
    }

    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        activeJob?.cancel()
        activeJob = scope.launch(block = block)
    }
}