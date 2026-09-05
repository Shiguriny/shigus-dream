package com.shigusdream.client

data class ActiveEffectInfo(
    val id: String,
    val name: String,
    val remainingTicks: Int,
    val totalTicks: Int,
)

object ActiveEffects {
    fun snapshot(): List<ActiveEffectInfo> = buildList {
        ScreenFx.active().forEach { add(ActiveEffectInfo(it.id, it.effect, it.remainingTicks, it.totalTicks)) }
        if (ClientEffects.fovRemainingTicks > 0) {
            add(ActiveEffectInfo("client:fov", "FOV", ClientEffects.fovRemainingTicks, ClientEffects.fovDurationTicks))
        }
        if (ClientControls.remainingTicks > 0) {
            add(ActiveEffectInfo("client:freeze", "freeze_controls", ClientControls.remainingTicks, ClientControls.remainingTicks))
        }
    }

    fun cancel(id: String) {
        when (id) {
            "client:fov" -> ClientEffects.cancelFov()
            "client:freeze" -> ClientControls.unfreeze()
            else -> ScreenFx.cancel(id)
        }
    }

    fun cancelAll() {
        ScreenFx.cancelAll()
        ClientEffects.cancelFov()
        ClientControls.unfreeze()
    }
}
