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
}
