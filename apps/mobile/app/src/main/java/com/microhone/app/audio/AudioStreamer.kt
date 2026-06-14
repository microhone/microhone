package com.microhone.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Faz 1–3 PoC capture + sender.
 *
 * Records 48 kHz mono 16-bit PCM from the mic and streams it to the desktop
 * receiver framed as `[ seq:u32 BE ][ timestamp:u32 BE ][ payload ]`
 * (see `packages/protocol/PROTOCOL.md`). The payload is Opus by default (~10 ms
 * frames, with the desktop side doing PLC) or raw little-endian PCM when Opus is
 * turned off.
 *
 * Two transports: UDP over WiFi, or TCP for USB mode (connect to 127.0.0.1,
 * which `adb reverse` on the PC tunnels back to the desktop; each frame is
 * length-prefixed with a u16). AAudio/Oboe and a foreground service come later.
 */
class AudioStreamer {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_MS = 10
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 480
        private const val BYTES_PER_SAMPLE = 2
        private const val HEADER_LEN = 8
        private const val NONCE_LEN = 12
        private const val GCM_TAG_LEN = 16
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Most recent frame peak amplitude, 0f..1f. Polled by the UI. */
    @Volatile
    var peakLevel: Float = 0f
        private set

    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(
        host: String,
        port: Int,
        useOpus: Boolean,
        usb: Boolean,
        key: ByteArray?,
        onError: (String) -> Unit,
    ) {
        if (isRunning) return
        isRunning = true
        worker = thread(name = "microhone-mic-net") {
            var record: AudioRecord? = null
            var udp: DatagramSocket? = null
            var tcp: Socket? = null
            var tcpOut: OutputStream? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                require(minBuffer > 0) { "AudioRecord is not available on this device" }

                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * 4),
                )
                check(record.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord failed to initialize"
                }

                val encoder = if (useOpus) {
                    OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO)
                } else {
                    null
                }

                var address: InetAddress? = null
                if (usb) {
                    tcp = Socket().apply {
                        tcpNoDelay = true
                        connect(InetSocketAddress(host, port), 3000)
                    }
                    tcpOut = tcp.getOutputStream()
                } else {
                    address = InetAddress.getByName(host)
                    udp = DatagramSocket()
                }
                record.startRecording()

                // Optional AES-256-GCM: only a paired sender (with the key) is
                // accepted and the audio is encrypted on the wire.
                val keySpec = key?.let { SecretKeySpec(it, "AES") }
                val cipher = keySpec?.let { Cipher.getInstance("AES/GCM/NoPadding") }
                val random = cipher?.let { SecureRandom() }
                val nonce = ByteArray(NONCE_LEN)

                val samples = ShortArray(SAMPLES_PER_FRAME)
                val pcmBytes = ByteArray(SAMPLES_PER_FRAME * BYTES_PER_SAMPLE)
                val opusBytes = ByteArray(1275) // max Opus packet for one mono frame
                // Reused header+payload buffer (Opus is the larger of the two).
                val packet = ByteArray(HEADER_LEN + maxOf(pcmBytes.size, opusBytes.size))
                // nonce + ciphertext + GCM tag for the encrypted path.
                val secureBuf = ByteArray(NONCE_LEN + packet.size + GCM_TAG_LEN)
                val lenPrefix = ByteArray(2) // u16 frame length for the TCP stream
                var seq = 0
                var timestamp = 0

                while (isRunning) {
                    var read = 0
                    while (read < SAMPLES_PER_FRAME && isRunning) {
                        val r = record.read(samples, read, SAMPLES_PER_FRAME - read)
                        if (r <= 0) break
                        read += r
                    }
                    if (read < SAMPLES_PER_FRAME) continue

                    peakLevel = framePeak(samples, read)

                    val payload: ByteArray
                    val payloadLen: Int
                    if (encoder != null) {
                        val len = encoder.encode(samples, 0, SAMPLES_PER_FRAME, opusBytes, 0, opusBytes.size)
                        if (len <= 0) continue
                        payload = opusBytes
                        payloadLen = len
                    } else {
                        var b = 0
                        for (i in 0 until read) {
                            pcmBytes[b++] = (samples[i].toInt() and 0xFF).toByte()
                            pcmBytes[b++] = (samples[i].toInt() shr 8).toByte()
                        }
                        payload = pcmBytes
                        payloadLen = b
                    }

                    writeU32Be(packet, 0, seq)
                    writeU32Be(packet, 4, timestamp)
                    System.arraycopy(payload, 0, packet, HEADER_LEN, payloadLen)
                    val frameLen = HEADER_LEN + payloadLen

                    // Build the wire bytes: encrypted (nonce + ciphertext) or plain.
                    val wire: ByteArray
                    val wireLen: Int
                    if (cipher != null) {
                        random!!.nextBytes(nonce)
                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
                        System.arraycopy(nonce, 0, secureBuf, 0, NONCE_LEN)
                        val ctLen = cipher.doFinal(packet, 0, frameLen, secureBuf, NONCE_LEN)
                        wire = secureBuf
                        wireLen = NONCE_LEN + ctLen
                    } else {
                        wire = packet
                        wireLen = frameLen
                    }

                    val out = tcpOut
                    if (out != null) {
                        lenPrefix[0] = (wireLen ushr 8).toByte()
                        lenPrefix[1] = wireLen.toByte()
                        out.write(lenPrefix)
                        out.write(wire, 0, wireLen)
                        out.flush()
                    } else {
                        udp!!.send(DatagramPacket(wire, wireLen, address, port))
                    }

                    seq++
                    timestamp += SAMPLES_PER_FRAME
                }
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            } finally {
                runCatching { record?.stop() }
                record?.release()
                udp?.close()
                runCatching { tcpOut?.close() }
                runCatching { tcp?.close() }
                peakLevel = 0f
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        worker?.join(500)
        worker = null
        peakLevel = 0f
    }

    /** Peak absolute amplitude of a PCM frame, normalized to 0f..1f. */
    private fun framePeak(samples: ShortArray, length: Int): Float {
        var peak = 0
        for (i in 0 until length) {
            val a = abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        return peak / 32768f
    }

    private fun writeU32Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
