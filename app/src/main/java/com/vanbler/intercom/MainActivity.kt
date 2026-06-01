package com.vanbler.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vanbler.intercom.ui.theme.IntercomTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

data class SoundboardEntry(val resId: Int, val label: String)

class MainActivity : ComponentActivity() {

    companion object {
        private const val TARGET_HOST = "192.168.143.15"
        private const val TARGET_PORT = 6769
        private const val HEARTBEAT_PORT = 6771
        private val PTT_INTRO_RES = R.raw.ptt_intro
    }

    private lateinit var udpSender: UdpSender
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var udpReceiver: UdpReceiver
    private var heartbeatJob: Job? = null
    private val heartbeatScope = CoroutineScope(Dispatchers.IO)
    private var onPermissionResult: (Boolean) -> Unit = {}

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted)
        }

    private fun loadSoundboardEntries(): List<SoundboardEntry> {
        val rawClass = R.raw::class.java
        return rawClass.fields
            .filter { it.name.startsWith("sb_") }
            .mapNotNull { field ->
                val parts = field.name.split("_", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val order = parts[1].toIntOrNull() ?: return@mapNotNull null
                val label = parts[2].replace("_", " ")
                val resId = field.getInt(null)
                Triple(order, label, resId)
            }
            .sortedBy { it.first }
            .map { SoundboardEntry(it.third, it.second) }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        udpSender = UdpSender(InetAddress.getByName(TARGET_HOST), TARGET_PORT)

        val attributedContext = createAttributionContext("ptt")
        audioStreamer = AudioStreamer(udpSender, attributedContext)

        udpReceiver = UdpReceiver(attributedContext)
        udpReceiver.start()

        val soundboardEntries = loadSoundboardEntries()

        enableEdgeToEdge()
        setContent {
            IntercomTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var isPressing by remember { mutableStateOf(false) }
                var isPressingDirect by remember { mutableStateOf(false) }
                val pressedSoundboard = remember { mutableStateMapOf<Int, Boolean>() }

                fun withAudioPermission(action: () -> Unit) {
                    when {
                        ContextCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED -> action()

                        else -> {
                            onPermissionResult = { granted ->
                                if (granted) action()
                                else scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Microphone permission is required for PTT"
                                    )
                                }
                            }
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ---------------------------------------------------------
                        // PTT buttons
                        // ---------------------------------------------------------
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterHorizontally
                            )
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
                                                        audioStreamer.streamWavThenMic(PTT_INTRO_RES)
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

                        // ---------------------------------------------------------
                        // Soundboard
                        // ---------------------------------------------------------
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioStreamer.stop()
        udpSender.close()
        udpReceiver.stop()
        heartbeatScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        heartbeatJob = heartbeatScope.launch {
            val payload = byteArrayOf(1)  // minimal payload
            while (isActive) {
                try {
                    udpSender.sendTo(payload, HEARTBEAT_PORT)
                } catch (_: Exception) {
                    // ignore transient send failures
                }
                delay(1000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}