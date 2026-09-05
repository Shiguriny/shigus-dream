package com.shigusdream.client

import com.shigusdream.ShigusDreamClient

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * Интерактивный вопрос цели: окно с кнопками вариантов.
 * Выбор уходит владельцу через action.result (note = выбранный вариант).
 */
class AskScreen(
    private val requestId: String,
    private val question: String,
    private val options: List<String>,
    private val durationTicks: Int,
) : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Вопрос")) {

    private var ticksLeft = durationTicks.coerceAtLeast(60)

    override fun init() {
        var btnY = height / 2 - 10
        for (option in options) {
            addRenderableWidget(
                Button.builder(Component.literal(option)) {
                    answer(option)
                }.bounds(width / 2 - 110, btnY, 220, 20).build(),
            )
            btnY += 26
        }
    }

    private fun answer(option: String) {
        ShigusDreamClient.connection.sendResult(
            requestId,
            "shigusdream:ask",
            true,
            null,
            note = option,
        )
        onClose()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Затемнение фона
        g.fill(0, 0, width, height, 0x90000000.toInt())
        val label = Component.literal("§dВопрос от ведущего:")
        g.centeredText(font, label, width / 2, height / 2 - 96, 0xFFFFFFFF.toInt())
        g.centeredText(
            font,
            com.shigusdream.client.MiniText.parse(question),
            width / 2, height / 2 - 80, 0xFFFFFF,
        )
        g.centeredText(
            font,
            Component.literal("Ответ исчезнет через ${(ticksLeft / 20).toString().padStart(2, '0')} с"),
            width / 2, height - 56, 0xFF909090.toInt(),
        )
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun tick() {
        super.tick()
        if (--ticksLeft <= 0) {
            ShigusDreamClient.connection.sendResult(
                requestId,
                "shigusdream:ask",
                true,
                null,
                note = "(нет ответа)",
            )
            onClose()
        }
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(null)
    }
}
