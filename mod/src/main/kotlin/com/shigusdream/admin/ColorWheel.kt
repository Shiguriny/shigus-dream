package com.shigusdream.admin

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Цветовой круг HSV: угол = тон, радиус = насыщенность, яркость фикс.
 * Отрисовка полярными ячейками (fill поддерживает только прямоугольники).
 * Клик выбирает цвет и возвращает его как #RRGGBB.
 */
class ColorWheel(
    x: Int,
    y: Int,
    diameter: Int,
    private val onPick: (Int) -> Unit,
) : AbstractWidget(x, y, diameter, diameter, Component.literal("color")) {

    private var selected: Int? = null

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val cx = x + width / 2.0
        val cy = y + height / 2.0
        val outer = width / 2.0
        val inner = outer * 0.18 // серое сердце круга
        val cell = 3.0

        var py = y.toDouble()
        while (py < y + height) {
            var px = x.toDouble()
            while (px < x + width) {
                val dx = px + cell / 2 - cx
                val dy = py + cell / 2 - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist in inner..outer) {
                    val hue = ((atan2(dy, dx) + Math.PI) / (2 * Math.PI))
                    val sat = (dist - inner) / (outer - inner)
                    g.fill(px.toInt(), py.toInt(), (px + cell).toInt(), (py + cell).toInt(), hsvToRgb(hue, sat, 1.0))
                }
                px += cell
            }
            py += cell
        }
        // Внутренний круг — серый (S=0)
        g.fill(
            (cx - inner).toInt(), (cy - inner).toInt(), (cx + inner).toInt(), (cy + inner).toInt(),
            0xFF3A3A55.toInt(),
        )

        // Маркер выбранного цвета
        selected?.let { rgb ->
            val marker = markerPos() ?: return
            g.fill(marker.first - 3, marker.second - 3, marker.first + 3, marker.second + 3, 0xFFFFFFFF.toInt())
            g.fill(marker.first - 2, marker.second - 2, marker.first + 2, marker.second + 2, 0xFF000000.toInt() or rgb)
        }

        // Обводка
        g.fill(x, y, x + width, y + 1, 0xFF4A4A6A.toInt())
        g.fill(x, y + height - 1, x + width, y + height, 0xFF4A4A6A.toInt())
        g.fill(x, y, x + 1, y + height, 0xFF4A4A6A.toInt())
        g.fill(x + width - 1, y, x + width, y + height, 0xFF4A4A6A.toInt())
    }

    private fun markerPos(): Pair<Int, Int>? {
        val rgb = selected ?: return null
        val (hue, sat) = rgbToHsv(rgb)
        val cx = x + width / 2.0
        val cy = y + height / 2.0
        val outer = width / 2.0
        val inner = outer * 0.18
        val angle = hue * 2 * Math.PI - Math.PI
        val dist = inner + sat * (outer - inner)
        return (cx + dist * cos(angle)).toInt() to (cy + dist * sin(angle)).toInt()
    }

    override fun onClick(e: MouseButtonEvent, doubled: Boolean) {
        val cx = x + width / 2.0
        val cy = y + height / 2.0
        val outer = width / 2.0
        val inner = outer * 0.18
        val dx = e.x - cx
        val dy = e.y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > outer || dist < inner) return
        val hue = ((atan2(dy, dx) + Math.PI) / (2 * Math.PI))
        val sat = min(1.0, (dist - inner) / (outer - inner))
        val rgb = hsvToRgb(hue, sat, 1.0) and 0xFFFFFF
        selected = rgb
        playButtonClickSound(Minecraft.getInstance().soundManager)
        onPick(rgb)
    }

    override fun updateWidgetNarration(narration: NarrationElementOutput) {}

    companion object {
        /** HSV -> ARGB (V=1). */
        fun hsvToRgb(h: Double, s: Double, v: Double): Int {
            val i = (h * 6).toInt()
            val f = h * 6 - i
            val p = v * (1 - s)
            val q = v * (1 - f * s)
            val t = v * (1 - (1 - f) * s)
            val (r, g, b) = when (i % 6) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            val ri = (r * 255).toInt().coerceIn(0, 255)
            val gi = (g * 255).toInt().coerceIn(0, 255)
            val bi = (b * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        /** RGB -> (hue, saturation) для позиционирования маркера. */
        fun rgbToHsv(rgb: Int): Pair<Double, Double> {
            val r = (rgb shr 16 and 0xFF) / 255.0
            val g = (rgb shr 8 and 0xFF) / 255.0
            val b = (rgb and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val h = when (max) {
                min -> 0.0
                r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6.0
                g -> ((b - r) / d + 2) / 6.0
                else -> ((r - g) / d + 4) / 6.0
            }
            val s = if (max == 0.0) 0.0 else d / max
            return h to s
        }
    }
}
