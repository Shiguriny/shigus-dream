package com.shigusdream.config

import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import com.shigusdream.admin.UiTextField
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.fabricmc.loader.api.FabricLoader

/**
 * Внутриигровые настройки мода: адрес backend, автоподключение, HUD,
 * требование admin_wand, ping, recoverySecret. Сохраняет в shigusdream.json.
 */
class ConfigScreen(private val parent: Screen? = null) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.config.title")) {

    private companion object {
        const val LEFT = 8
        const val W = 240
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_WHITE = -1
    }

    private var urlBox: UiTextField? = null
    private var pingBox: UiTextField? = null
    private var secretBox: UiTextField? = null
    private var statusLine: Component? = null
    private var statusColor: Int = 0xFF55FF55.toInt()

    // Состояния тогглов (виджеты меняют их напрямую)
    private var autoConnect: Boolean = ShigusDreamClient.config.autoConnect
    private var showHud: Boolean = ShigusDreamClient.config.showHud
    private var requireAdminWand: Boolean = ShigusDreamClient.config.requireAdminWand

    override fun init() {
        clearWidgets()
        val cfg = ShigusDreamClient.config

        urlBox = UiTextField(font, LEFT + 5, 41, W - 10, 10, Component.literal("backendUrl")).apply {
            setMaxLength(256)
            setValue(cfg.backendUrl)
            setHint(Component.literal("https://backend.example.com"))
        }
        addRenderableWidget(urlBox!!)

        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 64, W, 16, I18n.component("shigusdream.config.auto_connect"), autoConnect) { v -> autoConnect = v },
        )
        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 88, W, 16, I18n.component("shigusdream.config.show_hud"), showHud) { v -> showHud = v },
        )
        addRenderableWidget(
            com.shigusdream.admin.UiToggle(LEFT, 112, W, 16, I18n.component("shigusdream.config.require_wand"), requireAdminWand) { v -> requireAdminWand = v },
        )

        addRenderableWidget(com.shigusdream.admin.UiButton(LEFT, 136, W, 20,
            I18n.component("shigusdream.config.safety"), { Minecraft.getInstance().setScreen(SafetyScreen(this)) }))

        pingBox = UiTextField(font, LEFT + 5, 177, 50, 10, Component.literal("ping")).apply {
            setMaxLength(3)
            setValue(cfg.pingIntervalSeconds.toString())
        }
        addRenderableWidget(pingBox!!)

        secretBox = UiTextField(font, LEFT + 5, 213, W - 10, 10, Component.literal("recoverySecret")).apply {
            setMaxLength(128)
            setValue(cfg.recoverySecret)
            setHint(I18n.component("shigusdream.config.secret_hint"))
        }
        addRenderableWidget(secretBox!!)

        addRenderableWidget(
            com.shigusdream.admin.UiButton(LEFT, height - 50, W, 20, I18n.component("shigusdream.common.save"), { saveAndClose() }, true),
        )
        addRenderableWidget(
            com.shigusdream.admin.UiButton(LEFT, height - 26, W, 20, I18n.component("shigusdream.common.back"), { onClose() }),
        )
    }

    private fun saveAndClose() {
        val url = urlBox?.getValue()?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ws://") && !url.startsWith("wss://")) {
            statusLine = I18n.component("shigusdream.config.invalid_url")
            statusColor = 0xFFFF5555.toInt()
            return
        }
        val ping = pingBox?.getValue()?.trim()?.toIntOrNull() ?: ShigusDreamClient.config.pingIntervalSeconds
        if (ping !in 5..120) {
            statusLine = I18n.component("shigusdream.config.invalid_ping")
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
            statusLine = I18n.component("shigusdream.config.reconnecting")
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

        g.text(font, I18n.component("shigusdream.config.backend_url"), LEFT, 26, C_LABEL)
        g.text(font, I18n.component("shigusdream.config.ping"), LEFT, 163, C_LABEL)
        g.text(font, I18n.component("shigusdream.config.secret"), LEFT, 199, C_LABEL)
        statusLine?.let {
            g.text(font, it, LEFT, height - 84, statusColor)
        }
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(parent)
    }
}
