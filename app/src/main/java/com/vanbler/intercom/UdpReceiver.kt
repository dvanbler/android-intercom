package com.vanbler.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpReceiver(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 11025
        private const val CHUNK_SIZE = 548
        private const val PORT = 6770
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    @Volatile private var paused = false

    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

            val udpSocket = DatagramSocket(PORT)
            udpSocket.soTimeout = 200  // unblock every 200ms to check isActive/paused
            socket = udpSocket

            val buffer = ByteArray(CHUNK_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            try {
                while (isActive) {
                    try {
                        udpSocket.receive(packet)
                        if (!paused) {
                            audioTrack.write(buffer, 0, packet.length)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // expected — just loop to check isActive/paused
                    }
                }
            } finally {
                audioTrack.stop()
                audioTrack.release()
                udpSocket.close()
                socket = null
            }
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }
}