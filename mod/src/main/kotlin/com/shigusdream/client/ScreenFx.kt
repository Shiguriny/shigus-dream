package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.actions.ActionResult
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import kotlin.math.min

/**
 * Экранные пост-эффекты. Два механизма:
 * 1. Шейдерные post-цепочки (setPostEffect через access widener): grayscale, vignette, vhs,
 *    noisefx (свои .fsh), blurfx (box_blur Radius 12), invert и spider (ванильные цепочки).
 * 2. HUD-оверлеи: darkening, sleepy, color_filter, damaged_vision.
 * Одновременно активны один шейдерный и один HUD-эффект.
 */
object ScreenFx {

    private var shaderId: Identifier? = null
    private var shaderTicks = 0

    private var hudType: String? = null
    private var hudTicks = 0
    private var hudIntensity = 1.0f
    private var hudColor = 0xFFAA00

    fun apply(effect: String, duration: Int, intensity: Float, colorHex: String): ActionResult {
        val ticks = duration.coerceIn(20, 24000)
        val shader = when (effect) {
            "grayscale" -> Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, "grayscale")
            "blur" -> Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, "blurfx")
            "invert" -> Identifier.withDefaultNamespace("invert")
            "spider_vision" -> Identifier.withDefaultNamespace("spider")
            "vignette", "vhs", "noise" -> Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, effect)
            else -> null
        }
        val hud = when (effect) {
            "darkening", "sleepy", "color_filter", "damaged_vision" -> effect
            else -> null
        }

        if (shader == null && hud == null) {
            return ActionResult.fail("unknown effect: $effect")
        }

        if (shader != null) {
            shaderId = shader
            shaderTicks = ticks
            try {
                Minecraft.getInstance().gameRenderer.setPostEffect(shader)
            } catch (e: Exception) {
                ShigusDream.LOGGER.warn("Не удалось применить пост-эффект {}: {}", effect, e.message)
                shaderId = null
                return ActionResult.fail("shader error: ${e.message}")
            }
        }
        if (hud != null) {
            hudType = hud
            hudTicks = ticks
            hudIntensity = intensity
            hudColor = runCatching {
                Integer.parseInt(colorHex.removePrefix("#").removePrefix("0x"), 16) and 0xFFFFFF
            }.getOrDefault(0xFFAA00)
        }
        ShigusDream.LOGGER.info("экранный эффект {} на {} тиков", effect, ticks)
        return ActionResult.ok()
    }

    fun tick(mc: Minecraft) {
        if (shaderTicks > 0 && --shaderTicks == 0) {
            shaderId = null
            runCatching { mc.gameRenderer.clearPostEffect() }
        }
        if (hudTicks > 0 && --hudTicks == 0) {
            hudType = null
        }
    }

    fun render(g: net.minecraft.client.gui.GuiGraphicsExtractor) {
        val type = hudType ?: return
        if (hudTicks <= 0) return
        val w = g.guiWidth()
        val h = g.guiHeight()
        val t = System.currentTimeMillis() / 1000.0
        val pulse = (0.5 + 0.5 * Math.sin(t * Math.PI)).toFloat()

        when (type) {
            "darkening" -> {
                g.fill(0, 0, w, h, withAlpha(0x000000, (110 * hudIntensity).toInt()))
            }

            "color_filter" -> {
                g.fill(0, 0, w, h, withAlpha(hudColor, (95 * hudIntensity).toInt()))
            }

            "damaged_vision" -> {
                val base = 0xAA0000
                val layers = 6
                val depth = (h * 0.28 * hudIntensity).toInt()
                for (i in 0 until layers) {
                    val d = depth * (layers - i) / layers
                    val alpha = ((18 * hudIntensity * (i + 1)) * (0.6 + 0.6 * pulse)).toInt()
                        .coerceIn(0, 200)
                    g.fill(0, 0, w, d, withAlpha(base, alpha))
                    g.fill(0, h - d, w, h, withAlpha(base, alpha))
                    g.fill(0, 0, d, h, withAlpha(base, alpha))
                    g.fill(w - d, 0, w, h, withAlpha(base, alpha))
                }
            }

            "sleepy" -> {
                // «Веки»: синусоидально опускающиеся чёрные шторки сверху и снизу + лёгкое затемнение
                val phase = (0.5 + 0.5 * Math.sin(t * 2.0 * Math.PI / 7.0)).toFloat()
                val lid = (h * 0.30 * phase * hudIntensity).toInt()
                g.fill(0, 0, w, lid, 0xFF000000.toInt())
                g.fill(0, h - lid, w, h, 0xFF000000.toInt())
                g.fill(0, 0, w, h, withAlpha(0x000000, (40 * hudIntensity).toInt()))
            }
        }
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (min(alpha, 255) shl 24) or (rgb and 0xFFFFFF)
}
