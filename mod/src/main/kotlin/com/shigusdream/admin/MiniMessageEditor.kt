package com.shigusdream.admin

import com.shigusdream.client.MiniText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

/**
 * Мини-эдитор MiniMessage для поля text: цветовой круг HSV, стили B/I/U/S/O,
 * hex-поле и живой предпросмотр. Виджеты регистрируются через колбэки экрана.
 */
class MiniMessageEditor(
    private val target: EditBox,
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val adder: (AbstractWidget) -> Unit,
    private val remover: (AbstractWidget) -> Unit,
) {
    companion object {
        val STYLE_BUTTONS = listOf(
            "B" to "bold", "I" to "italic", "U" to "underlined", "S" to "strikethrough", "O" to "obfuscated",
        )
        private const val WHEEL = 64
    }

    private val font: Font get() = Minecraft.getInstance().font

    // Раскладка: слева круг, справа от него hex + стили, ниже — предпросмотр.
    private val wheel = ColorWheel(x, y + 12, WHEEL) { rgb -> wrap("<#%06X>".format(rgb), "</#%06X>".format(rgb)) }

    private val hexBox = EditBox(
        font,
        x + WHEEL + 10, y + 12, 62, 14,
        Component.literal("hex"),
    ).apply {
        setMaxLength(7)
        setValue("#")
        setHint(Component.literal("#RRGGBB"))
    }

    private val styleY = y + 12 + 18

    private val buttons: List<EditorButton> = STYLE_BUTTONS.mapIndexed { idx, (label, tag) ->
        EditorButton(x + WHEEL + 10 + idx * 20, styleY, 18, 14, label) { wrap("<$tag>", "</$tag>") }
    } + EditorButton(x + WHEEL + 10 + STYLE_BUTTONS.size * 20, styleY, 18, 14, "↺") { wrap("<reset>", "") }

    fun initWidgets() {
        adder(wheel)
        adder(hexBox)
        buttons.forEach { adder(it) }
    }

    fun removeWidgets() {
        remover(wheel)
        remover(hexBox)
        buttons.forEach { remover(it) }
    }

    fun setWidgetsVisible(visible: Boolean) {
        wheel.visible = visible
        hexBox.visible = visible
        buttons.forEach { it.visible = visible }
    }

    private fun wrap(open: String, close: String) {
        val value = target.getValue()
        val next = if (value.isEmpty()) "$open$close" else "$open$value$close"
        if (next.length <= 256) {
            target.setValue(next)
        }
    }

    fun applyHex() {
        val hex = hexBox.getValue().trim()
        if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
            wrap("<$hex>", "</$hex>")
        }
    }

    fun render(g: GuiGraphicsExtractor, mouseX: Double, mouseY: Double) {
        g.text(font, Component.literal("Formatting"), x, y, 0xFFA0A0B0.toInt())
        g.text(font, Component.literal("Hex"), x + WHEEL + 10, y + 2, 0xFFA0A0B0.toInt())

        // Кнопка применения hex справа от поля
        val applyX = hexBox.x + hexBox.width + 2
        val hovered = mouseX >= applyX && mouseX <= applyX + 14 && mouseY >= hexBox.y && mouseY <= hexBox.y + hexBox.height
        g.fill(applyX, hexBox.y, applyX + 14, hexBox.y + hexBox.height, if (hovered) 0xFF3A3A55.toInt() else 0xFF22223A.toInt())
        g.text(font, Component.literal("✔"), applyX + 3, hexBox.y + 3, 0xFF55FF55.toInt())

        val previewY = y + 12 + WHEEL + 8
        g.text(font, Component.literal("Preview"), x, previewY, 0xFFA0A0B0.toInt())
        val preview = MiniText.parse(target.getValue().ifBlank { " " })
        var px = x
        var py = previewY + 12
        for (child in preview.siblings) {
            val w = font.width(child)
            if (px + w > x + width) {
                px = x
                py += 12
            }
            g.text(font, child, px, py, 0xFFFFFFFF.toInt())
            px += w
        }
    }

    fun handleHexClick(mx: Int, my: Int): Boolean {
        val applyX = hexBox.x + hexBox.width + 2
        if (mx in applyX..(applyX + 14) && my in hexBox.y..(hexBox.y + hexBox.height)) {
            applyHex()
            return true
        }
        return false
    }

    /** Компактная текстовая кнопка стиля. */
    private inner class EditorButton(
        bx: Int, by: Int, bw: Int, bh: Int,
        label: String,
        private val action: () -> Unit,
    ) : AbstractWidget(bx, by, bw, bh, Component.literal(label)) {

        override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
            val border = if (isHovered) 0xFFB088FF.toInt() else 0xFF4A4A6A.toInt()
            g.fill(x, y, x + width, y + height, border)
            val inset = if (isHovered) 1 else 2
            g.fill(x + inset, y + inset, x + width - inset, y + height - inset, 0xFF22223A.toInt())
            g.text(
                font, message,
                x + (width - font.width(message)) / 2,
                y + (height - 8) / 2,
                0xFFFFFFFF.toInt(),
            )
        }

        override fun updateWidgetNarration(narration: NarrationElementOutput) {}

        override fun onClick(e: MouseButtonEvent, doubled: Boolean) {
            playButtonClickSound(Minecraft.getInstance().soundManager)
            action()
        }
    }
}
