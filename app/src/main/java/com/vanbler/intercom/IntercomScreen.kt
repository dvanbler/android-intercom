package com.vanbler.intercom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The original single-screen UI (PTT Chime, PTT, soundboard) lifted verbatim
 * into a composable so MainActivity can host it behind a tab alongside the
 * Speak screen. Shared instances are passed in; nothing is created here.
 */
@Composable
fun IntercomScreen(
    audioStreamer: AudioStreamer,
    udpReceiver: UdpReceiver,
    soundboardEntries: List<SoundboardEntry>,
    pttIntroRes: Int,
    withAudioPermission: (action: () -> Unit) -> Unit,
) {
    var isPressing by remember { mutableStateOf(false) }
    var isPressingDirect by remember { mutableStateOf(false) }
    val pressedSoundboard = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // -------------------------------------------------------------------
        // PTT buttons
        // -------------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
        ) {
            // PTT Chime — plays intro WAV then mic
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPressing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                if (down.changes.any { it.pressed }) {
                                    down.changes.forEach { it.consume() }
                                    withAudioPermission {
                                        isPressing = true
                                        udpReceiver.pause()
                                        audioStreamer.streamWavThenMic(pttIntroRes)
                                    }
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    isPressing = false
                                    audioStreamer.stop()
                                    udpReceiver.resume()
                                }
                            }
                        }
                    }
            ) {
                Text(
                    text = if (isPressing) "LIVE" else "PTT Chime",
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // PTT — goes straight to mic
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPressingDirect) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent()
                                if (down.changes.any { it.pressed }) {
                                    down.changes.forEach { it.consume() }
                                    withAudioPermission {
                                        isPressingDirect = true
                                        udpReceiver.pause()
                                        audioStreamer.streamMic()
                                    }
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    isPressingDirect = false
                                    audioStreamer.stop()
                                    udpReceiver.resume()
                                }
                            }
                        }
                    }
            ) {
                Text(
                    text = if (isPressingDirect) "LIVE" else "PTT",
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSecondary
                )
            }
        }

        // -------------------------------------------------------------------
        // Soundboard
        // -------------------------------------------------------------------
        if (soundboardEntries.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(soundboardEntries) { entry ->
                    val isEntryPressed = pressedSoundboard[entry.resId] == true
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isEntryPressed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                            .pointerInput(entry.resId) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent()
                                        if (down.changes.any { it.pressed }) {
                                            down.changes.forEach { it.consume() }
                                            udpReceiver.pause()
                                            pressedSoundboard[entry.resId] = true
                                            audioStreamer.streamWav(entry.resId)
                                            do {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                            pressedSoundboard[entry.resId] = false
                                            audioStreamer.stop()
                                            udpReceiver.resume()
                                        }
                                    }
                                }
                            }
                    ) {
                        Text(
                            text = entry.label,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
