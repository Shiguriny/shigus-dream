package com.shigusdream.admin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Кнопка в едином тёмном стиле панели (без ванильной градиентной стилистики).
 */
class UiButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    label: Component,
    private val onPress: () -> Unit,
    private val accent: Boolean = false,
) : AbstractWidget(x, y, width, height, label) {

    constructor(x: Int, y: Int, width: Int, height: Int, label: String, onPress: () -> Unit, accent: Boolean = false) :
        this(x, y, width, height, Component.literal(label), onPress, accent)

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val bg = when {
            !active -> 0xFF1A1A2C.toInt()
            isHovered -> 0xFF3A3A55.toInt()
            else -> 0xFF22223A.toInt()
        }
        g.fill(x, y, x + width, y + height, bg)
        val borderColor = if (accent) 0xFF7C5CFF.toInt() else 0xFF4A4A6A.toInt()
        g.fill(x, y, x + 1, y + height, borderColor)
        if (isHovered && active) {
            g.fill(x, y, x + width, y + 1, borderColor)
        }
        val font = Minecraft.getInstance().font
        val color = if (active) 0xFFFFFFFF.toInt() else 0xFF707070.toInt()
        g.text(font, message, x + (width - font.width(message)) / 2, y + (height - 8) / 2, color)
    }

    override fun updateWidgetNarration(narration: NarrationElementOutput) {}

    override fun onClick(e: MouseButtonEvent, doubled: Boolean) {
        if (active) {
            playButtonClickSound(Minecraft.getInstance().soundManager)
            onPress()
        }
    }
}
