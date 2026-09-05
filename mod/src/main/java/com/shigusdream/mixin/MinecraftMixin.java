package com.shigusdream.mixin;

import com.shigusdream.client.Highlight;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Игроки из списка Highlight получают ванильный glow-контур. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void shigusdream$highlightGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Highlight.INSTANCE.isGlowing(entity.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
