package com.shigusdream.client

import net.minecraft.client.Minecraft
import kotlin.math.sin

/**
 * Тряска камеры: вращение игрока колеблется вокруг базовых углов.
 * style: "fine" (мелкая дрожь) или "wide" (медленные широкие качания).
 */
object CameraShake {
    private var ticksLeft = 0
    private var intensity = 0.5f
    private var style = "fine"
    private var baseYaw = 0f
    private var basePitch = 0f
    private var active = false

    fun start(durationTicks: Int, intensityLevel: Float, style: String) {
        val player = Minecraft.getInstance().player ?: return
        baseYaw = player.yRot
        basePitch = player.xRot
        this.intensity = intensityLevel.coerceIn(0.1f, 3f)
        this.style = if (style == "wide") "wide" else "fine"
        ticksLeft = durationTicks.coerceIn(10, 1200)
        active = true
    }

    /** Вызывается каждый кадр из HUD-фазы. */
    fun frame(mc: Minecraft) {
        if (!active) return
        val player = mc.player ?: run { stop(); return }
        val t = (System.currentTimeMillis() % 100000) / 1000.0
        val amp = 6.0 * intensity
        if (style == "wide") {
            player.setYRot(baseYaw + (Math.sin(t * 6.0) * amp).toFloat())
            player.setXRot(basePitch + (Math.sin(t * 4.1) * amp * 0.6).toFloat())
        } else {
            player.setYRot(baseYaw + (Math.sin(t * 31.0) * amp * 0.25 + (Math.random() - 0.5) * amp * 0.3).toFloat())
            player.setXRot(basePitch + (Math.sin(t * 37.3) * amp * 0.2 + (Math.random() - 0.5) * amp * 0.2).toFloat())
        }
        if (--ticksLeft <= 0) stop()
    }

    private fun stop() {
        val mc = Minecraft.getInstance()
        if (active) {
            mc.player?.let {
                it.setYRot(baseYaw)
                it.setXRot(basePitch)
            }
        }
        active = false
        ticksLeft = 0
    }
}
