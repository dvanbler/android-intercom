package com.vanbler.intercom

/**
 * Converts an arbitrary mono/stereo PCM WAV (as produced by the Google TTS
 * engine — typically 16-bit signed at 22050/24000 Hz) into the intercom's
 * native format: 32 kHz, 8-bit UNSIGNED mono PCM (128 = silence).
 *
 * This matches the rest of the pipeline exactly: AudioTrack ENCODING_PCM_8BIT
 * is fed unsigned bytes, ReverbProcessor treats 128 as zero, and the Arduino
 * silence buffer is 128. Do NOT add a sign flip here.
 */
object PcmConverter {

    private const val TARGET_RATE = 32000

    fun wavTo32kUnsigned8(wav: ByteArray): ByteArray {
        fun le16(o: Int) =
            (wav[o].toInt() and 0xFF) or ((wav[o + 1].toInt() and 0xFF) shl 8)

        fun le32(o: Int) =
            (wav[o].toInt() and 0xFF) or
            ((wav[o + 1].toInt() and 0xFF) shl 8) or
            ((wav[o + 2].toInt() and 0xFF) shl 16) or
            ((wav[o + 3].toInt() and 0xFF) shl 24)

        require(wav.size > 44 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "Not a RIFF/WAV stream"
        }

        // Defaults in case a chunk is missing; overwritten by the real header.
        var rate = 22050
        var bits = 16
        var channels = 1
        var dataOff = 44
        var dataLen = wav.size - 44

        // Walk the chunk list rather than assuming fixed offsets — the engine
        // sometimes inserts a LIST/fact chunk before "data".
        var p = 12
        while (p + 8 <= wav.size) {
            val id = String(wav, p, 4, Charsets.US_ASCII)
            val sz = le32(p + 4)
            when (id) {
                "fmt " -> {
                    channels = le16(p + 10)
                    rate = le32(p + 12)
                    bits = le16(p + 22)
                }
                "data" -> {
                    dataOff = p + 8
                    dataLen = sz
                }
            }
            p += 8 + sz + (sz and 1) // chunks are word-aligned
        }
        // Guard against a header that claims more data than the file holds.
        dataLen = dataLen.coerceAtMost(wav.size - dataOff)

        val bytesPerSample = bits / 8
        val frameStep = channels * bytesPerSample
        if (frameStep <= 0) return ByteArray(0)
        val frames = dataLen / frameStep
        if (frames == 0) return ByteArray(0)

        // --- decode source to mono float in [-1, 1] ---
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            val base = dataOff + i * frameStep
            var acc = 0f
            for (c in 0 until channels) {
                val b = base + c * bytesPerSample
                acc += when (bits) {
                    16 -> {
                        val lo = wav[b].toInt() and 0xFF
                        val hi = wav[b + 1].toInt()        // sign-extends → signed16
                        ((hi shl 8) or lo) / 32768f
                    }
                    8 -> (((wav[b].toInt() and 0xFF) - 128)) / 128f  // 8-bit WAV is unsigned
                    else -> 0f
                }
            }
            mono[i] = acc / channels
        }

        // --- linear-resample to 32 kHz, emit unsigned 8-bit (128 = silence) ---
        val outLen = (frames.toLong() * TARGET_RATE / rate).toInt().coerceAtLeast(0)
        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * rate / TARGET_RATE
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = mono[i0.coerceIn(0, frames - 1)]
            val b = mono[(i0 + 1).coerceIn(0, frames - 1)]
            val v = a + (b - a) * frac
            out[i] = (((v * 127f) + 128f).toInt().coerceIn(0, 255)).toByte()
        }
        return out
    }
}
