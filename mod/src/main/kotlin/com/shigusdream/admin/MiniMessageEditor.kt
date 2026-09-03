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

/**
 * Интерактивный MiniMessage-эдитор для поля text действия show_message:
 * палитра 16 цветов, стили B/I/U/S/O, hex-поле, живой предпросмотр.
 * Виджеты добавляются/удаляются через колбэки хост-экрана (protected API Screen).
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
        val PALETTE = listOf(
            "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00, "dark_aqua" to 0x00AAAA,
            "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA, "gold" to 0xFFAA00, "gray" to 0xAAAAAA,
            "dark_gray" to 0x555555, "blue" to 0x5555FF, "green" to 0x55FF55, "aqua" to 0x55FFFF,
            "red" to 0xFF5555, "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
        )
        val STYLE_BUTTONS = listOf(
            "B" to "bold", "I" to "italic", "U" to "underlined", "S" to "strikethrough", "O" to "obfuscated",
        )
        private const val SWATCH = 16
        private const val GAP = 2
    }

    private val font: Font get() = Minecraft.getInstance().font

    private val hexBox = EditBox(
        font,
        x + width - 70, y + 2, 68, 14,
        Component.literal("hex"),
    ).apply {
        setMaxLength(7)
        setValue("#")
        setHint(Component.literal("#RRGGBB"))
    }

    private var buttons = listOf<EditorButton>()

    fun initWidgets() {
        // Палитра: 8 сватчей в ряд.
        var i = 0
        val swatches = mutableListOf<EditorButton>()
        for ((name, color) in PALETTE) {
            val col = i % 8
            val row = i / 8
            swatches += EditorButton(
                x + col * (SWATCH + GAP), y + 2 + row * (SWATCH + GAP), SWATCH, SWATCH,
                "", color,
            ) { wrap("<$name>", "</$name>") }
            i++
        }
        // Стилевые кнопки под палитрой.
        val styleY = y + 2 + 2 * (SWATCH + GAP) + 4
        buttons = swatches + STYLE_BUTTONS.mapIndexed { idx, (label, tag) ->
            EditorButton(x + idx * 20, styleY, 18, 14, label, null) { wrap("<$tag>", "</$tag>") }
        } + EditorButton(x + STYLE_BUTTONS.size * 20, styleY, 18, 14, "↺", null) { wrap("<reset>", "") }
        adder(hexBox)
        buttons.forEach { adder(it) }
    }

    fun removeWidgets() {
        remover(hexBox)
        buttons.forEach { remover(it) }
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
        g.text(font, Component.literal("Formatting"), x, y - 10, 0xFFA0A0B0.toInt())
        // Кнопка применения hex слева от поля
        val applyX = hexBox.x - 16
        val hovered = mouseX >= applyX && mouseX <= applyX + 14 && mouseY >= hexBox.y && mouseY <= hexBox.y + hexBox.height
        g.fill(applyX, hexBox.y, applyX + 14, hexBox.y + hexBox.height, if (hovered) 0xFF3A3A55.toInt() else 0xFF22223A.toInt())
        g.text(font, Component.literal("✔"), applyX + 3, hexBox.y + 3, 0xFF55FF55.toInt())
        // Предпросмотр
        val previewY = y + 84
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
        val applyX = hexBox.x - 16
        if (mx in applyX..(applyX + 14) && my in hexBox.y..(hexBox.y + hexBox.height)) {
            applyHex()
            return true
        }
        return false
    }

    /** Компактная кнопка эдитора: цветной сватч или текстовая. */
    private inner class EditorButton(
        bx: Int, by: Int, bw: Int, bh: Int,
        label: String,
        private val swatchColor: Int?,
        private val action: () -> Unit,
    ) : AbstractWidget(bx, by, bw, bh, Component.literal(label)) {

        override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
            val border = if (isHovered) 0xFFB088FF.toInt() else 0xFF4A4A6A.toInt()
            g.fill(x, y, x + width, y + height, border)
            val inset = if (isHovered) 1 else 2
            if (swatchColor != null) {
                g.fill(x + inset, y + inset, x + width - inset, y + height - inset, 0xFF000000.toInt() or swatchColor)
            } else {
                g.fill(x + inset, y + inset, x + width - inset, y + height - inset, 0xFF22223A.toInt())
                g.text(
                    font, message,
                    x + (width - font.width(message)) / 2,
                    y + (height - 8) / 2,
                    0xFFFFFFFF.toInt(),
                )
            }
        }

        override fun updateWidgetNarration(narration: NarrationElementOutput) {}

        override fun onClick(e: MouseButtonEvent, doubled: Boolean) {
            playButtonClickSound(Minecraft.getInstance().soundManager)
            action()
        }
    }
}
