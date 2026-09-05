package com.shigusdream.backend.websocket

import com.shigusdream.backend.auth.LinkCodeService
import com.shigusdream.backend.auth.PermissionService
import com.shigusdream.backend.auth.TokenService
import com.shigusdream.backend.command.CommandService
import com.shigusdream.backend.protocol.ActionErrorPayload
import com.shigusdream.backend.protocol.ActionExecutePayload
import com.shigusdream.backend.protocol.ActionResultPayload
import com.shigusdream.backend.protocol.AuthErrorPayload
import com.shigusdream.backend.protocol.AuthPendingPayload
import com.shigusdream.backend.protocol.AuthRequest
import com.shigusdream.backend.protocol.AuthSuccessPayload
import com.shigusdream.backend.protocol.Envelope
import com.shigusdream.backend.protocol.ErrorCode
import com.shigusdream.backend.protocol.MessageType
import com.shigusdream.backend.protocol.PresenceEntryDto
import com.shigusdream.backend.protocol.PresenceListPayload
import com.shigusdream.backend.protocol.PROTOCOL_VERSION
import com.shigusdream.backend.protocol.ProtocolJson
import com.shigusdream.backend.protocol.UserDto
import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.User
import com.shigusdream.backend.repository.UserRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/**
 * Маршрутизация WebSocket-сессий: обязательная аутентификация, presence,
 * доставка action.execute целевым клиентам и action.result обратно отправителю.
 */
