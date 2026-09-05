package com.shigusdream.admin

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/** Поле ввода в той же тёмной визуальной системе, что и кнопки панели. */
class UiTextField(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
) : EditBox(font, x, y, width, height, message) {
    companion object {
        const val HORIZONTAL_PADDING = 5
        const val VERTICAL_PADDING = 5
    }

    val outerX: Int get() = x - HORIZONTAL_PADDING
    val outerY: Int get() = y - VERTICAL_PADDING
    val outerRight: Int get() = x + width + HORIZONTAL_PADDING
    val outerBottom: Int get() = y + height + VERTICAL_PADDING

    init {
        setBordered(false)
        setTextColor(0xFFF2F2FF.toInt())
        setTextColorUneditable(0xFF9090A8.toInt())
        setTextShadow(false)
    }

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val border = when {
            isFocused() -> 0xFFB088FF.toInt()
            isHovered -> 0xFF7C5CFF.toInt()
            else -> 0xFF4A4A6A.toInt()
        }
        g.fill(outerX, outerY, outerRight, outerBottom, if (active) 0xEE171727.toInt() else 0xCC12121E.toInt())
        g.fill(outerX, outerY, outerX + 2, outerBottom, border)
        g.fill(outerX, outerY, outerRight, outerY + 1, border)
        g.fill(outerX, outerBottom - 1, outerRight, outerBottom, border)
        g.fill(outerRight - 1, outerY, outerRight, outerBottom, border)
        super.extractWidgetRenderState(g, mouseX, mouseY, delta)
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        visible && mouseX >= outerX && mouseX < outerRight && mouseY >= outerY && mouseY < outerBottom
}
