package com.shigusdream.backend.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

const val PROTOCOL_VERSION = 1

/** Типы сообщений протокола (по ТЗ) + auth.pending как расширение для ожидания подтверждения кода. */
object MessageType {
    const val AUTH = "auth"
    const val AUTH_PENDING = "auth.pending"
    const val AUTH_SUCCESS = "auth.success"
    const val AUTH_ERROR = "auth.error"
    const val PRESENCE_UPDATE = "presence.update"
    const val PRESENCE_LIST = "presence.list"
    const val ROLE_UPDATE = "role.update"
    const val ACTION_EXECUTE = "action.execute"
    const val ACTION_RESULT = "action.result"
    const val ACTION_ERROR = "action.error"
    const val PING = "ping"
    const val PONG = "pong"

    val ALL = setOf(
        AUTH, AUTH_PENDING, AUTH_SUCCESS, AUTH_ERROR, PRESENCE_UPDATE,
        PRESENCE_LIST, ACTION_EXECUTE, ACTION_RESULT, ACTION_ERROR, PING, PONG,
    )
}

/** Коды ошибок. */
object ErrorCode {
    const val NOT_AUTHENTICATED = "not_authenticated"
    const val INVALID_TOKEN = "invalid_token"
    const val EXPIRED_TOKEN = "expired_token"
    const val UNKNOWN_LINK_CODE = "unknown_link_code"
    const val EXPIRED_LINK_CODE = "expired_link_code"
    const val UUID_ALREADY_LINKED = "uuid_already_linked"
    const val SESSION_REPLACED = "session_replaced"
    const val MALFORMED_PACKET = "malformed_packet"
    const val PROTOCOL_MISMATCH = "protocol_mismatch"
    const val NO_PERMISSION = "no_permission"
    const val UNKNOWN_ACTION = "unknown_action"
    const val INVALID_ARGUMENTS = "invalid_arguments"
    const val UNKNOWN_TARGET = "unknown_target"
    const val TARGET_OFFLINE = "target_offline"
    const val DUPLICATE_REQUEST = "duplicate_request"
    const val INTERNAL_ERROR = "internal_error"
}

@Serializable
data class Envelope(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("message_type") val messageType: String,
    @SerialName("request_id") val requestId: String? = null,
    val payload: JsonElement = JsonNull,
)

@Serializable
data class AuthRequest(
    @SerialName("mc_uuid") val mcUuid: String? = null,
    @SerialName("mc_name") val mcName: String? = null,
    @SerialName("link_code") val linkCode: String? = null,
    val token: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val role: String,
    @SerialName("mc_uuid") val mcUuid: String? = null,
)

@Serializable
data class PresenceEntryDto(
    val id: String,
    val username: String,
    val role: String,
    val online: Boolean,
)

@Serializable
data class AuthSuccessPayload(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserDto,
    val presence: List<PresenceEntryDto> = emptyList(),
)

@Serializable
data class AuthPendingPayload(val message: String)

@Serializable
data class AuthErrorPayload(val code: String, val message: String)

@Serializable
data class PresenceListPayload(val users: List<PresenceEntryDto>)

@Serializable
data class PresenceUpdatePayload(
    val id: String,
    val username: String,
    val online: Boolean,
)

@Serializable
data class ActionExecutePayload(
    val target: String,
    val action: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val mode: String = "immediate",
    @SerialName("command_id") val commandId: String? = null,
)

@Serializable
data class ActionResultPayload(
    val action: String,
    val status: String,
    val error: String? = null,
    @SerialName("command_id") val commandId: String? = null,
)

@Serializable
data class ActionErrorPayload(
    val action: String? = null,
    val code: String,
    val message: String,
    @SerialName("command_id") val commandId: String? = null,
)

@Serializable
data class PingPayload(val timestamp: Long = 0)

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
