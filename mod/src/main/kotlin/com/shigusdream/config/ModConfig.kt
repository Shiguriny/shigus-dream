package com.shigusdream.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

data class ModConfig(
    /** Базовый URL backend: http://host:port (ws/wss и путь /ws подставляются автоматически). */
    val backendUrl: String = "http://localhost:8080",
    val autoConnect: Boolean = true,
    val showHud: Boolean = true,
    /** Открывать админ-панель только держа предмет с custom_data shigusdream:{type:control_item,id:admin_wand}. */
    val requireAdminWand: Boolean = false,
    val pingIntervalSeconds: Int = 20,
) {
    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(configDir: Path): ModConfig {
            val file = configDir.resolve("shigusdream.json")
            return try {
                if (Files.exists(file)) {
                    GSON.fromJson(Files.readString(file), ModConfig::class.java) ?: ModConfig()
                } else {
                    val defaults = ModConfig()
                    Files.createDirectories(configDir)
                    Files.writeString(file, GSON.toJson(defaults))
                    defaults
                }
            } catch (e: Exception) {
                com.shigusdream.ShigusDream.LOGGER.warn("Не удалось прочитать shigusdream.json, используются настройки по умолчанию", e)
                ModConfig()
            }
        }

        /** Базовый URL → адрес WebSocket (/ws). */
        fun websocketUrl(baseUrl: String): String {
            val withScheme = when {
                baseUrl.startsWith("wss://") || baseUrl.startsWith("ws://") -> baseUrl
                baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
                baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
                else -> "ws://$baseUrl"
            }
            val noSlash = withScheme.trimEnd('/')
            return if (noSlash.endsWith("/ws")) noSlash else "$noSlash/ws"
        }
    }
}
