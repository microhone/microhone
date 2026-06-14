package com.microhone.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Faz 1 PoC capture + sender.
 *
 * Records raw 48 kHz mono 16-bit PCM from the mic and streams it to the desktop
 * receiver over UDP, framed as `[ seq:u32 BE ][ timestamp:u32 BE ][ pcm ]`
 * (see `packages/protocol/PROTOCOL.md`). No codec, no pairing — this only
 * proves the pipeline. AAudio/Oboe and a foreground service come later.
 */
class AudioStreamer {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_MS = 10
        const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS / 1000 // 480
        private const val BYTES_PER_SAMPLE = 2
        private const val HEADER_LEN = 8
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
    fun start(host: String, port: Int, onError: (String) -> Unit) {
        if (isRunning) return
        isRunning = true
        worker = thread(name = "microhone-mic-udp") {
            var record: AudioRecord? = null
            var socket: DatagramSocket? = null
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

                val address = InetAddress.getByName(host)
                socket = DatagramSocket()
                record.startRecording()

                val frameBytes = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
                val pcm = ByteArray(frameBytes)
                val packet = ByteArray(HEADER_LEN + frameBytes)
                var seq = 0
                var timestamp = 0

                while (isRunning) {
                    var read = 0
                    while (read < frameBytes && isRunning) {
                        val r = record.read(pcm, read, frameBytes - read)
                        if (r <= 0) break
                        read += r
                    }
                    if (read <= 0) continue

                    writeU32Be(packet, 0, seq)
                    writeU32Be(packet, 4, timestamp)
                    System.arraycopy(pcm, 0, packet, HEADER_LEN, read)
                    socket.send(DatagramPacket(packet, HEADER_LEN + read, address, port))

                    peakLevel = framePeak(pcm, read)
                    seq++
                    timestamp += SAMPLES_PER_FRAME
                }
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            } finally {
                runCatching { record?.stop() }
                record?.release()
                socket?.close()
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

    /** Peak absolute amplitude of a little-endian PCM frame, normalized to 0f..1f. */
    private fun framePeak(pcm: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() // sign-extends
            val sample = (hi shl 8) or lo
            val a = abs(sample)
            if (a > peak) peak = a
            i += 2
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
