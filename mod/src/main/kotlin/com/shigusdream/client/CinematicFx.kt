package com.shigusdream.client

import net.minecraft.client.Minecraft

/**
 * Кинематографический режим: чёрные полосы letterbox (шейдер), скрытие ванильного HUD,
 * лёгкое затемнение. По окончании всё восстанавливается.
 */
object CinematicFx {
    private var ticksLeft = 0

    val isActive: Boolean get() = ticksLeft > 0

    fun start(durationTicks: Int) {
        ticksLeft = durationTicks.coerceAtLeast(20)
        // Чёрные полосы letterbox — пост-цепочкой (HUD скрыт, шейдер продолжает работать).
        ScreenFx.apply("letterbox", ticksLeft, 1.0f, "#FFFFFF")
        Minecraft.getInstance().options.hideGui = true
    }

    fun cancel() {
        if (ticksLeft > 0) {
            ticksLeft = 0
            Minecraft.getInstance().options.hideGui = false
            ScreenFx.cancel("screen:shader")
        }
    }

    fun tick(mc: Minecraft) {
        if (ticksLeft <= 0) return
        if (--ticksLeft == 0) {
            mc.options.hideGui = false
            ScreenFx.cancel("screen:shader")
        }
    }
}
