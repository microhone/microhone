package com.microhone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.microhone.app.audio.AudioEngine
import com.microhone.app.net.DeviceDiscovery
import com.microhone.app.net.DiscoveredDevice
import com.microhone.app.service.MicForegroundService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MicrohoneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocScreen()
                }
            }
        }
    }
}

@Composable
fun MicrohoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

@Composable
fun PocScreen() {
    val context = LocalContext.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47801") }
    var useOpus by remember { mutableStateOf(true) }
    var usb by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(AudioEngine.streamer.isRunning) }
    var status by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    val discovery = remember { DeviceDiscovery(context) }
    DisposableEffect(Unit) {
        discovery.onDevicesChanged = { devices = it }
        discovery.start()
        onDispose { discovery.stop() }
    }

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
        val intent = Intent(context, MicForegroundService::class.java).apply {
            action = MicForegroundService.ACTION_START
            putExtra(MicForegroundService.EXTRA_HOST, host.trim())
            putExtra(MicForegroundService.EXTRA_PORT, parsedPort)
            putExtra(MicForegroundService.EXTRA_OPUS, useOpus)
            putExtra(MicForegroundService.EXTRA_USB, usb)
        }
        ContextCompat.startForegroundService(context, intent)
        streaming = true
        val link = if (usb) "USB" else "WiFi"
        status = "Streaming to $host:$parsedPort ($link, ${if (useOpus) "Opus" else "PCM"})"
    }

    fun stopStreaming() {
        context.stopService(Intent(context, MicForegroundService::class.java))
        streaming = false
        status = "Stopped"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
        hasMicPermission = micGranted
        if (micGranted) beginStreaming() else status = "Microphone permission denied"
    }

    fun requestAndStart() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Reflect the shared engine: update the meter and notice if the service
    // stopped on its own (e.g. a network error).
    LaunchedEffect(streaming) {
        var sawRunning = false
        while (streaming) {
            val running = AudioEngine.streamer.isRunning
            if (running) sawRunning = true
            level = AudioEngine.streamer.peakLevel
            if (sawRunning && !running) {
                streaming = false
                status = AudioEngine.lastError?.let { "Error: $it" } ?: "Stopped"
            }
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
            text = "audio PoC",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (devices.isNotEmpty()) {
            Text(
                text = "Found on your network",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            devices.forEach { device ->
                OutlinedButton(
                    onClick = {
                        host = device.host
                        port = device.port.toString()
                    },
                    enabled = !streaming,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${device.name}  —  ${device.host}:${device.port}")
                }
            }
        }

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Opus codec")
            Switch(
                checked = useOpus,
                onCheckedChange = { useOpus = it },
                enabled = !streaming,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("USB cable")
            Switch(
                checked = usb,
                onCheckedChange = {
                    usb = it
                    if (it) {
                        host = "127.0.0.1"
                        port = "47801"
                    }
                },
                enabled = !streaming,
            )
        }

        LinearProgressIndicator(
            progress = { level },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (streaming) {
                    stopStreaming()
                } else if (hasMicPermission) {
                    beginStreaming()
                } else {
                    requestAndStart()
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
