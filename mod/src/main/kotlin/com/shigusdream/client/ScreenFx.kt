package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.actions.ActionResult
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import kotlin.math.min

/**
 * Экранные пост-эффекты. Два механизма:
 * 1. Шейдерные post-цепочки (setPostEffect через access widener): grayscale, vignette, vhs,
 *    blurfx (box_blur Radius 12), invert и spider (ванильные цепочки).
 * 2. HUD-оверлеи: darkening, sleepy, color_filter, damaged_vision, noise.
 * Одновременно активны один шейдерный и один HUD-эффект.
 */
object ScreenFx {

    data class Status(val id: String, val effect: String, val remainingTicks: Int, val totalTicks: Int)

    private var shaderId: Identifier? = null
    private var shaderName: String? = null
    private var shaderTicks = 0
    private var shaderTotalTicks = 0

    private var hudType: String? = null
    private var hudTicks = 0
    private var hudTotalTicks = 0
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
            // Отсутствующая post-цепочка в 26.x роняет кадр вместе с соединением — проверяем ресурс заранее.
            if (!postChainExists(shader)) {
                ShigusDream.LOGGER.warn("Пост-цепочка {} не найдена в ресурсах", shader)
                return ActionResult.fail("post chain not found: $shader")
            }
            shaderId = shader
            shaderName = effect
            shaderTicks = ticks
            shaderTotalTicks = ticks
            try {
                Minecraft.getInstance().gameRenderer.setPostEffect(shader)
            } catch (e: Exception) {
                ShigusDream.LOGGER.warn("Не удалось применить пост-эффект {}: {}", effect, e.message)
                shaderId = null
                shaderName = null
                // Шум деградирует в дешёвый HUD-вариант (редкие точки), остальные шейдерные эффекты просто не применяются.
                if (effect == "noise") {
                    hudType = "noise"
                    hudTicks = ticks
                    hudTotalTicks = ticks
                    hudIntensity = intensity
                } else {
                    return ActionResult.fail("shader error: ${e.message}")
                }
            }
        }
        if (hud != null) {
            hudType = hud
            hudTicks = ticks
            hudTotalTicks = ticks
            hudIntensity = intensity
            hudColor = runCatching {
                Integer.parseInt(colorHex.removePrefix("#").removePrefix("0x"), 16) and 0xFFFFFF
            }.getOrDefault(0xFFAA00)
        }
        ShigusDream.LOGGER.info("экранный эффект {} на {} тиков", effect, ticks)
        return ActionResult.ok()
    }

    /** Проверяет наличие post_effect/<ns>/<path>.json в ресурсах игры. */
    private fun postChainExists(id: Identifier): Boolean {
        val resId = Identifier.fromNamespaceAndPath(id.namespace, "post_effect/${id.path}.json")
        val result = runCatching {
            Minecraft.getInstance().resourceManager.getResource(resId)
        }.getOrNull()
        return when (result) {
            null -> false
            is java.util.Optional<*> -> result.isPresent
            else -> true
        }
    }

    fun tick(mc: Minecraft) {
        if (shaderTicks > 0 && --shaderTicks == 0) {
            shaderId = null
            shaderName = null
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
        val fadeTicks = min(10, (hudTotalTicks / 4).coerceAtLeast(1))
        val elapsed = (hudTotalTicks - hudTicks).coerceAtLeast(0)
        val fade = min(1.0f, min(elapsed / fadeTicks.toFloat(), hudTicks / fadeTicks.toFloat()))
        val visibleIntensity = hudIntensity * fade

        when (type) {
            "darkening" -> {
                g.fill(0, 0, w, h, withAlpha(0x000000, (110 * visibleIntensity).toInt()))
            }

            "color_filter" -> {
                g.fill(0, 0, w, h, withAlpha(hudColor, (95 * visibleIntensity).toInt()))
            }

            "damaged_vision" -> {
                val base = 0xAA0000
                val layers = 6
                val depth = (h * 0.28 * visibleIntensity).toInt()
                for (i in 0 until layers) {
                    val d = depth * (layers - i) / layers
                    val alpha = ((18 * visibleIntensity * (i + 1)) * (0.6 + 0.6 * pulse)).toInt()
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
                val lid = (h * 0.30 * phase * visibleIntensity).toInt()
                g.fill(0, 0, w, lid, 0xFF000000.toInt())
                g.fill(0, h - lid, w, h, 0xFF000000.toInt())
                g.fill(0, 0, w, h, withAlpha(0x000000, (40 * visibleIntensity).toInt()))
            }

            "noise" -> {
                // Резервный вариант без шейдера: редкие «снежинки» (десятки fill вместо тысяч).
                // Полноценный шум выполняется на GPU через noisefx-цепочку.
                val frame = (System.currentTimeMillis() / 45).toInt()
                val rnd = java.util.Random(frame.toLong())
                val dots = (140 * visibleIntensity).toInt().coerceAtLeast(20)
                for (i in 0 until dots) {
                    val x = rnd.nextInt(w)
                    val y = rnd.nextInt(h)
                    val size = 1 + rnd.nextInt(3)
                    val tone = 110 + rnd.nextInt(146)
                    val alpha = ((50 + rnd.nextInt(60)) * visibleIntensity).toInt().coerceIn(0, 110)
                    g.fill(x, y, x + size, y + size, withAlpha(tone * 0x010101, alpha))
                }
            }
        }
    }

    fun active(): List<Status> = buildList {
        shaderName?.takeIf { shaderTicks > 0 }?.let { add(Status("screen:shader", it, shaderTicks, shaderTotalTicks)) }
        hudType?.takeIf { hudTicks > 0 }?.let { add(Status("screen:hud", it, hudTicks, hudTotalTicks)) }
    }

    fun cancel(id: String) {
        when (id) {
            "screen:shader" -> {
                shaderTicks = 0
                shaderId = null
                shaderName = null
                runCatching { Minecraft.getInstance().gameRenderer.clearPostEffect() }
            }
            "screen:hud" -> {
                hudTicks = 0
                hudType = null
            }
        }
    }

    fun cancelAll() {
        cancel("screen:shader")
        cancel("screen:hud")
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        (min(alpha, 255) shl 24) or (rgb and 0xFFFFFF)
}
