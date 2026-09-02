package com.shigusdream.backend.command

import com.shigusdream.backend.action.ActionRegistry
import com.shigusdream.backend.action.ActionSpec
import com.shigusdream.backend.auth.PermissionService
import com.shigusdream.backend.protocol.ActionExecutePayload
import com.shigusdream.backend.protocol.ErrorCode
import com.shigusdream.backend.protocol.ProtocolJson
import com.shigusdream.backend.repository.Command
import com.shigusdream.backend.repository.CommandRepository
import com.shigusdream.backend.repository.User
import com.shigusdream.backend.repository.UserRepository
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Валидация и учёт команд. Backend проверяет права ДО доставки.
 * Каждая команда хранится с уникальным request_id — повторная вставка отклоняется (дедупликация).
 */
class CommandService(
    private val users: UserRepository,
    private val commands: CommandRepository,
    private val permissions: PermissionService,
) {
    sealed interface HandleOutcome {
        data class Created(val command: Command, val spec: ActionSpec, val target: User) : HandleOutcome
        data class Rejected(val code: String, val message: String) : HandleOutcome
    }

    fun handle(sender: User, payload: ActionExecutePayload, requestId: String): HandleOutcome {
        val spec = ActionRegistry.byId(payload.action)
            ?: return HandleOutcome.Rejected(ErrorCode.UNKNOWN_ACTION, "Действие '${payload.action}' не зарегистрировано")

        if (!permissions.has(sender, PermissionService.ADMIN_PERMISSION)) {
            return HandleOutcome.Rejected(ErrorCode.NO_PERMISSION, "Требуется право ${PermissionService.ADMIN_PERMISSION}")
        }
        if (!permissions.has(sender, spec.permission)) {
            return HandleOutcome.Rejected(ErrorCode.NO_PERMISSION, "Требуется право ${spec.permission}")
        }
        if (payload.mode !in setOf("immediate", "queued")) {
            return HandleOutcome.Rejected(ErrorCode.INVALID_ARGUMENTS, "mode must be 'immediate' or 'queued'")
        }

        val argErrors = ActionRegistry.validateArgs(spec, payload.args)
        if (argErrors.isNotEmpty()) {
            return HandleOutcome.Rejected(ErrorCode.INVALID_ARGUMENTS, argErrors.joinToString("; "))
        }

        val target = users.byUsername(payload.target)
            ?: return HandleOutcome.Rejected(ErrorCode.UNKNOWN_TARGET, "Пользователь '${payload.target}' не найден")

        val now = Instant.now()
        val command = Command(
            id = UUID.randomUUID(),
            requestId = requestId,
            senderId = sender.id,
            targetId = target.id,
            actionId = payload.action,
            payload = ProtocolJson.encodeToString(JsonObject.serializer(), payload.args),
            mode = payload.mode,
            status = "pending",
            createdAt = now,
            expiresAt = now.plus(COMMAND_TTL),
        )
        if (!commands.insert(command)) {
            return HandleOutcome.Rejected(ErrorCode.DUPLICATE_REQUEST, "request_id уже был обработан")
        }
        return HandleOutcome.Created(command, spec, target)
    }

    /** Регистрирует результат выполнения от целевого клиента. Возвращает команду для пересылки отправителю. */
    fun onResult(requestId: String, status: String, error: String?): Command? {
        val command = commands.byRequestId(requestId) ?: return null
        command.status = if (status == "executed") "executed" else "failed"
        command.error = error
        command.executedAt = Instant.now()
        commands.update(command)
        return command
    }

    /**
     * Подготавливает команду к доставке: помечает delivered или expired.
     * Возвращает true, если команду можно отправлять.
     */
    fun markForDelivery(command: Command): Boolean {
        if (command.expiresAt != null && Instant.now().isAfter(command.expiresAt)) {
            command.status = "expired"
            commands.update(command)
            return false
        }
        command.status = "delivered"
        commands.update(command)
        return true
    }

    /** Помечает immediate-команду как failed (цель не в сети). */
    fun markFailedOffline(command: Command, code: String) {
        command.status = "failed"
        command.error = code
        commands.update(command)
    }

    /** Все queued-команды, ожидающие подключения цели. */
    fun pendingForTarget(targetId: UUID): List<Command> = commands.pendingForTarget(targetId)

    fun find(commandId: UUID): Command? = commands.byId(commandId)

    fun cancel(commandId: UUID): Command? {
        val command = commands.byId(commandId) ?: return null
        if (command.status != "pending") return command
        command.status = "cancelled"
        commands.update(command)
        return command
    }

    companion object {
        val COMMAND_TTL: Duration = Duration.ofHours(24)
    }
}
