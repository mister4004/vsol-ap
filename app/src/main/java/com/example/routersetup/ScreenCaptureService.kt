package com.example.routersetup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private const val TAG = "ScreenCaptureService"
    private const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        val resultCode = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            Log.d(TAG, "MediaProjection obtained successfully")
            startForegroundServiceWithNotification()
            // Здесь начните захват экрана или инициализируйте WebRTC
        } else {
            Log.e(TAG, "MediaProjection not obtained")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Service")
            .setContentText("Screen capturing is running...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaProjection?.stop()
        mediaProjection = null
    }
}
