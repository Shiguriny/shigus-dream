package com.shigusdream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `envelope roundtrip`() {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("text", "Привет!")
            addProperty("duration", 100)
        }
        val env = com.shigusdream.network.Envelope(
            messageType = com.shigusdream.network.Msg.ACTION_EXECUTE,
            requestId = "abc-123",
            payload = payload,
        )
        val text = env.toJson()
        val parsed = com.shigusdream.network.Envelope.fromJson(text)

        assertNotNull(parsed)
        assertEquals(com.shigusdream.network.Msg.ACTION_EXECUTE, parsed.messageType)
        assertEquals("abc-123", parsed.requestId)
        assertEquals("Привет!", parsed.payload.get("text").asString)
        assertEquals(100, parsed.payload.get("duration").asInt)
    }

    @Test
    fun `rejects wrong protocol version`() {
        val text = """{"protocol_version":99,"message_type":"ping","payload":{}}"""
        assertNull(com.shigusdream.network.Envelope.fromJson(text))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(com.shigusdream.network.Envelope.fromJson("not json at all"))
        assertNull(com.shigusdream.network.Envelope.fromJson("""{"protocol_version":1}"""))
    }

    @Test
    fun `websocket url derivation`() {
        assertEquals("ws://localhost:8080/ws", com.shigusdream.config.ModConfig.websocketUrl("http://localhost:8080"))
        assertEquals("wss://example.com/ws", com.shigusdream.config.ModConfig.websocketUrl("https://example.com"))
        assertEquals("ws://host:9000/custom/ws", com.shigusdream.config.ModConfig.websocketUrl("ws://host:9000/custom/ws"))
    }
}
