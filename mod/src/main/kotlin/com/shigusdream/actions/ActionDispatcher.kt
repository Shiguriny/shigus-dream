package com.shigusdream.actions

import com.google.gson.JsonObject
import com.shigusdream.ShigusDream
import java.util.ArrayDeque

/**
 * Обработка входящих action.execute:
 * validate → deduplicate (request_id) → клиентский поток Minecraft → execute → ActionResult.
 * Потокобезопасен: handle() вызывается из сетевого потока, execute — в client thread.
 */
class ActionDispatcher(
    private val registry: ActionRegistry,
    private val executor: (Runnable) -> Unit,
    private val resultSink: (requestId: String, action: String, executed: Boolean, error: String?) -> Unit,
) {
    private val recentRequestIds = ArrayDeque<String>()
    private val lock = Any()

    /**
     * Валидирует и ставит выполнение в очередь клиентского потока.
     * Возвращает true, если команда принята (не дубликат, действие известно).
     */
    fun handle(requestId: String, actionId: String, args: JsonObject) {
        val action = registry.byId(actionId)
        if (action == null) {
            ShigusDream.LOGGER.warn("Получено неизвестное действие: {}", actionId)
            resultSink(requestId, actionId, false, "unknown_action")
            return
        }

        if (!deduplicate(requestId)) {
            ShigusDream.LOGGER.info("Дубликат request_id {} отклонён", requestId)
            resultSink(requestId, actionId, false, "duplicate_request")
            return
        }

        val errors = ActionValidator.validate(action.schema, args)
        if (errors.isNotEmpty()) {
            ShigusDream.LOGGER.warn("Невалидные аргументы для {}: {}", actionId, errors.joinToString("; "))
            resultSink(requestId, actionId, false, "invalid_arguments: ${errors.joinToString("; ")}")
            return
        }

        val context = ActionContext(requestId, args)
        executor(Runnable {
            val result = try {
                action.execute(null, context)
            } catch (e: Exception) {
                ShigusDream.LOGGER.error("Ошибка выполнения действия {}", actionId, e)
                ActionResult.fail("execution_error: ${e.message ?: e.javaClass.simpleName}")
            }
            resultSink(requestId, actionId, result.executed, result.error)
            ShigusDream.LOGGER.info(
                "action {} request_id={} -> {}",
                actionId,
                requestId,
                if (result.executed) "executed" else "failed: ${result.error}",
            )
        })
    }

    /** LRU на 1000 последних request_id: повторное выполнение одного запроса запрещено. */
    private fun deduplicate(requestId: String): Boolean = synchronized(lock) {
        if (recentRequestIds.contains(requestId)) {
            return false
        }
        recentRequestIds.addLast(requestId)
        while (recentRequestIds.size > MAX_REMEMBERED) {
            recentRequestIds.pollFirst()
        }
        true
    }

    companion object {
        const val MAX_REMEMBERED = 1000
    }
}
