package com.vanbler.intercom

import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Reverb baked into generated speech (unsigned 8-bit, 128 = silence).
// Same character as the intercom mic path; tune freely.
private const val TTS_REVERB_DELAY_SAMPLES = 3200   // 100ms at 32kHz
private const val TTS_REVERB_DECAY = 0.3f
private const val TTS_REVERB_TAIL_SAMPLES = 9600    // ~300ms ring-out

private fun applyReverb(dry: ByteArray): ByteArray {
    if (dry.isEmpty()) return dry
    // Pad with silence so the comb-filter tail rings out instead of clipping.
    val wet = ByteArray(dry.size + TTS_REVERB_TAIL_SAMPLES) { 128.toByte() }
    dry.copyInto(wet)
    ReverbProcessor(
        delaySamples = TTS_REVERB_DELAY_SAMPLES,
        decay = TTS_REVERB_DECAY
    ).process(wet)
    return wet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(
    tts: TtsSynthesizer,
    audioStreamer: AudioStreamer,
    udpReceiver: UdpReceiver,
    chimeRes: Int,
    cacheDir: File,
) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var pitch by remember { mutableStateOf(1.0f) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<Voice?>(null) }
    var voiceMenuOpen by remember { mutableStateOf(false) }

    var speechPcm by remember { mutableStateOf<ByteArray?>(null) }
    var chimeChecked by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }

    // Populate voices once the engine has finished initializing.
    LaunchedEffect(Unit) {
        tts.whenReady {
            val vs = tts.voices()
            voices = vs
            selectedVoice = vs.firstOrNull { it.locale.language == Locale.getDefault().language }
                ?: vs.firstOrNull()
        }
    }

    // Any change to text / pitch / voice invalidates the held buffer.
    fun invalidate() { speechPcm = null; status = null }

    val ready = speechPcm!= null && !generating

    // Read latest values inside the long-lived pointerInput handlers.
    val currentSpeech by rememberUpdatedState(speechPcm)
    val currentChime by rememberUpdatedState(chimeChecked)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- Voice ----
        ExposedDropdownMenuBox(
            expanded = voiceMenuOpen,
            onExpandedChange = { voiceMenuOpen = it }
        ) {
            OutlinedTextField(
                value = selectedVoice?.let { "${it.locale} · ${it.name}" } ?: "Loading voices…",
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuOpen)
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = voiceMenuOpen,
                onDismissRequest = { voiceMenuOpen = false }
            ) {
                voices.forEach { v ->
                    DropdownMenuItem(
                        text = { Text("${v.locale} · ${v.name}", fontSize = 13.sp) },
                        onClick = {
                            selectedVoice = v
                            voiceMenuOpen = false
                            invalidate()
                        }
                    )
                }
            }
        }

        // ---- Pitch ----
        Text("Pitch: ${"%.2f".format(pitch)}")
        Slider(
            value = pitch,
            onValueChange = { pitch = it; invalidate() },
            valueRange = 0.5f..2.0f
        )

        // ---- Text ----
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; invalidate() },
            label = { Text("Text to speak") },
            placeholder = { Text("Type, or tap the mic key on the keyboard") },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        )

        // ---- Generate ----
        Button(
            onClick = {
                val v = selectedVoice ?: return@Button
                generating = true
                status = null
                scope.launch {
                    try {
                        val wav = tts.synthesizeToWav(text.trim(), v, pitch, cacheDir)
                        val pcm = withContext(Dispatchers.Default) {
                            val bytes = wav.readBytes()
                            wav.delete()
                            val dry = PcmConverter.wavTo32kUnsigned8(bytes)
                            applyReverb(dry)
                        }
                        speechPcm = pcm
                        status = "Ready · ${"%.1f".format(pcm.size / 32000.0)}s"
                    } catch (e: Exception) {
                        speechPcm = null
                        status = "Failed: ${e.message}"
                    } finally {
                        generating = false
                    }
                }
            },
            enabled = !generating && text.isNotBlank() && selectedVoice != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (generating) "Generating…" else "Generate")
        }

        status?.let { Text(it, fontSize = 13.sp) }

        // ---- Chime toggle ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = chimeChecked, onCheckedChange = { chimeChecked = it })
            Text("Play chime before speech")
        }

        // ---- Preview (tap) + Send (hold) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Preview — local speaker only; pauses the receiver while it plays.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (ready) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                if (down.changes.any { it.pressed }) {
                                    down.changes.forEach { it.consume() }
                                    val pcm = currentSpeech
                                    if (pcm != null) {
                                        udpReceiver.pause()
                                        audioStreamer.previewPcm(pcm) {
                                            udpReceiver.resume()
                                        }
                                    }
                                    do {
                                        val e = awaitPointerEvent()
                                        e.changes.forEach { it.consume() }
                                    } while (e.changes.any { it.pressed })
                                }
                            }
                        }
                    }
            ) {
                Text(
                    text = "Preview",
                    textAlign = TextAlign.Center,
                    color = if (ready) MaterialTheme.colorScheme.onSecondary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Send — hold to stream chime?+speech over UDP + speaker; release = kill.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isSending -> MaterialTheme.colorScheme.error
                            ready -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                if (down.changes.any { it.pressed }) {
                                    down.changes.forEach { it.consume() }
                                    val pcm = currentSpeech
                                    if (pcm != null) {
                                        isSending = true
                                        udpReceiver.pause()
                                        audioStreamer.sendSpeech(
                                            pcm,
                                            if (currentChime) chimeRes else null
                                        )
                                        do {
                                            val e = awaitPointerEvent()
                                            e.changes.forEach { it.consume() }
                                        } while (e.changes.any { it.pressed })
                                        isSending = false
                                        audioStreamer.stop()
                                        udpReceiver.resume()
                                    } else {
                                        // Not ready — swallow the press, do nothing.
                                        do {
                                            val e = awaitPointerEvent()
                                            e.changes.forEach { it.consume() }
                                        } while (e.changes.any { it.pressed })
                                    }
                                }
                            }
                        }
                    }
            ) {
                Text(
                    text = if (isSending) "SENDING" else "Send",
                    textAlign = TextAlign.Center,
                    color = when {
                        isSending -> MaterialTheme.colorScheme.onError
                        ready -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
