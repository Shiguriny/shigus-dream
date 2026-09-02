package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.ArrayDeque

/**
 * Очередь сообщений show_message: текст показывается по центру экрана поверх HUD,
 * затухая к концу duration. Чистая JVM-часть (очередь) тестируется без Minecraft.
 */
object MessageOverlay {
    private data class Entry(val text: String, var ticksLeft: Int, val totalTicks: Int)

    private val queue = ArrayDeque<Entry>()
    private const val MAX_QUEUE = 5

    fun enqueue(text: String, durationTicks: Int) {
        if (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.addLast(Entry(text, durationTicks.coerceAtLeast(1), durationTicks.coerceAtLeast(1)))
    }

    fun tick() {
        val it = queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.ticksLeft--
            if (e.ticksLeft <= 0) it.remove()
        }
    }

    fun render(g: GuiGraphicsExtractor) {
        if (queue.isEmpty()) return
        val font: Font = Minecraft.getInstance().font
        var y = g.guiHeight() / 2 - 20 - queue.size * 12
        for (entry in queue) {
            val alpha = fadeAlpha(entry)
            val color = (alpha shl 24) or 0xFFFFFF
            g.text(font, entry.text, (g.guiWidth() - font.width(entry.text)) / 2, y, color)
            y += 12
        }
    }

    private fun fadeAlpha(entry: Entry): Int {
        val fade = 10
        return if (entry.ticksLeft <= fade) (entry.ticksLeft * 255) / fade else 255
    }

    fun clear() = queue.clear()
}
