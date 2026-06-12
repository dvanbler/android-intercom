package com.vanbler.intercom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    private lateinit var ttsSynthesizer: TtsSynthesizer
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        udpSender = UdpSender(InetAddress.getByName(TARGET_HOST), TARGET_PORT)

        val attributedContext = createAttributionContext("ptt")
        audioStreamer = AudioStreamer(udpSender, attributedContext)

        udpReceiver = UdpReceiver(attributedContext)
        udpReceiver.start()

        ttsSynthesizer = TtsSynthesizer(this)

        val soundboardEntries = loadSoundboardEntries()

        enableEdgeToEdge()
        setContent {
            IntercomTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var selectedTab by remember { mutableStateOf(0) }

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
                    ) {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Intercom") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Speak") }
                            )
                        }

                        when (selectedTab) {
                            0 -> IntercomScreen(
                                audioStreamer = audioStreamer,
                                udpReceiver = udpReceiver,
                                soundboardEntries = soundboardEntries,
                                pttIntroRes = PTT_INTRO_RES,
                                withAudioPermission = { action -> withAudioPermission(action) }
                            )

                            1 -> TtsScreen(
                                tts = ttsSynthesizer,
                                audioStreamer = audioStreamer,
                                udpReceiver = udpReceiver,
                                chimeRes = PTT_INTRO_RES,
                                cacheDir = cacheDir
                            )
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
        ttsSynthesizer.shutdown()
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
