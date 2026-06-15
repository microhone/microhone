package com.microhone.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.microhone.app.audio.AudioEngine
import com.microhone.app.net.DeviceDiscovery
import com.microhone.app.net.DiscoveredDevice
import com.microhone.app.service.MicForegroundService
import kotlinx.coroutines.delay

private val Blue = Color(0xFF3B82F6)
private val Slate900 = Color(0xFF0B1220)
private val Slate500 = Color(0xFF64748B)
private val Slate200 = Color(0xFFE2E8F0)
private val PageBg = Color(0xFFF8FAFC)

private val MicrohoneColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAFE),
    onPrimaryContainer = Color(0xFF0B2A4A),
    background = PageBg,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFEFF3F8),
    outline = Slate200,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force light system bars (dark icons) so they stay visible on our
        // light UI even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            MaterialTheme(colorScheme = MicrohoneColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PocScreen()
                }
            }
        }
    }
}

/** Parsed `microhone://pair?h=..&p=..&k=..` link: host, port and 32-byte key. */
data class Pairing(val host: String, val port: Int, val key: ByteArray)

fun parsePairing(link: String): Pairing? {
    val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "microhone") return null
    val host = uri.getQueryParameter("h") ?: return null
    val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
    val keyParam = uri.getQueryParameter("k") ?: return null
    val key = runCatching {
        Base64.decode(keyParam, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull() ?: return null
    if (key.size != 32) return null
    return Pairing(host, port, key)
}

private const val KEY_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

fun encodeKey(key: ByteArray?): String? =
    key?.let { Base64.encodeToString(it, KEY_FLAGS) }

fun decodeKey(encoded: String?): ByteArray? =
    encoded?.let { runCatching { Base64.decode(it, KEY_FLAGS) }.getOrNull() }
        ?.takeIf { it.size == 32 }

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Slate200),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StatusPill(streaming: Boolean) {
    val bg = if (streaming) MaterialTheme.colorScheme.primaryContainer else Color(0xFFEFF3F8)
    val fg = if (streaming) Color(0xFF0B2A4A) else Slate500
    Surface(color = bg, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                modifier = Modifier.size(6.dp),
                shape = CircleShape,
                color = if (streaming) Blue else Slate500,
            ) {}
            Text(
                text = if (streaming) "Live" else "Idle",
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

@Composable
fun PocScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("microhone", Context.MODE_PRIVATE) }

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("port", "47801") ?: "47801") }
    var useOpus by remember { mutableStateOf(prefs.getBoolean("opus", true)) }
    var usb by remember { mutableStateOf(prefs.getBoolean("usb", false)) }
    var autoConnect by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var pairingLink by remember { mutableStateOf("") }
    var pairingKey by remember { mutableStateOf(decodeKey(prefs.getString("key", null))) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 1f)) }
    var gate by remember { mutableFloatStateOf(prefs.getFloat("gate", 0f)) }
    var muted by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(AudioEngine.streamer.isRunning) }
    var status by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }

    // Remember the connection so the phone reconnects with one tap next time.
    LaunchedEffect(host, port, useOpus, usb, autoConnect, pairingKey, gain, gate) {
        prefs.edit()
            .putString("host", host)
            .putString("port", port)
            .putBoolean("opus", useOpus)
            .putBoolean("usb", usb)
            .putBoolean("auto", autoConnect)
            .putString("key", encodeKey(pairingKey))
            .putFloat("gain", gain)
            .putFloat("gate", gate)
            .apply()
    }

    // Keep the live tuning in sync with the engine, even before streaming.
    LaunchedEffect(gain, gate) {
        AudioEngine.streamer.gain = gain
        AudioEngine.streamer.gateThreshold = gate
    }

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
            status = "Enter a PC address and port, or scan the pairing QR"
            return
        }
        val intent = Intent(context, MicForegroundService::class.java).apply {
            action = MicForegroundService.ACTION_START
            putExtra(MicForegroundService.EXTRA_HOST, host.trim())
            putExtra(MicForegroundService.EXTRA_PORT, parsedPort)
            putExtra(MicForegroundService.EXTRA_OPUS, useOpus)
            putExtra(MicForegroundService.EXTRA_USB, usb)
            putExtra(MicForegroundService.EXTRA_KEY, pairingKey)
        }
        ContextCompat.startForegroundService(context, intent)
        muted = false
        streaming = true
        val lock = if (pairingKey != null) " · 🔒" else ""
        status = "Connected to $host$lock"
    }

    fun stopStreaming() {
        context.stopService(Intent(context, MicForegroundService::class.java))
        muted = false
        streaming = false
        status = null
    }

    fun applyPairingLink(value: String) {
        pairingLink = value
        val parsed = parsePairing(value)
        if (parsed != null) {
            host = parsed.host
            port = parsed.port.toString()
            pairingKey = parsed.key
        } else if (value.isBlank()) {
            pairingKey = null
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showScanner = true else status = "Camera permission denied"
    }

    fun openScanner() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showScanner = true else cameraLauncher.launch(Manifest.permission.CAMERA)
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

    // Auto-connect once on open if enabled and we already have what we need.
    LaunchedEffect(Unit) {
        if (autoConnect && !AudioEngine.streamer.isRunning &&
            hasMicPermission && host.isNotBlank()
        ) {
            beginStreaming()
        }
    }

    LaunchedEffect(streaming) {
        var sawRunning = false
        while (streaming) {
            val running = AudioEngine.streamer.isRunning
            if (running) sawRunning = true
            level = AudioEngine.streamer.peakLevel
            if (running) {
                if (AudioEngine.streamer.reconnecting) status = "Reconnecting…"
                else if (status == "Reconnecting…") status = "Connected"
            }
            if (sawRunning && !running) {
                streaming = false
                status = AudioEngine.lastError?.let { "Error: $it" }
            }
            delay(80)
        }
        level = 0f
    }

    if (showScanner) {
        QrScanner(
            onResult = { value ->
                applyPairingLink(value)
                showScanner = false
            },
            onClose = { showScanner = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "microhone",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "your phone mic",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                )
            }
            StatusPill(streaming)
        }

        // Connect
        SectionCard {
            Text(
                text = "Connect to your PC",
                style = MaterialTheme.typography.titleSmall,
            )
            FilledTonalButton(
                onClick = { openScanner() },
                enabled = !streaming,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (pairingKey != null) "🔒 Paired — rescan" else "Scan pairing QR")
            }
            OutlinedTextField(
                value = pairingLink,
                onValueChange = { applyPairingLink(it) },
                label = { Text("…or paste the pairing link") },
                singleLine = true,
                enabled = !streaming,
                modifier = Modifier.fillMaxWidth(),
            )

            if (devices.isNotEmpty()) {
                Text(
                    text = "On your network",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate500,
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
                        Text("${device.name} · ${device.host}")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("PC address") },
                    singleLine = true,
                    enabled = !streaming,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !streaming,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Control
        SectionCard {
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )
            if (streaming) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            muted = !muted
                            AudioEngine.streamer.muted = muted
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text(if (muted) "Unmute" else "Mute")
                    }
                    Button(
                        onClick = { stopStreaming() },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text("Stop")
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (hasMicPermission) beginStreaming() else requestAndStart()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Start")
                }
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Options
        SectionCard {
            Text(
                text = "Options",
                style = MaterialTheme.typography.labelMedium,
                color = Slate500,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Opus codec")
                Switch(checked = useOpus, onCheckedChange = { useOpus = it }, enabled = !streaming)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-connect on open")
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it },
                    enabled = !streaming,
                )
            }
        }

        // Sound tuning (live)
        SectionCard {
            Text(
                text = "Sound",
                style = MaterialTheme.typography.labelMedium,
                color = Slate500,
            )
            Text("Gain  ${String.format(java.util.Locale.US, "%.1f", gain)}×")
            Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.5f..3f)
            Text(
                text = "Noise gate  " +
                    if (gate == 0f) "off" else String.format(java.util.Locale.US, "%.2f", gate),
            )
            Slider(value = gate, onValueChange = { gate = it }, valueRange = 0f..0.1f)
        }
    }
}
