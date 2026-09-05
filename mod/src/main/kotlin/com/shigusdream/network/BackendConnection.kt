package com.shigusdream.network

import com.google.gson.JsonObject
import com.shigusdream.ShigusDream
import com.shigusdream.auth.AuthManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Управляет подключением к backend: состояния, health-проба (будит «спящий» бесплатный Render),
 * экспоненциальный backoff (1s → 2s → 4s → 8s → 16s → 30s), ping/pong, presence-снапшот
 * и диспетчеризация входящих action.execute.
 */
class BackendConnection(
    wsUrl: String,
    private val auth: AuthManager,
    private val pingIntervalSeconds: Int,
) {
    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, ONLINE }

    /** Адрес WebSocket (/ws). Может меняться из экрана настроек. */
    @Volatile
    var wsUrl: String = wsUrl
        private set

    /** Обновить адрес; применяется при следующем подключении. */
    fun updateUrl(newWsUrl: String) {
        wsUrl = newWsUrl
    }

    interface Handler {
        /** WebSocket открыт — самое время аутентифицироваться. Вызывается на WS-потоке. */
        fun onConnected()

        /** Вызывается на сетевом/WS-потоке. */
        fun onActionExecute(requestId: String, action: String, args: JsonObject, commandId: String?)
        fun onPresence(users: List<PresenceUser>)
        fun onAuthSuccess(username: String?, refreshToken: String?, accessToken: String?, role: String?)
        fun onAuthError(code: String, message: String)
        fun onActionResult(requestId: String, action: String, status: String, error: String?)
        fun onStateChange(state: State)

        /** Роль текущего аккаунта изменена на лету (владельцем). */
        fun onRoleUpdate(newRole: String) {}

        /** Входящий голосовой кадр (PCM) от другого игрока. */
        fun onVoiceData(pcm: ByteArray) {}

        /** Человекочитаемое сообщение о состоянии связи (для чата). */
        fun onMessage(line: String) {}

        /** Запланировано переподключение через указанное число секунд. */
        fun onReconnectIn(seconds: Long) {}
    }

    data class PresenceUser(val username: String, val online: Boolean, val role: String)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "shigusdream-connection").apply { isDaemon = true }
    }
    private val probeClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private var client: WsClient? = null
    private val state = AtomicReference(State.DISCONNECTED)
    private val reconnectAttempt = AtomicLong(0)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null

    /** Инкрементится при каждой попытке connect(); устаревшие колбэки проб игнорируются. */
    private val connectGeneration = AtomicLong(0)

    @Volatile
    private var manualClose = false

    @Volatile
    var handler: Handler? = null

    val currentState: State get() = state.get()
    val isOnline: Boolean get() = state.get() == State.ONLINE

    /** wss://host/ws -> https://host/health — для пробуждения «спящего» бесплатного Render. */
    private val healthUrl: String
        get() = wsUrl.removeSuffix("/ws").let {
            (if (it.startsWith("wss://")) "https://" + it.removePrefix("wss://") else "http://" + it.removePrefix("ws://")) + "/health"
        }

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun connect() {
        manualClose = false
        if (state.get() != State.DISCONNECTED) return
        setState(State.CONNECTING)
        val generation = connectGeneration.incrementAndGet()

        handler?.onMessage("Проверяем backend (бесплатный Render может просыпаться до минуты)...")

        val request = HttpRequest.newBuilder(URI.create(healthUrl))
            .timeout(Duration.ofSeconds(75))
            .GET()
            .build()
        probeClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .orTimeout(80, TimeUnit.SECONDS)
            .whenComplete { response, error ->
                if (generation != connectGeneration.get() || manualClose || state.get() != State.CONNECTING) {
                    return@whenComplete
                }
                when {
                    error != null -> {
                        ShigusDream.LOGGER.info("health probe failed: {}", error.toString())
                        handler?.onMessage("Backend недоступен (${error.javaClass.simpleName}) — попробуем ещё раз")
                        onLostConnection()
                    }

                    response.statusCode() / 100 != 2 -> {
                        ShigusDream.LOGGER.info("backend ещё просыпается: HTTP {}", response.statusCode())
                        handler?.onMessage("Backend просыпается (HTTP ${response.statusCode()}) — ждём...")
                        onLostConnection()
                    }

                    else -> {
                        ShigusDream.LOGGER.info("backend отвечает, открываем WebSocket")
                        openWebSocket()
                    }
                }
            }
    }

    @Synchronized
    fun disconnect() {
        manualClose = true
        connectGeneration.incrementAndGet()
        cancelTasks()
        client?.close()
        client = null
        setState(State.DISCONNECTED)
    }

    /** Переподключиться принудительно (кнопка J / смена настроек). */
    @Synchronized
    fun reconnect() {
        manualClose = true
        connectGeneration.incrementAndGet()
        cancelTasks()
        client?.close()
        client = null
        setState(State.DISCONNECTED)
        reconnectAttempt.set(0)
        connect()
    }

    private fun openWebSocket() {
        val ws = WsClient(wsUrl, object : WsClient.Listener {
            override fun onOpen() {
                setState(State.AUTHENTICATING)
                handler?.onConnected()
            }

            override fun onText(message: String) = handleIncoming(message)

            override fun onBinary(bytes: ByteArray) {
                if (state.get() == State.ONLINE) {
                    handler?.onVoiceData(bytes)
                }
            }

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

    private fun onLostConnection() {
        if (manualClose) return
        pingTask?.cancel(false)
        setState(State.DISCONNECTED)
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
        val env = Envelope.fromJson(text) ?: return
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
                val user = env.payload.getAsJsonObject("user")
                val username = user?.get("username")?.asString
                val role = user?.get("role")?.asString
                handler?.onAuthSuccess(username, refreshToken, accessToken, role)
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

            Msg.ROLE_UPDATE -> {
                val newRole = env.payload.get("role")?.asString ?: return
                handler?.onRoleUpdate(newRole)
            }
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

    /** Голосовой кадр (PCM) в бинарном WS-кадре. */
    fun sendVoiceBinary(pcm: ByteArray) {
        client?.sendBinary(pcm)
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
