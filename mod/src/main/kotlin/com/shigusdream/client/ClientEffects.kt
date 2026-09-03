package com.shigusdream.client

import net.minecraft.client.Minecraft

/**
 * Заморозка управления: каждый тик гасит блокируемые клавиши; по истечении duration снимается.
 * Esc остаётся доступен (при allowEsc — пауза размораживает, иначе — тоже снимает заморозку).
 */
object ClientControls {

    private val BLOCKED_KEYS = listOf(
        "keyUp", "keyLeft", "keyDown", "keyRight", "keyJump", "keySprint", "keyShift",
        "keyInventory", "keyDrop", "keySwapOffhand", "keyUse", "keyAttack", "keyPickItem",
        "keyChat", "keyCommand", "keyPlayerList", "keySocialInteractions", "keyAdvancements",
    )

    private var freezeTicksLeft = 0
    private var freezeAllowEsc = true

    val isFrozen: Boolean get() = freezeTicksLeft > 0

    fun freeze(durationTicks: Int, allowEsc: Boolean) {
        freezeTicksLeft = durationTicks.coerceAtLeast(1)
        freezeAllowEsc = allowEsc
    }

    fun unfreeze() {
        freezeTicksLeft = 0
    }

    fun tick(mc: Minecraft) {
        if (freezeTicksLeft <= 0) return

        val screen = mc.screen
        if (screen != null) {
            val isPause = screen.javaClass.simpleName == "PauseScreen"
            if (isPause) {
                unfreeze() // меню паузы — гарантированный выход из заморозки
            } else {
                mc.setScreen(null) // инвентарь/чат и т.п. закрываем
            }
        }

        val options = mc.options
        for (name in BLOCKED_KEYS) {
            runCatching {
                val field = options.javaClass.getField(name)
                (field.get(options) as net.minecraft.client.KeyMapping).setDown(false)
            }
        }

        freezeTicksLeft--
    }
}

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
