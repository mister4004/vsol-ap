package com.example.routersetup

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.routersetup.ui.theme.RouterSetupTheme

class RouterSetupActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Создание канала уведомлений для Foreground Service
        val channel = NotificationChannel(
            "SCREEN_CAPTURE_CHANNEL",
            "Screen Sharing",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        // Инициализация MediaProjectionManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Настройка ActivityResultLauncher для запроса захвата экрана
        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                // Запуск Foreground Service для захвата экрана
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("code", result.resultCode)
                    putExtra("data", data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                println("Permission denied for screen capture")
            }
        }

        // Установка содержимого через Jetpack Compose
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
        val routerIp = remember { mutableStateOf("192.168.1.1") }
        val loadUrlTrigger = remember { mutableStateOf("http://192.168.1.1") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Поле ввода IP-адреса роутера
            OutlinedTextField(
                value = routerIp.value,
                onValueChange = { routerIp.value = it },
                label = { Text("Router IP Address") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Кнопка для загрузки роутера
            Button(onClick = { loadUrlTrigger.value = "http://${routerIp.value}" }) {
                Text("Load Router")
            }

            Spacer(Modifier.height(16.dp))

            // Кнопка для начала захвата экрана
            Button(onClick = { onStartShare() }) {
                Text("Start Screen Sharing")
            }

            Spacer(Modifier.height(16.dp))

            // WebView для отображения роутера
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.javaScriptEnabled = true
                            loadUrl(loadUrlTrigger.value)
                        }
                    },
                    update = { webView ->
                        webView.loadUrl(loadUrlTrigger.value)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Остановка Foreground Service при уничтожении Activity
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}
