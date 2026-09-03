package com.shigusdream.config

import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.fabricmc.loader.api.FabricLoader

/**
 * Внутриигровые настройки мода: адрес backend, автоподключение, HUD,
 * требование admin_wand, ping, recoverySecret. Сохраняет в shigusdream.json.
 */
class ConfigScreen(private val parent: Screen? = null) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Настройки")) {

    private companion object {
        const val LEFT = 8
        const val W = 240
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_WHITE = -1
    }

    private var urlBox: EditBox? = null
    private var pingBox: EditBox? = null
    private var secretBox: EditBox? = null
    private var statusLine: String = ""
    private var statusColor: Int = 0xFF55FF55.toInt()

    // Состояния тогглов (виджеты меняют их напрямую)
    private var autoConnect: Boolean = ShigusDreamClient.config.autoConnect
    private var showHud: Boolean = ShigusDreamClient.config.showHud
    private var requireAdminWand: Boolean = ShigusDreamClient.config.requireAdminWand

    override fun init() {
        val cfg = ShigusDreamClient.config

        urlBox = EditBox(font, LEFT, 36, W, 16, Component.literal("backendUrl")).apply {
            setMaxLength(256)
            setValue(cfg.backendUrl)
            setHint(Component.literal("https://shigusdream-backend.onrender.com"))
        }
        addRenderableWidget(urlBox!!)

        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 62, W, 16, "Автоподключение при входе в мир", autoConnect) { v -> autoConnect = v },
        )
        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 86, W, 16, "Показывать строку статуса (HUD)", showHud) { v -> showHud = v },
        )
        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 110, W, 16, "Панель только с admin_wand", requireAdminWand) { v -> requireAdminWand = v },
        )

        pingBox = EditBox(font, LEFT, 146, 60, 16, Component.literal("ping")).apply {
            setMaxLength(3)
            setValue(cfg.pingIntervalSeconds.toString())
        }
        addRenderableWidget(pingBox!!)

        secretBox = EditBox(font, LEFT, 180, W, 16, Component.literal("recoverySecret")).apply {
            setMaxLength(128)
            setValue(cfg.recoverySecret)
            setHint(Component.literal("SHIGU_RECOVERY_SECRET с сервера"))
        }
        addRenderableWidget(secretBox!!)

        addRenderableWidget(
            com.shigusdream.admin.UiButton(LEFT, height - 50, W, 20, "Сохранить", { saveAndClose() }, true),
        )
        addRenderableWidget(
            com.shigusdream.admin.UiButton(LEFT, height - 26, W, 20, "Назад", { onClose() }),
        )
    }

    private fun saveAndClose() {
        val url = urlBox?.getValue()?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ws://") && !url.startsWith("wss://")) {
            statusLine = "Адрес должен начинаться с http(s):// или ws(s)://"
            statusColor = 0xFFFF5555.toInt()
            return
        }
        val ping = pingBox?.getValue()?.trim()?.toIntOrNull() ?: ShigusDreamClient.config.pingIntervalSeconds
        if (ping !in 5..120) {
            statusLine = "Ping-интервал: 5..120 секунд"
            statusColor = 0xFFFF5555.toInt()
            return
        }

        val oldUrl = ShigusDreamClient.config.backendUrl
        val newConfig = ShigusDreamClient.config.copy(
            backendUrl = url,
            autoConnect = autoConnect,
            showHud = showHud,
            requireAdminWand = requireAdminWand,
            pingIntervalSeconds = ping,
            recoverySecret = secretBox?.getValue()?.trim().orEmpty(),
        )
        val configDir = FabricLoader.getInstance().configDir
        ModConfig.save(configDir, newConfig)
        ShigusDreamClient.applyConfig(newConfig)
        ShigusDream.LOGGER.info("Настройки сохранены; backend={}", url)

        if (url != oldUrl) {
            statusLine = "Сохранено — переподключение..."
            statusColor = 0xFF55FF55.toInt()
            ShigusDreamClient.connection.updateUrl(ModConfig.websocketUrl(url))
            ShigusDreamClient.connection.reconnect()
        } else {
            onClose()
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        g.text(font, title, LEFT, 12, C_WHITE)
        val versionLine = "[Shigu's Dream v${ShigusDreamClient.modVersion()}]"
        g.text(font, Component.literal(versionLine), width - font.width(versionLine) - 4, 6, 0xFFB088FF.toInt())

        g.text(font, Component.literal("Адрес backend"), LEFT, 26, C_LABEL)
        g.text(font, Component.literal("Ping-интервал, с (5..120)"), LEFT, 136, C_LABEL)
        g.text(font, Component.literal("Секрет восстановления (необязательно)"), LEFT, 170, C_LABEL)
        if (statusLine.isNotBlank()) {
            g.text(font, Component.literal(statusLine), LEFT, height - 84, statusColor)
        }
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }
}
