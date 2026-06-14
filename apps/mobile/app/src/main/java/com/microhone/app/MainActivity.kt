package com.microhone.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.microhone.app.audio.AudioStreamer
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val streamer = AudioStreamer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MicrohoneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocScreen(streamer)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Faz 1 captures only while in the foreground; a foreground service
        // for background capture lands in a later phase.
        streamer.stop()
    }
}

@Composable
fun MicrohoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun PocScreen(streamer: AudioStreamer) {
    val context = LocalContext.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47801") }
    var streaming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun beginStreaming() {
        status = null
        val parsedPort = port.toIntOrNull()
        if (host.isBlank() || parsedPort == null || parsedPort !in 1..65535) {
            status = "Enter a valid PC IP and port"
            return
        }
        streamer.start(host.trim(), parsedPort) { message ->
            status = "Error: $message"
            streaming = false
        }
        streaming = true
        status = "Streaming to $host:$parsedPort"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) beginStreaming() else status = "Microphone permission denied"
    }

    LaunchedEffect(streaming) {
        while (streaming) {
            level = streamer.peakLevel
            delay(80)
        }
        level = 0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = "🎙️ microhone", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Faz 1 — audio PoC",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("PC IP address") },
            placeholder = { Text("192.168.1.42") },
            singleLine = true,
            enabled = !streaming,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            enabled = !streaming,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (streaming) {
                    streamer.stop()
                    streaming = false
                    status = "Stopped"
                } else if (hasMicPermission) {
                    beginStreaming()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (streaming) "Stop" else "Start")
        }

        status?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
