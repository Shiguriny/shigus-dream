package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.actions.ActionResult
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * shigusdream:play_sound — идентификатор обязан существовать в реестре SoundEvent.
 */
object SoundPlayback {

    fun play(soundId: String, volume: Float, pitch: Float): ActionResult {
        val client = Minecraft.getInstance()
        val identifier = Identifier.tryParse(soundId) ?: return ActionResult.fail("invalid_identifier: $soundId")

        val soundEvent = BuiltInRegistries.SOUND_EVENT.get(identifier)
            .map { it.value() }
            .orElse(null)
            ?: return ActionResult.fail("unknown_sound: $soundId")

        client.soundManager.play(SimpleSoundInstance.forUI(soundEvent, pitch, volume))
        ShigusDream.LOGGER.info("sound {} played", soundId)
        return ActionResult.ok()
    }

    /** Позиционный звук в точке мира. */
    fun playAt(soundId: String, x: Double, y: Double, z: Double, volume: Float): Boolean {
        val client = Minecraft.getInstance()
        val identifier = Identifier.tryParse(soundId) ?: return false
        val soundEvent = BuiltInRegistries.SOUND_EVENT.get(identifier)
            .map { it.value() }
            .orElse(null) ?: return false
        val player = client.player ?: return false
        client.soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance(
                soundEvent, net.minecraft.sounds.SoundSource.MASTER, volume, 1.0f,
                player.random, x, y, z,
            ),
        )
        return true
    }
}
