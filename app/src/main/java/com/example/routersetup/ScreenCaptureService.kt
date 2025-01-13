package com.example.routersetup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

class ScreenCaptureService : Service() {
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var socket: Socket
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called")
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setupSocket()
        initializePeerConnection()
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
            startScreenCapture()
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupSocket() {
        try {
            socket = IO.socket("https://ping-speed.ddns.net")
            socket.connect()
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to server!")
                socket.emit("readyForOffer")
            }
            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Disconnected from server!")
                peerConnection?.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initializePeerConnection() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:176.126.70.215:3478")
                .setUsername("test")
                .setPassword("password123")
                .createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            }
        )
    }

    private fun startScreenCapture() {
        Log.d(TAG, "Screen capturing started...")
        // Screen capture logic here
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        socket.disconnect()
        coroutineScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
