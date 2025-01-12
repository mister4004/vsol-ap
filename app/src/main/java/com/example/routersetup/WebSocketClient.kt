package com.example.routersetup

import okhttp3.*

class WebSocketClient {
    private val client = OkHttpClient()
    private lateinit var webSocket: WebSocket

    private val request = Request.Builder()
        .url("wss://ping-speed.ddns.net") // Ваш WebSocket-адрес
        .build()

    fun startWebSocket() {
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                this@WebSocketClient.webSocket = webSocket
                println("WebSocket connected!")
                webSocket.send("Hello Server!") // Отправка сообщения при подключении
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                println("Received from server: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                println("WebSocket error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                println("WebSocket closed: $reason")
            }
        }

        client.newWebSocket(request, webSocketListener)
    }

    fun sendMessage(message: String) {
        if (::webSocket.isInitialized) {
            webSocket.send(message)
            println("Message sent: $message")
        } else {
            println("WebSocket not connected")
        }
    }
}
