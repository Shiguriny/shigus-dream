package com.shigusdream.backend.websocket

import com.shigusdream.backend.protocol.Envelope
import com.shigusdream.backend.protocol.ProtocolJson
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.util.UUID

/**
 * Состояние одного WebSocket-соединения с клиентом.
 * Аутентификация обязательна первым сообщением (auth).
 */
class ClientSession(val raw: WebSocketServerSession) {
    var authenticated: Boolean = false
    var user: com.shigusdream.backend.repository.User? = null
    var awaitingLinkCode: String? = null

    val userId: UUID? get() = user?.id

    suspend fun send(envelope: Envelope) {
        try {
            raw.outgoing.send(Frame.Text(ProtocolJson.encodeToString(Envelope.serializer(), envelope)))
        } catch (_: ClosedSendChannelException) {
            // Соединение уже закрыто — игнорируем.
        }
    }

    suspend fun close(reason: CloseReason) {
        try {
            raw.close(reason)
        } catch (_: Exception) {
        }
    }
}
