package com.shigusdream.client

import com.shigusdream.ShigusDream
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * Позиционный/зацикленный эмбиент-звук: проигрывает soundId каждые interval тиков
 * в точке (для point) или на позиции игрока-цели (для follow) в течение duration.
 */
object AmbientAudio {

    private data class Loop(
        val soundId: String,
        val mode: String, // point | follow
        val pos: Vec3?,
        val interval: Int,
        val volume: Float,
        var ticksLeft: Int,   // до конца эффекта
        var countdown: Int,   // до следующего проигрывания
    )

    private val loops = mutableListOf<Loop>()

    fun play(soundId: String, mode: String, x: Double, y: Double, z: Double, interval: Int, duration: Int, volume: Float) {
        val mc = Minecraft.getInstance()
        val pos = if (mode == "point") Vec3(x, y, z) else mc.player?.position() ?: return
        val event = resolveSound(soundId) ?: run {
            ShigusDream.LOGGER.warn("ambient: неизвестный звук {}", soundId)
            return
        }
        loops += Loop(soundId, mode, pos.takeIf { mode == "point" }, interval.coerceAtLeast(1), volume.coerceIn(0f, 4f), duration.coerceAtLeast(1), 1)
        playOnce(event, pos, volume)
    }

    private fun resolveSound(soundId: String): net.minecraft.sounds.SoundEvent? =
        net.minecraft.resources.Identifier.tryParse(soundId)?.let { id ->
            net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(id).map { it.value() }.orElse(null)
        }

    private fun playOnce(event: net.minecraft.sounds.SoundEvent, pos: Vec3, volume: Float) {
        val mc = Minecraft.getInstance()
        mc.soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance(
                event, SoundSource.AMBIENT, volume, 1.0f,
                mc.player?.random ?: net.minecraft.util.RandomSource.create(),
                pos.x, pos.y, pos.z,
            ),
        )
    }

    fun tick(mc: Minecraft) {
        if (loops.isEmpty()) return
        val iter = loops.iterator()
        while (iter.hasNext()) {
            val loop = iter.next()
            if (--loop.countdown <= 0) {
                val pos = if (loop.mode == "follow") {
                    // follow-режим: в админ-сценариях точка фиксирована при запуске; для point-логики берём сохранённую.
                    loop.pos ?: mc.player?.position()
                } else loop.pos
                if (pos != null) {
                    resolveSound(loop.soundId)?.let { playOnce(it, pos, loop.volume) }
                }
                loop.countdown = loop.interval
            }
            if (--loop.ticksLeft <= 0) iter.remove()
        }
    }

    fun cancelAll() {
        loops.clear()
    }
}
