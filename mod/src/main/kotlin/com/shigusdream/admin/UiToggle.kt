package com.shigusdream.admin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Переключатель в тёмном стиле панели: полоса- track + кружок состояния. */
class UiToggle(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    label: String,
    var checked: Boolean,
    private val onChange: (Boolean) -> Unit,
) : AbstractWidget(x, y, width, height, Component.literal(label)) {

    private val labelComponent = Component.literal(label)

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        g.text(font, labelComponent, x, y + (height - 8) / 2, 0xFFC8C8D8.toInt())

        val trackW = 34
        val trackH = 12
        val tx = x + width - trackW
        val ty = y + (height - trackH) / 2
        val trackColor = when {
            checked -> 0xFF55FF55.toInt()
            isHovered -> 0xFF4A4A6A.toInt()
            else -> 0xFF2A2A44.toInt()
        }
        g.fill(tx, ty, tx + trackW, ty + trackH, trackColor)
        g.fill(tx, ty, tx + 1, ty + trackH, 0xFF4A4A6A.toInt())
        // Кружок состояния
        val knobX = if (checked) tx + trackW - 10 else tx + 2
        g.fill(knobX, ty + 2, knobX + 8, ty + trackH - 2, 0xFFFFFFFF.toInt())
        g.text(
            font,
            Component.literal(if (checked) "вкл" else "выкл"),
            tx - font.width("выкл") - 6, ty + 2,
            0xFF909090.toInt(),
        )
    }

    override fun onClick(e: MouseButtonEvent, doubled: Boolean) {
        checked = !checked
        playButtonClickSound(Minecraft.getInstance().soundManager)
        onChange(checked)
    }

    override fun updateWidgetNarration(narration: NarrationElementOutput) {}
}
