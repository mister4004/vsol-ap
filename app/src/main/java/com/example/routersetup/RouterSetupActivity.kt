package com.example.routersetup

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import com.example.routersetup.ui.theme.RouterSetupTheme
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

class RouterSetupActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var socket: Socket
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var adminSocketId: String? = null

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create Notification Channel for Foreground Service
        val channel = NotificationChannel(
            "SCREEN_CAPTURE_CHANNEL",
            "Screen Sharing",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data

                // Start Foreground Service
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                startForegroundService(serviceIntent)

                mediaProjection = projectionManager.getMediaProjection(result.resultCode, data!!)
                setupSocket()
                initializePeerConnection()
            } else {
                println("Permission denied for screen capture")
            }
        }

        setContent {
            RouterSetupTheme {
                MainScreenUI(
                    onStartShare = {
                        val captureIntent = projectionManager.createScreenCaptureIntent()
                        screenCaptureLauncher.launch(captureIntent)
                    }
                )
            }
        }
    }

    @Composable
    fun MainScreenUI(onStartShare: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onStartShare() }) {
                Text("Start WebSocket + Share")
            }
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.javaScriptEnabled = true
                            loadUrl("https://ping-speed.ddns.net")
                        }
                    }
                )
            }
        }
    }

    private fun setupSocket() {
        try {
            socket = IO.socket("https://ping-speed.ddns.net")
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
                        val type = signalObj.getString("type")
                        val fromId = data.optString("from", "")

                        if (!fromId.isNullOrEmpty()) {
                            adminSocketId = fromId
                        }

                        when (type) {
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
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
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
                            put("targetId", adminSocketId)
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
                override fun onTrack(transceiver: RtpTransceiver?) {}
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
                    put("targetId", adminSocketId)
                }
                socket.emit("webrtcSignal", payload)
            }

            override fun onCreateFailure(error: String?) {
                println("Failed to create answer: $error")
            }
        }, constraints)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}

abstract class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
