package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** Регистрация HUD-элементов: строка статуса соединения и оверлей сообщений. */
object HudElements {

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, "overlay"),
        ) { g, _ ->
            if (ShigusDreamClient.config.showHud) {
                val state = when (ShigusDreamClient.connection.currentState) {
                    com.shigusdream.network.BackendConnection.State.ONLINE -> "§aOnline"
                    com.shigusdream.network.BackendConnection.State.DISCONNECTED -> "§cOffline"
                    else -> "§eConnecting"
                }
                g.text(Minecraft.getInstance().font, "§d[Shigu's Dream] $state", 4, 4, 0xFFFFFF)
            }
            MessageOverlay.render(g)
        }
    }
}
