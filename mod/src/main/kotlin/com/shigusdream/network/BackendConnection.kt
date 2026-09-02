package com.shigusdream.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shigusdream.ShigusDream
import com.shigusdream.auth.AuthManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Управляет подключением к backend: состояния, экспоненциальный backoff
 * (1s → 2s → 4s → 8s → 16s → 30s), ping/pong, presence-снапшот и диспетчеризация
 * входящих action.execute.
 */
class BackendConnection(
    private val wsUrl: String,
    private val auth: AuthManager,
    private val pingIntervalSeconds: Int,
) {
    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, ONLINE }

    interface Handler {
        /** WebSocket открыт — самое время аутентифицироваться. Вызывается на WS-потоке. */
        fun onConnected()

        /** Вызывается на сетевом/WS-потоке. */
        fun onActionExecute(requestId: String, action: String, args: JsonObject, commandId: String?)
        fun onPresence(users: List<PresenceUser>)
        fun onAuthSuccess(username: String?, refreshToken: String?, accessToken: String?)
        fun onAuthError(code: String, message: String)
        fun onActionResult(requestId: String, action: String, status: String, error: String?)
        fun onStateChange(state: State)

        /** Запланировано переподключение через указанное число секунд. */
        fun onReconnectIn(seconds: Long) {}
    }

    data class PresenceUser(val username: String, val online: Boolean, val role: String)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "shigusdream-connection").apply { isDaemon = true }
    }
    private var client: WsClient? = null
    private val state = java.util.concurrent.atomic.AtomicReference(State.DISCONNECTED)
    private val reconnectAttempt = AtomicLong(0)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    @Volatile
    private var manualClose = false
    @Volatile
    var handler: Handler? = null

    val currentState: State get() = state.get()
    val isOnline: Boolean get() = state.get() == State.ONLINE

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun connect() {
        manualClose = false
        if (state.get() != State.DISCONNECTED) return
        setState(State.CONNECTING)
        val ws = WsClient(wsUrl, object : WsClient.Listener {
            override fun onOpen() {
                setState(State.AUTHENTICATING)
                handler?.onConnected()
            }

            override fun onText(message: String) = handleIncoming(message)
            override fun onClosed(code: Int, reason: String) {
                ShigusDream.LOGGER.info("ws закрыт: code={} reason={}", code, reason)
                onLostConnection()
            }

            override fun onError(t: Throwable?) {
                ShigusDream.LOGGER.info("ws ошибка: {}", t?.message ?: t?.javaClass?.simpleName ?: "unknown")
                onLostConnection()
            }
        })
        client = ws
        ws.connect()
    }

    @Synchronized
    fun disconnect() {
        manualClose = true
        cancelTasks()
        client?.close()
        client = null
        setState(State.DISCONNECTED)
    }

    /** Переподключиться принудительно (кнопка J / смена настроек). */
    @Synchronized
    fun reconnect() {
        manualClose = true
        cancelTasks()
        client?.close()
        client = null
        setState(State.DISCONNECTED)
        reconnectAttempt.set(0)
        connect()
    }

    private fun onLostConnection() {
        if (manualClose) return
        pingTask?.cancel(false)
        setState(State.CONNECTING)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempt.getAndIncrement()
        val delay = when (attempt) {
            0L -> 1L
            1L -> 2L
            2L -> 4L
            3L -> 8L
            4L -> 16L
            else -> 30L
        }
        ShigusDream.LOGGER.info("Переподключение через {} с (попытка {})", delay, attempt + 1)
        handler?.onReconnectIn(delay)
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    private fun cancelTasks() {
        reconnectTask?.cancel(false)
        pingTask?.cancel(false)
        reconnectTask = null
        pingTask = null
    }

    // ------------------------------------------------------------------ incoming

    private fun handleIncoming(text: String) {
        val env = Envelope.fromJson(text) ?: run {
            // protocol mismatch / malformed — сервер закроет соединение при несовпадении версии сам.
            return
        }
        when (env.messageType) {
            Msg.AUTH_PENDING -> {
                setState(State.AUTHENTICATING)
                handler?.onAuthError("pending", env.payload.get("message")?.asString ?: "Ожидание подтверждения кода")
            }

            Msg.AUTH_SUCCESS -> {
                reconnectAttempt.set(0)
                setState(State.ONLINE)
                val refreshToken = env.payload.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
                val accessToken = env.payload.get("access_token")?.takeIf { !it.isJsonNull }?.asString
                val username = env.payload.getAsJsonObject("user")?.get("username")?.asString
                handler?.onAuthSuccess(username, refreshToken, accessToken)
                startPing()
            }

            Msg.AUTH_ERROR -> {
                val code = env.payload.get("code")?.asString ?: "invalid_token"
                val message = env.payload.get("message")?.asString ?: ""
                ShigusDream.LOGGER.warn("auth error: {} {}", code, message)
                handler?.onAuthError(code, message)
                // Невосстановимые ошибки аутентификации — стоп с ручным переподключением.
                if (code == "invalid_token" || code == "unknown_link_code" || code == "expired_link_code" || code == "uuid_already_linked") {
                    manualClose = true
                    setState(State.DISCONNECTED)
                    client?.close()
                }
            }

            Msg.PRESENCE_LIST -> {
                val users = env.payload.getAsJsonArray("users")?.map { el ->
                    val u = el.asJsonObject
                    PresenceUser(
                        username = u.get("username").asString,
                        online = u.get("online").asBoolean,
                        role = u.get("role")?.asString ?: "user",
                    )
                } ?: emptyList()
                handler?.onPresence(users)
            }

            Msg.ACTION_EXECUTE -> {
                val action = env.payload.get("action")?.asString ?: return
                val commandId = env.payload.get("command_id")?.takeIf { it.isJsonPrimitive }?.asString
                val args = env.payload.getAsJsonObject("args") ?: JsonObject()
                handler?.onActionExecute(env.requestId ?: return, action, args, commandId)
            }

            Msg.ACTION_RESULT -> {
                val action = env.payload.get("action")?.asString ?: ""
                val status = env.payload.get("status")?.asString ?: ""
                val error = env.payload.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                handler?.onActionResult(env.requestId ?: "", action, status, error)
            }

            Msg.ACTION_ERROR -> {
                val code = env.payload.get("code")?.asString ?: "error"
                val message = env.payload.get("message")?.asString ?: ""
                handler?.onActionResult(env.requestId ?: "", "", "failed", "[$code] $message")
            }

            Msg.PONG -> {} // только маркер живости
        }
    }

    // ------------------------------------------------------------------ outgoing

    private fun startPing() {
        pingTask?.cancel(false)
        pingTask = scheduler.scheduleAtFixedRate({
            if (state.get() == State.ONLINE) {
                val payload = JsonObject().apply { addProperty("timestamp", System.currentTimeMillis()) }
                client?.send(Envelope(Msg.PING, payload = payload).toJson())
            }
        }, pingIntervalSeconds.toLong(), pingIntervalSeconds.toLong(), TimeUnit.SECONDS)
    }

    fun sendAuth() {
        val payload = JsonObject()
        auth.tokens.refreshToken?.takeIf { it.isNotBlank() }?.let {
            payload.addProperty("token", it)
        }
        client?.send(Envelope(Msg.AUTH, payload = payload).toJson())
    }

    fun sendAuthWithLinkCode(code: String) {
        val payload = JsonObject().apply { addProperty("link_code", code) }
        client?.send(Envelope(Msg.AUTH, payload = payload).toJson())
    }

    /** Запрос полного снапшота presence (только для сессий с правами). */
    fun requestPresence() {
        client?.send(Envelope(Msg.PRESENCE_LIST).toJson())
    }

    fun sendExecute(target: String, action: String, args: JsonObject): String {
        val requestId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("target", target)
            addProperty("action", action)
            add("args", args)
            addProperty("mode", "immediate")
        }
        client?.send(Envelope(Msg.ACTION_EXECUTE, requestId = requestId, payload = payload).toJson())
        return requestId
    }

    fun sendResult(requestId: String, action: String, executed: Boolean, error: String?) {
        val payload = JsonObject().apply {
            addProperty("action", action)
            addProperty("status", if (executed) "executed" else "failed")
            error?.let { addProperty("error", it) }
        }
        client?.send(Envelope(Msg.ACTION_RESULT, requestId = requestId, payload = payload).toJson())
    }

    private fun setState(newState: State) {
        val old = state.getAndSet(newState)
        if (old != newState) {
            ShigusDream.LOGGER.info("connection: {} -> {}", old, newState)
            handler?.onStateChange(newState)
        }
    }

    fun shutdown() {
        disconnect()
        scheduler.shutdownNow()
    }
}
