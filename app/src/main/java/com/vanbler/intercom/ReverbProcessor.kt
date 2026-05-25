package com.vanbler.intercom

/**
 * Simple comb filter reverb for unsigned 8-bit PCM (0-255).
 *
 * Tuning:
 *   DELAY_SAMPLES — delay length in samples at 32kHz
 *                   6400 = 200ms, 9600 = 300ms, 12800 = 400ms
 *   DECAY         — feedback amount, 0.0 = no echo, 1.0 = infinite echo
 *                   0.5 = each repeat half the volume of the previous
 */
class ReverbProcessor(
    private val delaySamples: Int = 6400,  // 200ms at 32kHz
    private val decay: Float = 0.5f
) {
    private val delayBuffer = ByteArray(delaySamples) { 128.toByte() }  // silence = 128
    private var writePos = 0

    /**
     * Process a buffer of unsigned 8-bit PCM in-place.
     * Input and output are both unsigned (0-255) stored in signed Kotlin bytes.
     */
    fun process(buffer: ByteArray, len: Int = buffer.size) {
        for (i in 0 until len) {
            val readPos = (writePos - delaySamples + delaySamples) % delaySamples

            // Convert unsigned bytes to signed ints centered around 0
            val delayed = (delayBuffer[readPos].toInt() and 0xFF) - 128
            val current = (buffer[i].toInt() and 0xFF) - 128

            // Mix: output = current + decay * delayed
            var mixed = current + (delayed * decay).toInt()

            // Clamp to [-128, 127] then shift back to unsigned [0, 255]
            mixed = mixed.coerceIn(-128, 127)
            val output = (mixed + 128).toByte()

            // Write output into delay buffer for feedback
            delayBuffer[writePos] = output
            writePos = (writePos + 1) % delaySamples

            buffer[i] = output
        }
    }

    fun reset() {
        delayBuffer.fill(128.toByte())
        writePos = 0
    }
}