package com.shigusdream

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionDispatcher
import com.shigusdream.actions.ActionRegistry
import com.shigusdream.actions.impl.NotificationAction
import com.shigusdream.actions.impl.PlaySoundAction
import com.shigusdream.actions.impl.ShowMessageAction
import com.shigusdream.admin.AdminScreen
import com.shigusdream.auth.AuthManager
import com.shigusdream.client.HudElements
import com.shigusdream.client.MessageOverlay
import com.shigusdream.config.ModConfig
import com.shigusdream.minecraft.CustomDataReader
import com.shigusdream.network.BackendConnection
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW

object ShigusDreamClient : ClientModInitializer {

    lateinit var config: ModConfig
        private set
    lateinit var auth: AuthManager
        private set
    lateinit var connection: BackendConnection
        private set
    val registry: ActionRegistry = ActionRegistry()

    private val adminCategory: KeyMapping.Category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ShigusDream.MOD_ID, "admin"))

    /** Последний результат отправленного действия (для админ-панели). */
    @Volatile
    var lastResultText: String = ""

    private lateinit var openAdminKey: KeyMapping
    private lateinit var statusKey: KeyMapping

    override fun onInitializeClient() {
        val configDir = FabricLoader.getInstance().configDir
        config = ModConfig.load(configDir)
        auth = AuthManager(configDir.resolve("shigusdream"), config.backendUrl)
        auth.load()

        registry.register(ShowMessageAction)
        registry.register(NotificationAction)
        registry.register(PlaySoundAction)

        connection = BackendConnection(
            wsUrl = ModConfig.websocketUrl(config.backendUrl),
            auth = auth,
            pingIntervalSeconds = config.pingIntervalSeconds,
        )
        connection.handler = ConnectionHandler()

        val dispatcher = ActionDispatcher(
            registry = registry,
            executor = { runnable -> Minecraft.getInstance().execute(runnable) },
            resultSink = { requestId, action, executed, error ->
                connection.sendResult(requestId, action, executed, error)
            },
        )
        ShigusDreamRuntime.dispatcher = dispatcher

        openAdminKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.shigusdream.admin_panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, adminCategory),
        )
        statusKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.shigusdream.status", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, adminCategory),
        )

        ClientTickEvents.END_CLIENT_TICK.register { client -> onEndTick(client) }
        HudElements.register()

        // Подключаемся при заходе в мир/на сервер — код привязки и статусы не теряются в меню,
        // а presence означает «игрок сейчас в игре». Выход из мира — разрыв соединения.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (config.autoConnect && connection.currentState == BackendConnection.State.DISCONNECTED) {
                connection.connect()
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            connection.disconnect()
        }

        ShigusDream.LOGGER.info("Shigu's Dream v{} инициализирован; backend={}", modVersion(), config.backendUrl)
    }

    // ------------------------------------------------------------------ tick

    private fun onEndTick(client: Minecraft) {
        while (openAdminKey.consumeClick()) {
            val player = client.player ?: continue
            if (config.requireAdminWand && !CustomDataReader.isAdminWand(player.getMainHandItem())) {
                chatFeedback("§c[Shigu's Dream]§7 Панель доступна только с предметом admin_wand")
                continue
            }
            if (!connection.isOnline) {
                chatFeedback("§c[Shigu's Dream]§7 Нет подключения к backend (переподключение — клавиша J)")
                continue
            }
            connection.requestPresence()
            lastResultText = ""
            client.setScreen(AdminScreen())
        }
        while (statusKey.consumeClick()) {
            when (connection.currentState) {
                BackendConnection.State.ONLINE ->
                    chatFeedback("§a[Shigu's Dream]§7 Онлайн как ${auth.tokens.username ?: "?"} (${ShigusDreamRuntime.presenceUsers.count { it.online }} в сети)")

                BackendConnection.State.DISCONNECTED -> {
                    chatFeedback("§e[Shigu's Dream]§7 Переподключение...")
                    connection.reconnect()
                }

                else -> chatFeedback("§e[Shigu's Dream]§7 Соединение: ${connection.currentState}")
            }
        }
        MessageOverlay.tick()
    }

    // ------------------------------------------------------------------ handlers

    private class ConnectionHandler : BackendConnection.Handler {

        override fun onConnected() {
            if (auth.hasRefreshToken) {
                connection.sendAuth()
                return
            }
            // Первичное привязывание: HTTP-запрос кода — вне MC-потока, код показываем в чате.
            Thread {
                val mc = Minecraft.getInstance()
                val mcUuid = mc.user?.profileId?.toString()
                val mcName = mc.user?.name
                if (mcUuid == null || mcName == null) {
                    chatFeedback("§c[Shigu's Dream]§7 Не удалось получить UUID профиля")
                    return@Thread
                }
                when (val outcome = auth.requestLinkCode(mcUuid, mcName)) {
                    is AuthManager.LinkOutcome.CodeIssued -> {
                        chatFeedback("§b[Shigu's Dream]§7 Код привязки: §e${outcome.code}§7 — откройте §9${outcome.confirmUrl}§7 и введите его (действует 15 минут)")
                        connection.sendAuthWithLinkCode(outcome.code)
                    }

                    is AuthManager.LinkOutcome.AlreadyLinked -> connection.sendAuth()

                    is AuthManager.LinkOutcome.Failed ->
                        chatFeedback("§c[Shigu's Dream]§7 ${outcome.error}")
                }
            }.apply { isDaemon = true }.start()
        }

        override fun onActionExecute(requestId: String, action: String, args: JsonObject, commandId: String?) {
            ShigusDreamRuntime.dispatcher?.handle(requestId, action, args)
        }

        override fun onPresence(users: List<BackendConnection.PresenceUser>) {
            ShigusDreamRuntime.presenceUsers = users
        }

        override fun onAuthSuccess(username: String?, refreshToken: String?, accessToken: String?) {
            auth.saveTokens(refreshToken ?: auth.tokens.refreshToken, accessToken, username)
            chatFeedback("§a[Shigu's Dream]§7 Подключено как $username")
        }

        override fun onAuthError(code: String, message: String) {
            if (code == "pending") {
                chatFeedback("§e[Shigu's Dream]§7 $message")
                return
            }
            if (code == "invalid_token" || code == "expired_token") {
                // Refresh-токен истёк/отозван — сбрасываем и просим привязать аккаунт заново по коду.
                auth.clear()
                chatFeedback("§e[Shigu's Dream]§7 Сессия недействительна ($message). Переподключитесь (J) — получите новый код привязки.")
            } else {
                chatFeedback("§c[Shigu's Dream]§7 Ошибка аутентификации: $message")
            }
            ShigusDream.LOGGER.warn("auth error: {} {}", code, message)
        }

        override fun onActionResult(requestId: String, action: String, status: String, error: String?) {
            lastResultText = if (status == "executed") "✔ $action выполнено" else "✖ ${error ?: "failed"}"
            chatFeedback("§7[Shigu's Dream] $lastResultText")
        }

        override fun onReconnectIn(seconds: Long) {
            val note = if (seconds >= 30) " (сервер мог уснуть — просыпается до минуты)" else ""
            chatFeedback("§7[Shigu's Dream] Переподключение через ${seconds}с$note")
        }

        override fun onStateChange(state: BackendConnection.State) {
            if (state == BackendConnection.State.ONLINE) {
                connection.requestPresence()
            }
        }
    }

    fun chatFeedback(message: String) {
        val client = Minecraft.getInstance()
        client.execute {
            client.gui.chat.addClientSystemMessage(Component.literal(message))
        }
    }

    fun modVersion(): String =
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(ShigusDream.MOD_ID).orElse(null)?.metadata?.version?.friendlyString ?: "?"
}

/** Runtime-состояние, доступное из экранов и колбэков. */
object ShigusDreamRuntime {
    var dispatcher: ActionDispatcher? = null
    var presenceUsers: List<BackendConnection.PresenceUser> = emptyList()
}
