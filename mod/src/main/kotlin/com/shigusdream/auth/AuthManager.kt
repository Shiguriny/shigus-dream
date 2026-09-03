package com.shigusdream.auth

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shigusdream.ShigusDream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class StoredTokens(
    var refreshToken: String? = null,
    var accessToken: String? = null,
    var username: String? = null,
)

/**
 * UUID — идентификатор, но не секрет. Секрет владения — одноразовый код привязки
 * (подтверждается на HTML-странице backend) и refresh-токен, хранящийся локально.
 */
class AuthManager(internal val configDir: Path, internal val baseUrl: String) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    internal val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val tokens = StoredTokens()

    sealed interface LinkOutcome {
        data class CodeIssued(val code: String, val confirmUrl: String) : LinkOutcome
        data class AlreadyLinked(val refreshToken: String?) : LinkOutcome
        data class Failed(val error: String) : LinkOutcome
    }

    fun load() {
        try {
            val file = tokensFile()
            if (Files.exists(file)) {
                val stored = gson.fromJson(Files.readString(file), StoredTokens::class.java)
                if (stored != null) {
                    tokens.refreshToken = stored.refreshToken
                    tokens.accessToken = stored.accessToken
                    tokens.username = stored.username
                }
            }
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Не удалось прочитать tokens.json", e)
        }
    }

    fun saveTokens(refreshToken: String?, accessToken: String?, username: String?) {
        tokens.refreshToken = refreshToken
        tokens.accessToken = accessToken
        tokens.username = username
        try {
            Files.createDirectories(configDir)
            Files.writeString(tokensFile(), gson.toJson(tokens))
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Не удалось сохранить tokens.json", e)
        }
    }

    fun clear() = saveTokens(null, null, null)

    val hasRefreshToken: Boolean get() = !tokens.refreshToken.isNullOrBlank()

    /**
     * Первый шаг привязки: просим backend выдать одноразовый код для этого UUID.
     * Код пользователь подтверждает на странице backend, после чего WS-auth завершится.
     * Если UUID уже привязан, а токены потеряны — нужен recoverySecret (см. ModConfig).
     */
    fun requestLinkCode(mcUuid: String, mcName: String, recoverySecret: String = ""): LinkOutcome {
        if (hasRefreshToken) return LinkOutcome.AlreadyLinked(tokens.refreshToken)
        return try {
            val body = gson.toJson(
                mapOf(
                    "mc_uuid" to mcUuid,
                    "mc_name" to mcName,
                    "recovery_secret" to recoverySecret.takeIf { it.isNotBlank() },
                ),
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + "/auth/link"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val json = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
                    LinkOutcome.CodeIssued(
                        code = json.get("link_code").asString,
                        confirmUrl = baseUrl.trimEnd('/') + (json.get("confirm_url")?.asString ?: "/link"),
                    )
                }

                409 -> LinkOutcome.Failed(
                    "UUID уже привязан, а токены потеряны. Впишите SHIGU_RECOVERY_SECRET из панели Render в config/shigusdream.json (recoverySecret) и перезапустите игру",
                )

                else -> LinkOutcome.Failed("backend вернул ${response.statusCode()}: ${response.body().take(200)}")
            }
        } catch (e: Exception) {
            LinkOutcome.Failed("Не удалось связаться с backend: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Авторизованный вызов admin-API. Возвращает (status, body); status = -1 при сетевой ошибке, -2 если нет токена. */
    fun adminApi(method: String, path: String, body: String? = null): Pair<Int, String?> {
        val token = tokens.accessToken ?: return -2 to null
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
            if (body != null) {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.GET()
            }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun tokensFile(): Path = configDir.resolve("tokens.json")
}