class WsManager(
    private val users: UserRepository,
    private val linkCodes: LinkCodeRepository,
    private val tokenService: TokenService,
    private val linkCodeService: LinkCodeService,
    private val permissions: PermissionService,
    private val commandService: CommandService,
) {
    private val log = LoggerFactory.getLogger(WsManager::class.java)

    private val sessions = ConcurrentHashMap<UUID, ClientSession>()
    private val awaitingConfirmation = ConcurrentHashMap<String, ClientSession>()

    fun isOnline(userId: UUID): Boolean = sessions.containsKey(userId)

    fun presenceSnapshot(): List<PresenceEntryDto> =
        users.all().map { PresenceEntryDto(id = it.id.toString(), username = it.username, role = it.role, online = isOnline(it.id)) }

    // ------------------------------------------------------------------ lifecycle

    suspend fun handleConnection(session: ClientSession) {
        log.info("ws connected")
        try {
            // Первое сообщение (auth) должно прийти в течение AUTH_TIMEOUT_MS.
            var authenticated = false
            while (true) {
                val frame = kotlinx.coroutines.withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                    session.raw.incoming.receive()
                }
                if (frame == null) break
                if (frame is Frame.Text) handleText(session, frame.readText())
                if (session.authenticated) {
                    authenticated = true
                    break
                }
            }
            if (!authenticated) {
                session.send(authError(ErrorCode.NOT_AUTHENTICATED, "Аутентификация не выполнена за ${AUTH_TIMEOUT_MS / 1000} с"))
                session.close(CloseReason(CloseReason.Codes.NORMAL, "auth_timeout"))
                return
            }
            for (frame in session.raw.incoming) {
                when (frame) {
                    is Frame.Text -> handleText(session, frame.readText())
                    is Frame.Binary -> relayVoice(session, frame.data)
                    else -> {}
                }
            }
        } finally {
            onDisconnected(session)
        }
    }

    /** Голосовой кадр: ретрансляция всем остальным аутентифицированным сессиям (групповая рация). */
    private suspend fun relayVoice(from: ClientSession, data: ByteArray) {
        if (data.size > 64 * 1024) return // защита от мусора
        var receivers = 0
        for ((_, session) in sessions) {
            if (session.raw === from.raw) continue
            session.sendBinary(data)
            receivers++
        }
        if (receivers == 0) log.debug("voice: нет получателей")
    }

    private suspend fun onDisconnected(session: ClientSession) {
        session.awaitingLinkCode?.let { awaitingConfirmation.remove(it) }
        val user = session.user ?: return
        sessions.remove(user.id, session)
        log.info("ws disconnected user={}", user.username)
        broadcastPresence()
    }

    // ------------------------------------------------------------------ messages

    private suspend fun handleText(session: ClientSession, text: String) {
        val env = try {
            ProtocolJson.decodeFromString(Envelope.serializer(), text)
        } catch (_: SerializationException) {
            session.send(errorEnvelope(null, null, ErrorCode.MALFORMED_PACKET, "Некорректное сообщение"))
            return
        }

        if (env.protocolVersion != PROTOCOL_VERSION) {
            session.send(authError(ErrorCode.PROTOCOL_MISMATCH, "Ожидается protocol_version=$PROTOCOL_VERSION"))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "protocol_mismatch"))
            return
        }

        when (env.messageType) {
            MessageType.PING -> session.send(Envelope(messageType = MessageType.PONG, requestId = env.requestId, payload = env.payload))
            MessageType.AUTH -> handleAuth(session, env)
            MessageType.PRESENCE_LIST -> {
                val user = session.user
                if (user != null && permissions.has(user, PermissionService.ADMIN_PERMISSION)) {
                    session.send(Envelope(messageType = MessageType.PRESENCE_LIST, payload = ProtocolJson.encodeToJsonElement(PresenceListPayload.serializer(), PresenceListPayload(presenceSnapshot()))))
                }
            }
            MessageType.PRESENCE_UPDATE -> {} // MVP: presence определяет сервер
            MessageType.ACTION_EXECUTE -> handleExecute(session, env)
            MessageType.ACTION_RESULT -> handleResult(session, env)
            else -> session.send(
                errorEnvelope(
                    env.requestId, null,
                    if (session.authenticated) ErrorCode.MALFORMED_PACKET else ErrorCode.NOT_AUTHENTICATED,
                    "Неизвестный или недопустимый тип сообщения: ${env.messageType}",
                ),
            )
        }
    }

    private suspend fun handleAuth(session: ClientSession, env: Envelope) {
        if (session.authenticated) return

        val request = try {
            ProtocolJson.decodeFromJsonElement(AuthRequest.serializer(), env.payload)
        } catch (_: SerializationException) {
            session.send(authError(ErrorCode.MALFORMED_PACKET, "Некорректный payload auth"))
            return
        }

        // Вариант 1: повторный вход по refresh-токену.
        if (!request.token.isNullOrBlank()) {
            val userId = tokenService.validate(request.token, "refresh")
                ?: run {
                    session.send(authError(ErrorCode.INVALID_TOKEN, "Refresh-токен недействителен"))
                    return
                }
            val user = users.byId(userId)
            if (user == null) {
                session.send(authError(ErrorCode.INVALID_TOKEN, "Пользователь не найден"))
                return
            }
            login(session, user)
            return
        }

        // Вариант 2: первичная привязка по одноразовому коду.
        val code = request.linkCode?.trim()?.uppercase()
        if (code.isNullOrEmpty()) {
            session.send(authError(ErrorCode.INVALID_TOKEN, "Нужен token или link_code"))
            return
        }

        val link = linkCodes.byCode(code)
        if (link == null) {
            session.send(authError(ErrorCode.UNKNOWN_LINK_CODE, "Код не найден"))
            return
        }

        val linkedUserId = link.userId
        when {
            link.status == "confirmed" && linkedUserId != null ->
                // Код уже подтверждён (например, WS-сессия оборвалась до доставки токенов) — повторяем выдачу.
                users.byId(linkedUserId)?.let { login(session, it) }
                    ?: session.send(authError(ErrorCode.UNKNOWN_LINK_CODE, "Аккаунт привязки не найден"))

            link.isExpired -> {
                link.status = "expired"
                linkCodes.save(link)
                session.send(authError(ErrorCode.EXPIRED_LINK_CODE, "Код истёк, запросите новый в игре"))
            }

            else -> {
                session.awaitingLinkCode = code
                awaitingConfirmation[code] = session
                log.info("auth awaiting confirmation code={} mc_name={}", code, link.mcName)
                session.send(Envelope(messageType = MessageType.AUTH_PENDING, payload = ProtocolJson.encodeToJsonElement(AuthPendingPayload.serializer(), AuthPendingPayload("Введите код $code на странице подтверждения backend"))))
            }
        }
    }

    /** Вызывается из HTTP-обработчика подтверждения кода. */
    suspend fun onLinkConfirmed(code: String, userId: UUID) {
        val session = awaitingConfirmation.remove(code) ?: return
        val user = users.byId(userId) ?: return
        login(session, user)
    }

    /** Push-обновление роли онлайн-пользователю: права применяются без переподключения. */
    suspend fun notifyRoleChange(userId: UUID, newRole: String) {
        val user = users.byId(userId) ?: return
        val session = sessions[userId] ?: return
        val payload = buildJsonObject {
            put("role", newRole)
        }
        session.send(Envelope(messageType = MessageType.ROLE_UPDATE, payload = payload))
        log.info("role update pushed user={} role={}", user.username, newRole)
        broadcastPresence()
    }

    suspend fun login(session: ClientSession, user: User) {
        val existing = sessions.put(user.id, session)
        if (existing != null && existing !== session) {
            existing.send(authError(ErrorCode.SESSION_REPLACED, "Аккаунт подключён с другого клиента"))
            existing.close(CloseReason(CloseReason.Codes.NORMAL, "session_replaced"))
        }

        session.authenticated = true
        session.user = user
        session.awaitingLinkCode = null

        val accessToken = tokenService.issueAccessToken(user)
        val refreshToken = tokenService.issueRefreshToken(user)
        session.send(
            Envelope(
                messageType = MessageType.AUTH_SUCCESS,
                payload = ProtocolJson.encodeToJsonElement(
                    AuthSuccessPayload.serializer(),
                    AuthSuccessPayload(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiresIn = TokenService.ACCESS_TTL.toSeconds(),
                        user = user.toDto(),
                        presence = presenceSnapshot(),
                    ),
                ),
            ),
        )
        log.info("auth success user={} role={} mc_uuid={}", user.username, user.role, user.mcUuid)
        broadcastPresence()
        deliverPending(user.id)
    }

    // ------------------------------------------------------------------ commands

    private suspend fun handleExecute(session: ClientSession, env: Envelope) {
        val sender = session.user
        if (sender == null) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.NOT_AUTHENTICATED, "Сначала выполните auth"))
            return
        }
        if (env.requestId.isNullOrBlank()) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.MALFORMED_PACKET, "action.execute требует request_id"))
            return
        }

        val payload = try {
            ProtocolJson.decodeFromJsonElement(ActionExecutePayload.serializer(), env.payload)
        } catch (_: SerializationException) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.MALFORMED_PACKET, "Некорректный payload action.execute"))
            return
        }

        when (val outcome = commandService.handle(sender, payload, env.requestId)) {
            is com.shigusdream.backend.command.CommandService.HandleOutcome.Rejected -> {
                commandLog(sender.username, payload.target, payload.action, env.requestId, "rejected", outcome.code)
                session.send(
                    errorEnvelope(env.requestId, payload.action, outcome.code, outcome.message),
                )
            }

            is com.shigusdream.backend.command.CommandService.HandleOutcome.Created -> {
                val command = outcome.command
                val targetSession = sessions[outcome.target.id]
                val forwardPayload = ActionExecutePayload(
                    target = outcome.target.username,
                    action = payload.action,
                    args = payload.args,
                    mode = payload.mode,
                    commandId = command.id.toString(),
                )
                when {
                    targetSession != null -> {
                        commandService.markForDelivery(command)
                        targetSession.send(
                            Envelope(requestId = env.requestId, messageType = MessageType.ACTION_EXECUTE, payload = ProtocolJson.encodeToJsonElement(ActionExecutePayload.serializer(), forwardPayload)),
                        )
                        commandLog(sender.username, outcome.target.username, payload.action, env.requestId, "delivered", null)
                    }

                    payload.mode == "queued" -> {
                        // Осталась pending — доставим при подключении цели.
                        session.send(
                            Envelope(
                                requestId = env.requestId,
                                messageType = MessageType.ACTION_RESULT,
                                payload = ProtocolJson.encodeToJsonElement(
                                    ActionResultPayload.serializer(),
                                    ActionResultPayload(action = payload.action, status = "queued", commandId = command.id.toString()),
                                ),
                            ),
                        )
                        commandLog(sender.username, outcome.target.username, payload.action, env.requestId, "queued", null)
                    }

                    else -> {
                        commandService.markFailedOffline(command, ErrorCode.TARGET_OFFLINE)
                        session.send(errorEnvelope(env.requestId, payload.action, ErrorCode.TARGET_OFFLINE, "Цель не в сети"))
                        commandLog(sender.username, outcome.target.username, payload.action, env.requestId, "failed", ErrorCode.TARGET_OFFLINE)
                    }
                }
            }
        }
    }

    private suspend fun handleResult(session: ClientSession, env: Envelope) {
        val user = session.user
        if (user == null) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.NOT_AUTHENTICATED, "Сначала выполните auth"))
            return
        }
        if (env.requestId.isNullOrBlank()) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.MALFORMED_PACKET, "action.result требует request_id"))
            return
        }
        val payload = try {
            ProtocolJson.decodeFromJsonElement(ActionResultPayload.serializer(), env.payload)
        } catch (_: SerializationException) {
            session.send(errorEnvelope(env.requestId, null, ErrorCode.MALFORMED_PACKET, "Некорректный payload action.result"))
            return
        }

        val command = commandService.onResult(env.requestId, payload.status, payload.error)
        if (command == null || command.targetId != user.id) {
            log.warn("action.result rejected user={} request_id={}", user.username, env.requestId)
            return
        }

        val senderSession = sessions[command.senderId]
        senderSession?.send(
            Envelope(
                requestId = env.requestId,
                messageType = MessageType.ACTION_RESULT,
                payload = ProtocolJson.encodeToJsonElement(
                    ActionResultPayload.serializer(),
                    ActionResultPayload(action = payload.action, status = payload.status, error = payload.error, commandId = command.id.toString()),
                ),
            ),
        )
        commandLog(senderName(command.senderId), user.username, payload.action, env.requestId, payload.status, payload.error)
    }

    // ------------------------------------------------------------------ helpers

    suspend fun deliverPending(userId: UUID) {
        for (command in commandService.pendingForTarget(userId)) {
            if (commandService.markForDelivery(command)) {
                sessions[userId]?.send(
                    Envelope(
                        requestId = command.requestId,
                        messageType = MessageType.ACTION_EXECUTE,
                        payload = ProtocolJson.encodeToJsonElement(
                            ActionExecutePayload.serializer(),
                            ActionExecutePayload(
                                target = users.byId(userId)?.username ?: "",
                                action = command.actionId,
                                args = ProtocolJson.decodeFromString(JsonObject.serializer(), command.payload),
                                mode = command.mode,
                                commandId = command.id.toString(),
                            ),
                        ),
                    ),
                )
                commandLog(senderName(command.senderId), users.byId(userId)?.username ?: "?", command.actionId, command.requestId, "delivered", null)
            }
        }
    }

    private suspend fun broadcastPresence() {
        val snapshot = presenceSnapshot()
        for (session in sessions.values) {
            val user = session.user ?: continue
            if (permissions.has(user, PermissionService.ADMIN_PERMISSION)) {
                session.send(
                    Envelope(
                        messageType = MessageType.PRESENCE_LIST,
                        payload = ProtocolJson.encodeToJsonElement(PresenceListPayload.serializer(), PresenceListPayload(snapshot)),
                    ),
                )
            }
        }
    }

    private fun senderName(senderId: UUID): String = users.byId(senderId)?.username ?: "?"

    private fun commandLog(sender: String, target: String, action: String, requestId: String?, status: String, error: String?) {
        log.info("command sender={} target={} action={} request_id={} status={} error={}", sender, target, action, requestId ?: "-", status, error ?: "-")
    }

    private fun authError(code: String, message: String): Envelope =
        Envelope(messageType = MessageType.AUTH_ERROR, payload = ProtocolJson.encodeToJsonElement(AuthErrorPayload.serializer(), AuthErrorPayload(code, message)))

    private fun errorEnvelope(requestId: String?, action: String?, code: String, message: String): Envelope =
        Envelope(
            requestId = requestId,
            messageType = MessageType.ACTION_ERROR,
            payload = ProtocolJson.encodeToJsonElement(ActionErrorPayload.serializer(), ActionErrorPayload(action, code, message)),
        )

    private fun User.toDto(): UserDto = UserDto(id = id.toString(), username = username, role = role, mcUuid = mcUuid?.toString())

    companion object {
        /** Время на подтверждение кода привязки: игрок может не спешить вводить /link. */
        const val AUTH_TIMEOUT_MS = 120_000L
    }
}
