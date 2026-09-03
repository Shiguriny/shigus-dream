package com.shigusdream.backend.api

import com.shigusdream.backend.action.ActionRegistry
import com.shigusdream.backend.action.ActionSpec
import com.shigusdream.backend.auth.LinkCodeService
import com.shigusdream.backend.auth.PermissionService
import com.shigusdream.backend.auth.TokenService
import com.shigusdream.backend.command.CommandService
import com.shigusdream.backend.protocol.ErrorCode
import com.shigusdream.backend.repository.Command
import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.User
import com.shigusdream.backend.repository.UserRepository
import com.shigusdream.backend.websocket.WsManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class AuthLinkRequest(
    @SerialName("mc_uuid") val mcUuid: String? = null,
    @SerialName("mc_name") val mcName: String? = null,
    @SerialName("recovery_secret") val recoverySecret: String? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String? = null)

@Serializable
data class RoleRequest(val role: String)

@Serializable
data class PermissionRequest(val permission: String)

@Serializable
data class ErrorResponse(val error: String, val message: String? = null)

@Serializable
data class CommandDto(
    val id: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("action_id") val actionId: String,
    val payload: JsonObject,
    val mode: String,
    val status: String,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("executed_at") val executedAt: String? = null,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private suspend fun ApplicationCall.respondJson(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(text, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String? = null) =
    respondJson(json.encodeToString(ErrorResponse.serializer(), ErrorResponse(code, message)), status)

class HttpRoutes(
    private val users: UserRepository,
    private val linkCodes: LinkCodeRepository,
    private val tokenService: TokenService,
    private val permissions: PermissionService,
    private val linkCodeService: LinkCodeService,
    private val commandService: CommandService,
    private val wsManager: WsManager,
    private val recoverySecret: String? = null,
) {
    fun register(route: Route) = with(route) {
        post("/auth/link") { handleAuthLink(call) }
        get("/link") { call.respondText(linkPage(), ContentType.Text.Html) }
        post("/link") { handleLinkConfirm(call) }
        post("/auth/refresh") { handleRefresh(call) }

        get("/health") { call.respondJson("""{"status":"ok"}""") }

        get("/actions") {
            call.respondJson(json.encodeToString(ListSerializer(ActionSpec.serializer()), ActionRegistry.ACTIONS))
        }

        get("/users") { handleUsersList(call) }
        get("/users/{id}") { handleUserGet(call) }
        post("/users/{id}/role") { handleUserRole(call) }
        post("/users/{id}/permissions") { handlePermissionAdd(call) }
        delete("/users/{id}/permissions/{permission}") { handlePermissionRemove(call) }

        get("/commands/{id}") { handleCommandGet(call) }
        post("/commands/{id}/cancel") { handleCommandCancel(call) }
    }

    // ------------------------------------------------------------------ auth

    private suspend fun handleAuthLink(call: ApplicationCall) {
        val body = try {
            json.decodeFromString(AuthLinkRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "malformed_request", "Ожидается JSON {mc_uuid, mc_name}")
            return
        }
        val uuid = body.mcUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuid == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "mc_uuid должен быть валидным UUID")
            return
        }
        val name = body.mcName?.trim().orEmpty()
        if (name.isEmpty() || name.length > 16) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "mc_name обязателен (до 16 символов)")
            return
        }
        val boundUser = users.byMcUuid(uuid)
        if (boundUser != null) {
            // UUID уже привязан: обычным путём отказ, с верным recovery-секретом — код восстановления.
            val force = recoverySecret != null && body.recoverySecret == recoverySecret
            if (!force) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    ErrorCode.UUID_ALREADY_LINKED,
                    "UUID уже привязан к аккаунту '${boundUser.username}'. Для восстановления задайте SHIGU_RECOVERY_SECRET и пришлите его в recovery_secret",
                )
                return
            }
            val code = linkCodeService.createCode(uuid, name, force = true)
            call.respondJson("""{"link_code":"$code","expires_in":${TokenService.LINK_CODE_TTL_SECONDS},"confirm_url":"/link","force":true}""")
            return
        }
        val code = linkCodeService.createCode(uuid, name)
        call.respondJson(
            """{"link_code":"$code","expires_in":${TokenService.LINK_CODE_TTL_SECONDS},"confirm_url":"/link"}""",
        )
    }

    private suspend fun handleLinkConfirm(call: ApplicationCall) {
        val params = call.receiveParameters()
        val code = params["code"]?.trim().orEmpty()
        val username = params["username"]?.trim().orEmpty()

        if (!username.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            call.respondText(page("Ошибка", "Имя аккаунта: 3-16 символов, латиница/цифры/подчёркивание."), ContentType.Text.Html, HttpStatusCode.BadRequest)
            return
        }

        when (val result = linkCodeService.confirm(code, username)) {
            is LinkCodeService.ConfirmResult.Ok -> {
                wsManager.onLinkConfirmed(code.uppercase(), result.userId)
                call.respondText(page("Готово!", "Аккаунт <b>$username</b> привязан. Вернитесь в игру — подключение завершится автоматически."), ContentType.Text.Html)
            }

            is LinkCodeService.ConfirmResult.Failed ->
                call.respondText(page("Ошибка", result.message), ContentType.Text.Html, HttpStatusCode.BadRequest)
        }
    }

    private suspend fun handleRefresh(call: ApplicationCall) {
        val body = try {
            json.decodeFromString(RefreshRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "malformed_request", "Ожидается JSON {refresh_token}")
            return
        }
        val userId = body.refreshToken?.let { tokenService.validate(it, "refresh") }
        val user = userId?.let { users.byId(it) }
        if (user == null) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCode.INVALID_TOKEN, "Refresh-токен недействителен")
            return
        }
        call.respondJson(
            """{"access_token":"${tokenService.issueAccessToken(user)}","token_type":"Bearer","expires_in":${TokenService.ACCESS_TTL.toSeconds()}}""",
        )
    }

    // ------------------------------------------------------------------ users

    private suspend fun handleUsersList(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val arr = users.all().joinToString(",") { u ->
            """{"id":"${u.id}","username":"${u.username}","role":"${u.role}","mc_uuid":${u.mcUuid?.let { "\"$it\"" } ?: "null"},"online":${wsManager.isOnline(u.id)}}"""
        }
        call.respondJson("""{"users":[$arr]}""")
    }

    private suspend fun handleUserGet(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val user = call.routeUser() ?: return
        val perms = permissions.explicitPermissions(user).joinToString(",") { "\"$it\"" }
        call.respondJson(
            """{"id":"${user.id}","username":"${user.username}","role":"${user.role}","mc_uuid":${user.mcUuid?.let { "\"$it\"" } ?: "null"},"online":${wsManager.isOnline(user.id)},"permissions":[$perms]}""",
        )
    }

    private suspend fun handleUserRole(call: ApplicationCall) {
        val caller = call.authorizedAdmin() ?: return
        if (!caller.isOwner) {
            call.respondError(HttpStatusCode.Forbidden, ErrorCode.NO_PERMISSION, "Управлять ролями может только владелец (owner)")
            return
        }
        val user = call.routeUser() ?: return
        if (user.id == caller.id) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "Нельзя менять собственную роль")
            return
        }
        val role = try {
            json.decodeFromString(RoleRequest.serializer(), call.receiveText()).role
        } catch (_: Exception) {
            ""
        }
        if (role != "admin" && role != "user") {
            call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "role должен быть 'admin' или 'user'")
            return
        }
        users.setRole(user, role)
        call.respondJson("""{"status":"ok","username":"${user.username}","role":"${user.role}"}""")
    }

    private suspend fun handlePermissionAdd(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val user = call.routeUser() ?: return
        val permission = try {
            json.decodeFromString(PermissionRequest.serializer(), call.receiveText()).permission
        } catch (_: Exception) {
            ""
        }
        if (permission.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "permission обязателен")
            return
        }
        users.addPermission(user, permission)
        call.respondJson("""{"status":"ok"}""")
    }

    private suspend fun handlePermissionRemove(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val user = call.routeUser() ?: return
        users.removePermission(user, call.parameters["permission"].orEmpty())
        call.respondJson("""{"status":"ok"}""")
    }

    // ------------------------------------------------------------------ commands

    private suspend fun handleCommandGet(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val command = call.routeCommand() ?: return
        call.respondJson(json.encodeToString(CommandDto.serializer(), command.toDto()))
    }

    private suspend fun handleCommandCancel(call: ApplicationCall) {
        if (call.authorizedAdmin() == null) return
        val command = call.routeCommand() ?: return
        val cancelled = commandService.cancel(command.id)
        if (cancelled != null && cancelled.status == "cancelled") {
            call.respondJson(json.encodeToString(CommandDto.serializer(), command.toDto()))
        } else {
            call.respondError(HttpStatusCode.Conflict, "not_cancellable", "Команда в статусе ${command.status} не может быть отменена")
        }
    }

    private fun Command.toDto(): CommandDto = CommandDto(
        id = id.toString(),
        requestId = requestId,
        senderId = senderId.toString(),
        targetId = targetId.toString(),
        actionId = actionId,
        payload = json.parseToJsonElement(payload).let { it as? JsonObject ?: JsonObject(emptyMap()) },
        mode = mode,
        status = status,
        error = error,
        createdAt = createdAt.toString(),
        executedAt = executedAt?.toString(),
    )

    // ------------------------------------------------------------------ helpers

    private suspend fun ApplicationCall.routeUser(): User? {
        val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val user = id?.let { users.byId(it) }
        if (user == null) {
            respondError(HttpStatusCode.NotFound, "unknown_user", "Пользователь не найден")
        }
        return user
    }

    private suspend fun ApplicationCall.routeCommand(): Command? {
        val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val command = id?.let { commandService.find(it) }
        if (command == null) {
            respondError(HttpStatusCode.NotFound, "unknown_command", "Команда не найдена")
        }
        return command
    }

    private suspend fun ApplicationCall.authorizedAdmin(): User? {
        val header = request.headers["Authorization"]
        if (header == null || !header.startsWith("Bearer ")) {
            respondError(HttpStatusCode.Unauthorized, ErrorCode.NOT_AUTHENTICATED, "Требуется Authorization: Bearer <access_token>")
            return null
        }
        val userId = tokenService.validate(header.removePrefix("Bearer ").trim(), "access")
        val user = userId?.let { users.byId(it) }
        if (user == null || !permissions.has(user, PermissionService.ADMIN_PERMISSION)) {
            respondError(HttpStatusCode.Forbidden, ErrorCode.NO_PERMISSION, "Требуется право ${PermissionService.ADMIN_PERMISSION}")
            return null
        }
        return user
    }

    private fun linkPage(): String = """
        <!DOCTYPE html>
        <html lang="ru">
        <head><meta charset="utf-8"><title>Shigu's Dream — привязка аккаунта</title>
        <style>
          body { font-family: system-ui, sans-serif; background: #1b1b2f; color: #eee; display: flex;
                 justify-content: center; align-items: center; height: 100vh; margin: 0; }
          .card { background: #262647; padding: 2rem 3rem; border-radius: 12px; min-width: 320px; }
          h1 { font-size: 1.3rem; } input, button { display: block; width: 100%; box-sizing: border-box;
                 margin: .6rem 0; padding: .6rem; border-radius: 6px; border: 1px solid #444; font-size: 1rem; }
          button { background: #7c5cff; color: white; border: none; cursor: pointer; }
        </style></head>
        <body><div class="card">
          <h1>Shigu's Dream — привязка аккаунта</h1>
          <p>Введите одноразовый код, который показывает мод в игре, и имя аккаунта.</p>
          <form method="post" action="/link">
            <input name="code" placeholder="Код из игры (например, K7QM2P)" required maxlength="8">
            <input name="username" placeholder="Имя аккаунта (3-16 символов)" required maxlength="16">
            <button type="submit">Привязать</button>
          </form>
          <p style="font-size:.85rem;color:#999">Код действует 15 минут и работает один раз.</p>
        </div></body></html>
    """.trimIndent()

    private fun page(title: String, message: String): String = """
        <!DOCTYPE html>
        <html lang="ru"><head><meta charset="utf-8"><title>Shigu's Dream — $title</title>
        <style>body { font-family: system-ui, sans-serif; background: #1b1b2f; color: #eee;
          display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
        .card { background: #262647; padding: 2rem 3rem; border-radius: 12px; }</style></head>
        <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()
}
