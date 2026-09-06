package com.shigusdream.mixin;

import com.shigusdream.client.HiddenEntities;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Скрывает сущности из рендера по UUID (модель, ник, частицы — всё исчезает). */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void shigusdream$hideEntity(E entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (HiddenEntities.INSTANCE.isHidden(entity.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
