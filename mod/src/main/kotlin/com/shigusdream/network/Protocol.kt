package com.shigusdream.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shigusdream.ShigusDream

/** Типы сообщений протокола (зеркало backend). */
object Msg {
    const val PROTOCOL_VERSION = 1

    const val AUTH = "auth"
    const val AUTH_PENDING = "auth.pending"
    const val AUTH_SUCCESS = "auth.success"
    const val AUTH_ERROR = "auth.error"
    const val PRESENCE_LIST = "presence.list"
    const val ACTION_EXECUTE = "action.execute"
    const val ACTION_RESULT = "action.result"
    const val ACTION_ERROR = "action.error"
    const val PING = "ping"
    const val PONG = "pong"

    val ALL = setOf(
        AUTH, AUTH_PENDING, AUTH_SUCCESS, AUTH_ERROR, PRESENCE_LIST,
        ACTION_EXECUTE, ACTION_RESULT, ACTION_ERROR, PING, PONG,
    )
}

data class Envelope(
    val messageType: String,
    val requestId: String? = null,
    val payload: JsonObject = JsonObject(),
) {
    fun toJson(): String {
        val obj = JsonObject()
        obj.addProperty("protocol_version", Msg.PROTOCOL_VERSION)
        obj.addProperty("message_type", messageType)
        requestId?.let { obj.addProperty("request_id", it) }
        obj.add("payload", payload)
        return obj.toString()
    }

    companion object {
        fun fromJson(text: String): Envelope? = try {
            val root = JsonParser.parseString(text).asJsonObject
            val version = root.get("protocol_version")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
            val type = root.get("message_type")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (version != Msg.PROTOCOL_VERSION) {
                ShigusDream.LOGGER.warn("protocol mismatch: ожидается {}, получено {}", Msg.PROTOCOL_VERSION, version)
                return null
            }
            Envelope(
                messageType = type,
                requestId = root.get("request_id")?.takeIf { it.isJsonPrimitive }?.asString,
                payload = root.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
            )
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("malformed packet: {}", text.take(200), e)
            null
        }
    }
}
