package com.shigusdream.mixin;

import com.shigusdream.client.ClientControls;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Не позволяет мыши поворачивать камеру, пока управление заморожено. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void shigusdream$freezeCamera(double frameTime, CallbackInfo ci) {
        if (ClientControls.INSTANCE.isFrozen()) {
            ci.cancel();
        }
    }
}
