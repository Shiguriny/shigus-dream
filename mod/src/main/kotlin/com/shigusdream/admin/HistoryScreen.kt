package com.shigusdream.admin

import com.shigusdream.ShigusDreamClient
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent

class HistoryScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.history.title")) {

    private var left = 12
    private var boxWidth = 440
    private var selectedId: String? = null
    private var scroll = 0
    private lateinit var repeatButton: UiButton

    override fun init() {
        clearWidgets()
        boxWidth = minOf(520, width - 24).coerceAtLeast(180)
        left = (width - boxWidth) / 2
        repeatButton = addRenderableWidget(UiButton(left, height - 54, (boxWidth - 6) / 2, 20,
            I18n.component("shigusdream.history.repeat"), ::repeatSelected, true))
        addRenderableWidget(UiButton(left + (boxWidth + 6) / 2, height - 54, (boxWidth - 6) / 2, 20,
            I18n.component("shigusdream.common.back"), ::onClose))
        repeatButton.active = false
    }

    private fun repeatSelected() {
        val entry = CommandHistory.newest().firstOrNull { it.requestId == selectedId } ?: return
        val ids = ShigusDreamClient.sendAction(entry.target, entry.action, entry.args)
        if (ids.isEmpty()) ShigusDreamClient.chatFeedback(I18n.text("shigusdream.history.send_failed"))
    }

    override fun mouseClicked(e: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = e.x.toInt()
        val my = e.y.toInt()
        val top = 42
        val rows = ((height - 108) / 24).coerceAtLeast(1)
        if (mx in left..(left + boxWidth) && my in top until (top + rows * 24)) {
            CommandHistory.newest().getOrNull(scroll + (my - top) / 24)?.let {
                selectedId = it.requestId
                repeatButton.active = !it.local
                return true
            }
        }
        return super.mouseClicked(e, doubled)
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        val rows = ((height - 108) / 24).coerceAtLeast(1)
        scroll = (scroll - yDelta.toInt()).coerceIn(0, (CommandHistory.newest().size - rows).coerceAtLeast(0))
        return true
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.outline(left - 8, 8, left + boxWidth + 8, height - 4, 0xFF34344C.toInt())
        g.text(font, title, left, 18, -1)
        val entries = CommandHistory.newest()
        val rows = ((height - 108) / 24).coerceAtLeast(1)
        if (entries.isEmpty()) g.text(font, I18n.component("shigusdream.history.empty"), left, 48, 0xFF909090.toInt())
        repeat(rows) { row ->
            val entry = entries.getOrNull(scroll + row) ?: return@repeat
            val y = 42 + row * 24
            if (entry.requestId == selectedId) g.fill(left, y, left + boxWidth, y + 22, 0xFF2A3A55.toInt())
            val symbol = when (entry.status) { "executed" -> "✔"; "failed" -> "✖"; else -> "…" }
            val color = when (entry.status) { "executed" -> 0xFF55FF55.toInt(); "failed" -> 0xFFFF5555.toInt(); else -> 0xFFFFFF55.toInt() }
            val action = entry.action.substringAfter(':')
            g.text(font, net.minecraft.network.chat.Component.literal("$symbol $action → ${entry.target}"), left + 5, y + 3, color)
            val detail = entry.error ?: entry.sentAt.replace('T', ' ').take(19)
            g.text(font, net.minecraft.network.chat.Component.literal(detail), left + 5, y + 13, 0xFF909090.toInt())
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}
