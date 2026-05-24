package com.vanbler.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vanbler.intercom.ui.theme.IntercomTheme
import kotlinx.coroutines.launch
import java.net.InetAddress

class MainActivity : ComponentActivity() {

    companion object {
        private const val TARGET_HOST = "192.168.143.15"
        private const val TARGET_PORT = 6769
        private val PTT_INTRO_RES = R.raw.ptt_intro
    }

    private lateinit var udpSender: UdpSender
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var udpReceiver: UdpReceiver

    private var onPermissionResult: (Boolean) -> Unit = {}

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted)
        }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        udpSender = UdpSender(InetAddress.getByName(TARGET_HOST), TARGET_PORT)

        val attributedContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createAttributionContext("ptt")
        } else {
            this
        }
        audioStreamer = AudioStreamer(udpSender, attributedContext)

        udpReceiver = UdpReceiver(attributedContext)
        udpReceiver.start()

        enableEdgeToEdge()
        setContent {
            IntercomTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var isPressing by remember { mutableStateOf(false) }

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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(180.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPressing) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            withAudioPermission {
                                                isPressing = true
                                                udpReceiver.pause()
                                                audioStreamer.streamWavThenMic(PTT_INTRO_RES)
                                            }
                                            tryAwaitRelease()
                                            isPressing = false
                                            audioStreamer.stop()
                                            udpReceiver.resume()
                                        }
                                    )
                                }
                        ) {
                            Text(
                                text = if (isPressing) "LIVE" else "PTT",
                                fontSize = 28.sp,
                                color = MaterialTheme.colorScheme.onPrimary
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
    }
}