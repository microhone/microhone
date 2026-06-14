package com.microhone.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.microhone.app.MainActivity
import com.microhone.app.R
import com.microhone.app.audio.AudioEngine

/**
 * Keeps the mic capture running while the app is backgrounded or the screen is
 * off, with an ongoing notification (Android requires a foreground service of
 * type `microphone` for this).
 */
class MicForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.microhone.app.action.START"
        const val ACTION_STOP = "com.microhone.app.action.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_OPUS = "opus"
        const val EXTRA_USB = "usb"
        const val EXTRA_KEY = "key"
        private const val CHANNEL_ID = "microhone_capture"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI before starting
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_HOST)
        if (host.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra(EXTRA_PORT, 47801)
        val opus = intent.getBooleanExtra(EXTRA_OPUS, true)
        val usb = intent.getBooleanExtra(EXTRA_USB, false)
        val key = intent.getByteArrayExtra(EXTRA_KEY)

        startAsForeground()
        AudioEngine.lastError = null
        AudioEngine.streamer.start(host, port, opus, usb, key) { message ->
            AudioEngine.lastError = message
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AudioEngine.streamer.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Microphone streaming",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MicForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("microhone")
            .setContentText("Streaming your mic to your PC")
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
