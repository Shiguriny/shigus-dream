package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType

/** Принудительная перспектива камеры с возвратом исходной по таймеру (0 = не возвращать). */
object PerspectiveFx {
    private var restore: CameraType? = null
    private var ticksLeft = 0

    fun set(type: CameraType, durationTicks: Int) {
        val mc = Minecraft.getInstance()
        if (restore == null && durationTicks > 0) {
            restore = mc.options.getCameraType()
        }
        mc.options.setCameraType(type)
        ticksLeft = if (durationTicks > 0) durationTicks else -1
    }

    fun tick(mc: Minecraft) {
        if (ticksLeft > 0 && --ticksLeft == 0) {
            restore?.let { mc.options.setCameraType(it) }
            restore = null
        }
    }

    fun cancel() {
        restore?.let { Minecraft.getInstance().options.setCameraType(it) }
        restore = null
        ticksLeft = 0
    }
}
