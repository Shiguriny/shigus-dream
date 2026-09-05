package com.shigusdream.admin

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.shigusdream.ShigusDream
import com.shigusdream.ShigusDreamClient
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/** Шаг сценария: цель, действие, аргументы (JSON), задержка после выполнения (в тиках). */
data class ScenarioStep(
    val target: String,
    val action: String,
    val args: JsonObject,
    var delay: Int = 20,
    var delayBefore: Int = 0,
    var waitForResult: Boolean = true,
    var stopOnError: Boolean = true,
    var repeat: Int = 1,
)

/** Сценарий — именованная цепочка шагов. */
data class Scenario(
    val name: String,
    val steps: MutableList<ScenarioStep>,
    var loops: Int = 1,
)

/**
 * Хранилище сценариев (config/shigusdream_scenarios.json) и исполнитель.
 * Исполнение клиентское: шаги отправляются последовательно с задержками;
 * права на каждое действие по-прежнему проверяет backend.
 */
object ScenarioStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: Path

    val scenarios = mutableListOf<Scenario>()
    var currentIndex = 0

    val current: Scenario? get() = scenarios.getOrNull(currentIndex)

    fun init(configDir: Path) {
        file = configDir.resolve("shigusdream_scenarios.json")
        try {
            if (Files.exists(file)) {
                val root = com.google.gson.JsonParser.parseString(Files.readString(file)).asJsonObject
                val arr = root.getAsJsonArray("scenarios") ?: return
                scenarios.clear()
                for (el in arr) {
                    val o = el.asJsonObject
                    val steps = mutableListOf<ScenarioStep>()
                    for (s in o.getAsJsonArray("steps")) {
                        val so = s.asJsonObject
                        steps += ScenarioStep(
                            target = so.get("target").asString,
                            action = so.get("action").asString,
                            args = so.getAsJsonObject("args") ?: JsonObject(),
                            delay = so.get("delay")?.asInt ?: 20,
                            delayBefore = so.get("delay_before")?.asInt ?: 0,
                            waitForResult = so.get("wait_for_result")?.asBoolean ?: true,
                            stopOnError = so.get("stop_on_error")?.asBoolean ?: true,
                            repeat = so.get("repeat")?.asInt ?: 1,
                        )
                    }
                    scenarios += Scenario(o.get("name").asString, steps, o.get("loops")?.asInt ?: 1)
                }
            }
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Не удалось прочитать сценарии", e)
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            val arr = com.google.gson.JsonArray()
            for (scenario in scenarios) {
                val so = JsonObject()
                so.addProperty("name", scenario.name)
                so.addProperty("loops", scenario.loops)
                val steps = com.google.gson.JsonArray()
                for (step in scenario.steps) {
                    val sto = JsonObject()
                    sto.addProperty("target", step.target)
                    sto.addProperty("action", step.action)
                    sto.add("args", step.args)
                    sto.addProperty("delay", step.delay)
                    sto.addProperty("delay_before", step.delayBefore)
                    sto.addProperty("wait_for_result", step.waitForResult)
                    sto.addProperty("stop_on_error", step.stopOnError)
                    sto.addProperty("repeat", step.repeat)
                    steps.add(sto)
                }
                so.add("steps", steps)
                arr.add(so)
            }
            root.add("scenarios", arr)
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(root))
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Не удалось сохранить сценарии", e)
        }
    }

    fun create(name: String): Scenario {
        var unique = name
        var i = 1
        while (scenarios.any { it.name == unique }) unique = "$name ${++i}"
        val scenario = Scenario(unique, mutableListOf())
        scenarios += scenario
        currentIndex = scenarios.size - 1
        save()
        return scenario
    }

    fun deleteCurrent() {
        if (scenarios.isEmpty()) return
        scenarios.removeAt(currentIndex.coerceIn(0, scenarios.size - 1))
        currentIndex = currentIndex.coerceIn(0, (scenarios.size - 1).coerceAtLeast(0))
        save()
    }
}

/** Последовательное исполнение сценария с задержками между шагами. */
object ScenarioRunner {
    private var ticksLeft = 0
    private var stepIndex = 0
    private var loopIndex = 0
    private var repeatIndex = 0
    private var runningScenario: Scenario? = null
    private val awaiting = linkedSetOf<String>()
    private var waitingFailed = false

    val isRunning: Boolean get() = runningScenario != null
    val runningName: String? get() = runningScenario?.name
    val progressLine: String?
        get() = runningScenario?.let {
            "${it.name}: ${stepIndex + 1}/${it.steps.size}, ${loopIndex + 1}/${it.loops}"
        }

    fun start(scenario: Scenario) {
        if (scenario.steps.isEmpty()) return
        runningScenario = scenario
        stepIndex = 0
        loopIndex = 0
        repeatIndex = 0
        awaiting.clear()
        waitingFailed = false
        ticksLeft = scenario.steps.first().delayBefore.coerceAtLeast(1)
        ShigusDreamClient.chatFeedback("§b[Shigu's Dream]§7 Запуск сценария «${scenario.name}» (${scenario.steps.size} шагов)")
    }

    fun stop() {
        if (runningScenario != null) {
            ShigusDreamClient.chatFeedback("§7[Shigu's Dream] Сценарий остановлен")
        }
        runningScenario = null
        awaiting.clear()
    }

    fun tick() {
        val scenario = runningScenario ?: return
        if (awaiting.isNotEmpty()) return
        if (--ticksLeft > 0) return

        val step = scenario.steps.getOrNull(stepIndex)
        if (step == null) {
            loopIndex++
            if (loopIndex < scenario.loops.coerceIn(1, 100)) {
                stepIndex = 0
                repeatIndex = 0
                ticksLeft = scenario.steps.first().delayBefore.coerceAtLeast(1)
            } else {
                ShigusDreamClient.chatFeedback("§a[Shigu's Dream]§7 Сценарий «${scenario.name}» выполнен")
                runningScenario = null
            }
            return
        }
        val requestIds = ShigusDreamClient.sendAction(step.target, step.action, step.args)
        ShigusDream.LOGGER.info("scenario[{}] step {} -> {} (request_ids={})", scenario.name, stepIndex + 1, step.action, requestIds)
        if (requestIds.isEmpty()) {
            if (step.stopOnError) {
                failScenario("no_targets")
                return
            }
            advance(step)
        } else if (step.waitForResult) {
            awaiting += requestIds
            waitingFailed = false
        } else {
            advance(step)
        }
    }

    fun onResult(requestId: String, status: String, error: String?) {
        if (!awaiting.remove(requestId)) return
        if (status != "executed") waitingFailed = true
        if (awaiting.isNotEmpty()) return
        val scenario = runningScenario ?: return
        val step = scenario.steps.getOrNull(stepIndex) ?: return
        if (waitingFailed && step.stopOnError) {
            failScenario(error ?: "step_failed")
            return
        }
        advance(step)
    }

    private fun advance(step: ScenarioStep) {
        repeatIndex++
        if (repeatIndex >= step.repeat.coerceIn(1, 100)) {
            repeatIndex = 0
            stepIndex++
            val nextBefore = runningScenario?.steps?.getOrNull(stepIndex)?.delayBefore ?: 0
            ticksLeft = (step.delay + nextBefore).coerceAtLeast(1)
        } else {
            ticksLeft = step.delay.coerceAtLeast(1)
        }
    }

    private fun failScenario(error: String) {
        val name = runningScenario?.name ?: return
        ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Сценарий «$name» остановлен: $error")
        runningScenario = null
        awaiting.clear()
    }
}
