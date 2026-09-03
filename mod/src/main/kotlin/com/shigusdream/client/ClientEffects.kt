package com.shigusdream.client

import net.minecraft.client.Minecraft

/**
 * Локальные клиентские эффекты с таймерами: FOV возвращается через заданное число тиков.
 */
object ClientEffects {

    private var fovRestore: Pair<Int, Int>? = null // сохранённый FOV к тикам до возврата

    fun setFovFor(target: Int, durationTicks: Int) {
        val mc = Minecraft.getInstance()
        if (fovRestore == null) {
            fovRestore = mc.options.fov().get() to durationTicks.coerceAtLeast(1)
        }
        mc.options.fov().set(target)
    }

    fun tick() {
        val pending = fovRestore ?: return
        val (saved, ticksLeft) = pending
        if (ticksLeft <= 1) {
            Minecraft.getInstance().options.fov().set(saved)
            fovRestore = null
        } else {
            fovRestore = saved to ticksLeft - 1
        }
    }
}
