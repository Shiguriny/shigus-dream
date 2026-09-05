package com.shigusdream.backend.web

import com.shigusdream.backend.action.ActionRegistry
import com.shigusdream.backend.command.CommandService
import com.shigusdream.backend.protocol.ActionExecutePayload
import com.shigusdream.backend.protocol.ErrorCode
import com.shigusdream.backend.protocol.MessageType
import com.shigusdream.backend.protocol.ProtocolJson
import com.shigusdream.backend.repository.Command
import com.shigusdream.backend.repository.UserRepository
import com.shigusdream.backend.repository.WebScenario
import com.shigusdream.backend.repository.WebScenarioRepository
import com.shigusdream.backend.websocket.WsManager
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Исполнитель веб-сценариев на backend: шаги с задержками, ожидание результата,
 * стоп-по-ошибке, повторы шагов и циклы, автозапуск по расписанию.
 */
class WebScenarioService(
    private val repo: WebScenarioRepository,
    private val users: UserRepository,
    private val commandService: CommandService,
    private val wsManager: WsManager,
) {
    data class RunState(
        val runId: String,
        val scenario: String,
        val step: Int,
        val totalSteps: Int,
        val loop: Int,
        val loops: Int,
        val status: String, // running | finished | failed | stopped
        val lastError: String? = null,
    )

    private val runs = ConcurrentHashMap<String, RunState>()
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private val scheduleCounters = ConcurrentHashMap<String, Int>()
    @Volatile
    private var schedulerStarted = false

    fun list(): List<WebScenario> = repo.list()

    fun save(scenario: WebScenario): WebScenario {
        repo.save(scenario)
        return scenario
    }

    fun delete(name: String): Boolean = repo.delete(name)

    fun byName(name: String): WebScenario? = repo.byName(name)

    fun runStates(): List<RunState> = runs.values.sortedByDescending { it.runId }

    fun stop(runId: String) {
        stopped += runId
    }

    /** Запускает сценарий в фоновом потоке. Возвращает runId или null (не найден/пуст). */
    fun run(name: String, triggeredBy: String): String? {
        val scenario = repo.byName(name) ?: return null
        if (scenario.steps.isEmpty()) return null
        val runId = UUID.randomUUID().toString()
        runs[runId] = RunState(runId, name, 0, scenario.steps.size, 1, scenario.loops.coerceIn(1, 100), "running")
        Thread({
            try {
                executeRun(runId, scenario, triggeredBy)
            } catch (e: Exception) {
                runs[runId] = RunState(runId, scenario.name, 0, scenario.steps.size, 1, scenario.loops, "failed", e.message)
            }
        }, "shigusdream-webrun-$name").apply { isDaemon = true }.start()
        return runId
    }

    private fun isStopped(runId: String): Boolean = runId in stopped

    private fun updateState(runId: String, scenario: WebScenario, step: Int, loop: Int, status: String, error: String?) {
        runs[runId] = RunState(runId, scenario.name, step, scenario.steps.size, loop + 1, scenario.loops, status, error)
    }

    private fun executeRun(runId: String, scenario: WebScenario, triggeredBy: String) {
        val executor = users.byUsername(triggeredBy)
        if (executor == null) {
            runs[runId] = RunState(runId, scenario.name, 0, scenario.steps.size, 1, scenario.loops, "failed", "Аккаунт $triggeredBy не найден")
            return
        }

        for (loop in 0 until scenario.loops.coerceIn(1, 100)) {
            for ((index, step) in scenario.steps.withIndex()) {
                if (isStopped(runId)) {
                    runs[runId] = RunState(runId, scenario.name, index, scenario.steps.size, loop + 1, scenario.loops, "stopped", null)
                    return
                }
                updateState(runId, scenario, index, loop, "running", null)

                val repeats = step.repeat.coerceIn(1, 20)
                for (r in 0 until repeats) {
                    val requestId = "web-$runId-$loop-$index-$r"
                    val args = ProtocolJson.parseToJsonElement(step.args).let {
                        it as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
                    }
                    val payload = ActionExecutePayload(
                        target = step.target,
                        action = step.action,
                        args = args,
                        mode = "immediate",
                    )

                    var command: Command? = null
                    var error: String? = null
                    when (val outcome = commandService.handle(executor, payload, requestId)) {
                        is CommandService.HandleOutcome.Rejected ->
                            error = "${outcome.code}: ${outcome.message}"

                        is CommandService.HandleOutcome.Created -> {
                            command = outcome.command
                            val envelope = com.shigusdream.backend.protocol.Envelope(
                                requestId = requestId,
                                messageType = MessageType.ACTION_EXECUTE,
                                payload = ProtocolJson.encodeToJsonElement(
                                    ActionExecutePayload.serializer(),
                                    ActionExecutePayload(
                                        target = outcome.target.username,
                                        action = payload.action,
                                        args = payload.args,
                                        mode = "immediate",
                                        commandId = outcome.command.id.toString(),
                                    ),
                                ),
                            )
                            val delivered = runBlocking { wsManager.deliverTo(outcome.target.id, envelope) }
                            if (delivered) {
                                commandService.markForDelivery(outcome.command)
                            } else {
                                commandService.markFailedOffline(outcome.command, ErrorCode.TARGET_OFFLINE)
                                error = ErrorCode.TARGET_OFFLINE
                            }
                        }
                    }

                    if (error != null && step.stopOnError) {
                        runs[runId] = RunState(runId, scenario.name, index, scenario.steps.size, loop + 1, scenario.loops, "failed", error)
                        return
                    }

                    // Ожидание результата цели, если шаг этого требует
                    if (error == null && step.waitForResult && command != null) {
                        var waited = 0
                        while (waited < 15_000) {
                            val current = commandService.find(command.id)
                            val status = current?.status ?: "pending"
                            if (status == "executed" || status == "failed") {
                                if (status == "failed" && step.stopOnError) {
                                    runs[runId] = RunState(runId, scenario.name, index, scenario.steps.size, loop + 1, scenario.loops, "failed", current?.error)
                                    return
                                }
                                break
                            }
                            Thread.sleep(200)
                            waited += 200
                        }
                    }

                    if (r < repeats - 1) Thread.sleep(step.delayMs.toLong().coerceAtLeast(50))
                }

                if (index < scenario.steps.size - 1) Thread.sleep(step.delayMs.toLong().coerceAtLeast(50))
            }
        }
        runs[runId] = RunState(runId, scenario.name, scenario.steps.size, scenario.steps.size, scenario.loops, scenario.loops, "finished", null)
    }

    /** Автозапуск по расписанию (scheduledMinutes > 0). Проверка раз в минуту. */
    fun startScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true
        Thread({
            while (true) {
                Thread.sleep(60_000)
                try {
                    for (scenario in repo.list()) {
                        if (scenario.scheduledMinutes <= 0) continue
                        val counter = (scheduleCounters[scenario.name] ?: 0) + 1
                        if (counter >= scenario.scheduledMinutes) {
                            scheduleCounters[scenario.name] = 0
                            if (runs.values.none { it.scenario == scenario.name && it.status == "running" }) {
                                run(scenario.name, scenario.createdBy)
                            }
                        } else {
                            scheduleCounters[scenario.name] = counter
                        }
                    }
                } catch (e: Exception) {
                    // планировщик переживает сбой отдельного сценария
                }
            }
        }, "shigusdream-web-scheduler").apply { isDaemon = true }.start()
    }
}
