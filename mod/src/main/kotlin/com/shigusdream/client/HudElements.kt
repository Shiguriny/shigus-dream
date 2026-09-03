package com.shigusdream.client

import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier

/** Регистрация HUD-элементов: строка статуса соединения и оверлей сообщений. */
object HudElements {

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, "overlay"),
        ) { g, _ ->
            if (ShigusDreamClient.config.showHud) {
                val (stateText, stateColor) = when (ShigusDreamClient.connection.currentState) {
                    com.shigusdream.network.BackendConnection.State.ONLINE -> "Online" to 0x55FF55
                    com.shigusdream.network.BackendConnection.State.DISCONNECTED -> "Offline" to 0xFF5555
                    else -> "Connecting" to 0xFFD76E
                }
                val version = ShigusDreamClient.modVersion()
                val line = Component.literal("[Shigu's Dream v$version] ")
                    .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }
                    .append(Component.literal(stateText).withStyle { it.withColor(TextColor.fromRgb(stateColor)) })
                g.text(Minecraft.getInstance().font, line, 4, 4, -1) // цвет задан стилями компонента
            }
            MessageOverlay.render(g)
        }
    }
}
