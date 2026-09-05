package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Input

/**
 * Заморозка управления подавляет ввод до и после игрового тика. Одного END-tick
 * недостаточно: KeyboardInput успевал применить краткий импульс движения/прыжка.
 * Esc остаётся гарантированным аварийным выходом через меню паузы.
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
    val remainingTicks: Int get() = freezeTicksLeft

    fun freeze(durationTicks: Int, allowEsc: Boolean) {
        freezeTicksLeft = durationTicks.coerceAtLeast(1)
        freezeAllowEsc = allowEsc
        suppressInput(Minecraft.getInstance())
    }

    fun unfreeze() {
        freezeTicksLeft = 0
        releaseInput(Minecraft.getInstance())
    }

    /** Вызывается до Minecraft.tick: не даёт KeyboardInput увидеть нажатия. */
    fun beginTick(mc: Minecraft) {
        if (freezeTicksLeft <= 0) return
        suppressInput(mc)
    }

    /** Вызывается после Minecraft.tick: убирает накопленные клики и ведёт таймер. */
    fun endTick(mc: Minecraft) {
        if (freezeTicksLeft <= 0) return

        val screen = mc.screen
        if (screen != null) {
            val isPause = screen.javaClass.simpleName == "PauseScreen"
            if (isPause && freezeAllowEsc) {
                // Стандартный безопасный выход: Esc снимает freeze, если это разрешено аргументом.
                unfreeze()
                releaseInput(mc)
                return
            } else {
                mc.setScreen(null) // инвентарь/чат и (при allowEsc=false) паузу закрываем
            }
        }

        suppressInput(mc)
        freezeTicksLeft--
        if (freezeTicksLeft <= 0) releaseInput(mc)
    }

    /** Полностью сбрасывает и KeyMapping, и уже вычисленный Player input. */
    private fun suppressInput(mc: Minecraft) {
        val options = mc.options
        for (name in BLOCKED_KEYS) {
            runCatching {
                val field = options.javaClass.getField(name)
                val mapping = field.get(options) as net.minecraft.client.KeyMapping
                mapping.setDown(false)
                while (mapping.consumeClick()) {
                    // Очищаем clickCount: атака/использование/прыжок не должны
                    // выполниться позже одним пакетом после завершения эффекта.
                }
            }
        }

        mc.player?.let { player ->
            player.input.keyPresses = Input.EMPTY
            // Пересчитывает moveVector уже с погашенными KeyMapping.
            player.input.tick()
            player.input.keyPresses = Input.EMPTY
            player.xxa = 0.0f
            player.yya = 0.0f
            player.zza = 0.0f
            player.setJumping(false)

            // Убираем остаточную горизонтальную инерцию от ввода до freeze,
            // сохраняя гравитацию, падение и вертикальные серверные воздействия.
            val motion = player.deltaMovement
            player.setDeltaMovement(0.0, motion.y, 0.0)
        }
    }

    private fun releaseInput(mc: Minecraft) {
        mc.player?.let { player ->
            player.input.keyPresses = Input.EMPTY
            player.xxa = 0.0f
            player.yya = 0.0f
            player.zza = 0.0f
            player.setJumping(false)
        }
    }
}

/**
 * Локальные клиентские эффекты с таймерами: FOV возвращается через заданное число тиков.
 */
object ClientEffects {

    private var fovRestore: Pair<Int, Int>? = null // сохранённый FOV к тикам до возврата
    private var fovTotalTicks = 0

    val fovRemainingTicks: Int get() = fovRestore?.second ?: 0
    val fovDurationTicks: Int get() = fovTotalTicks

    fun setFovFor(target: Int, durationTicks: Int) {
        val mc = Minecraft.getInstance()
        if (fovRestore == null) {
            fovRestore = mc.options.fov().get() to durationTicks.coerceAtLeast(1)
        }
        fovTotalTicks = durationTicks.coerceAtLeast(1)
        mc.options.fov().set(target)
    }

    fun tick() {
        val pending = fovRestore ?: return
        val (saved, ticksLeft) = pending
        if (ticksLeft <= 1) {
            Minecraft.getInstance().options.fov().set(saved)
            fovRestore = null
            fovTotalTicks = 0
        } else {
            fovRestore = saved to ticksLeft - 1
        }
    }

    fun cancelFov() {
        val saved = fovRestore?.first ?: return
        Minecraft.getInstance().options.fov().set(saved)
        fovRestore = null
        fovTotalTicks = 0
    }
}
