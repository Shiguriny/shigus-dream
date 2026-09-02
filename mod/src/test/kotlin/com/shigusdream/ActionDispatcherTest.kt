package com.shigusdream

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionDispatcher
import com.shigusdream.actions.ActionRegistry
import com.shigusdream.actions.impl.ShowMessageAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionDispatcherTest {

    private fun registry1(): ActionRegistry = ActionRegistry().apply { register(ShowMessageAction) }

    @Test
    fun `executes valid action once`() {
        val latch = CountDownLatch(1)
        val results = mutableListOf<Pair<Boolean, String?>>()

        val dispatcher = ActionDispatcher(
            registry = registry1(),
            executor = { it.run() },
            resultSink = { _, _, ok, err ->
                synchronized(results) { results += ok to err }
                latch.countDown()
            },
        )

        val args = JsonObject().apply {
            addProperty("text", "test")
            addProperty("duration", 20)
        }
        // MessageOverlay безопасен вне MC (просто очередь).
        dispatcher.handle("req-1", "shigusdream:show_message", args)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, results.size)
        assertEquals(true to null, results[0])
    }

    @Test
    fun `duplicate request id rejected`() {
        val results = mutableListOf<Pair<Boolean, String?>>()
        val latch = CountDownLatch(2)

        val dispatcher = ActionDispatcher(
            registry = registry1(),
            executor = { it.run() },
            resultSink = { _, _, ok, err ->
                synchronized(results) { results += ok to err }
                latch.countDown()
            },
        )

        val args = JsonObject().apply { addProperty("text", "dup") }
        dispatcher.handle("same-id", "shigusdream:show_message", args)
        dispatcher.handle("same-id", "shigusdream:show_message", args)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(2, results.size)
        assertEquals(true to null, results[0])
        assertEquals(false to "duplicate_request", results[1])
    }

    @Test
    fun `unknown action rejected`() {
        val latch = CountDownLatch(1)
        val results = mutableListOf<Pair<Boolean, String?>>()
        val dispatcher = ActionDispatcher(
            registry = registry1(),
            executor = { it.run() },
            resultSink = { _, _, ok, err ->
                synchronized(results) { results += ok to err }
                latch.countDown()
            },
        )
        dispatcher.handle("req-2", "shigusdream:not_registered", JsonObject())
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false to "unknown_action", results[0])
    }

    @Test
    fun `invalid arguments rejected`() {
        val latch = CountDownLatch(1)
        val results = mutableListOf<Pair<Boolean, String?>>()
        val dispatcher = ActionDispatcher(
            registry = registry1(),
            executor = { it.run() },
            resultSink = { _, _, ok, err ->
                synchronized(results) { results += ok to err }
                latch.countDown()
            },
        )
        dispatcher.handle("req-3", "shigusdream:show_message", JsonObject().apply { addProperty("duration", 50) })
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(results[0].second!!.startsWith("invalid_arguments"))
    }
}
