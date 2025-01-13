package com.example.routersetup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.routersetup.ui.theme.RouterSetupTheme
import android.app.NotificationChannel
import android.app.NotificationManager

class RouterSetupActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
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
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                // Start Foreground Service
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                serviceIntent.putExtra("code", result.resultCode)
                serviceIntent.putExtra("data", data)
                startForegroundService(serviceIntent)
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

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}
