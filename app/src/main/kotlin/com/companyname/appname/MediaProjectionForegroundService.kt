package com.companyname.appname

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MediaProjectionForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        var started = false
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                started = true
            } catch (e: SecurityException) {
                ErrorLog.logException(this, "MEDIA_PROJ_SVC", e)
            }
        }
        if (!started) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (e: Exception) {
                ErrorLog.logException(this, "MEDIA_PROJ_SVC_FALLBACK", e)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_RESULT_CODE) == true && intent.hasExtra(EXTRA_RESULT_DATA)) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val resultData = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
            if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                createMediaProjection(resultCode, resultData)
            }
        }
        if (intent?.action == ACTION_STOP) {
            Companion.mediaProjection?.stop()
            Companion.mediaProjection = null
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
        return START_STICKY
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)!!
            Companion.mediaProjection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Companion.mediaProjection = null
                }
            }, null)
            sendBroadcast(Intent(ACTION_READY))
        } catch (e: Exception) {
            ErrorLog.logException(this, "MEDIA_PROJ_CREATE", e)
            TelegramReporter.sendException("MEDIA_PROJ_CREATE", e)
            sendBroadcast(Intent(ACTION_FAILED))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تسجيل صوت النظام",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مطلوب لتسجيل صوت النظام"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("تسجيل صوت النظام")
            .setContentText("التطبيق يسجل صوت النظام...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "media_projection_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val ACTION_READY = "com.companyname.appname.PROJECTION_READY"
        const val ACTION_FAILED = "com.companyname.appname.PROJECTION_FAILED"
        const val ACTION_STOP = "com.companyname.appname.STOP_RECORDING"
        var mediaProjection: MediaProjection? = null
    }
}
