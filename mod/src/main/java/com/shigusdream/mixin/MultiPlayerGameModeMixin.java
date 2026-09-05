package com.shigusdream.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ваниль вызывает gameMode.tick() при level != null без проверки player,
 * из-за чего в окне логина/дисконнекта возможен NPE в ensureHasSentCarriedItem.
 * Пропускаем тик gameMode, пока игрока нет.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void shigusdream$skipWithoutPlayer(CallbackInfo ci) {
        if (Minecraft.getInstance().player == null) {
            ci.cancel();
        }
    }
}
