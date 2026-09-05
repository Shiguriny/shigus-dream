package com.shigusdream.network

import com.shigusdream.ShigusDream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Тонкая обёртка над java.net.http.WebSocket: сборка текстовых фрагментов в кадр,
 * отправка текста, слушатель закрытия. Работает вне MC-потока.
 */
class WsClient(
    private val url: String,
    private val listener: Listener,
) {
    interface Listener {
        /** WebSocket установлен (HTTP 101). */
        fun onOpen()
        fun onText(message: String)
        fun onBinary(data: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onError(t: Throwable?)
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val webSocket = AtomicReference<WebSocket?>(null)
    private val fragmentBuffer = StringBuilder()
    @Volatile
    private var closedByUs = false

    val isOpen: Boolean get() = webSocket.get() != null

    fun connect() {
        closedByUs = false
        fragmentBuffer.setLength(0)
        val future = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(url), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    this@WsClient.webSocket.set(webSocket)
                    listener.onOpen()
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    synchronized(fragmentBuffer) { fragmentBuffer.append(data) }
                    if (last) {
                        val frame = synchronized(fragmentBuffer) {
                            val s = fragmentBuffer.toString()
                            fragmentBuffer.setLength(0)
                            s
                        }
                        listener.onText(frame)
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(Unit)
                }

                override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*> {
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    listener.onBinary(bytes)
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(Unit)
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    this@WsClient.webSocket.set(null)
                    listener.onClosed(statusCode, reason)
                    return CompletableFuture.completedFuture(Unit)
                }

                    override fun onError(webSocket: WebSocket, error: Throwable) {
                        this@WsClient.webSocket.set(null)
                        listener.onError(error)
                    }
            })
        // Сторожевой таймер: если рукопожатие не завершилось за 30 с — считаем соединение потерянным.
        future.orTimeout(30, TimeUnit.SECONDS).whenComplete { _, error ->
            if (error != null) {
                listener.onError(error)
            }
        }
    }

    fun send(text: String): Boolean {
        val ws = webSocket.get() ?: return false
        return try {
            ws.sendText(text, true)
            true
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("ws send failed", e)
            false
        }
    }

    fun sendBinary(bytes: ByteArray): Boolean {
        val ws = webSocket.get() ?: return false
        return try {
            ws.sendBinary(java.nio.ByteBuffer.wrap(bytes), true)
            true
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("ws binary send failed", e)
            false
        }
    }

    fun close() {
        closedByUs = true
        webSocket.getAndSet(null)?.let { ws ->
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get()
            } catch (_: Exception) {
            }
        }
    }
}
