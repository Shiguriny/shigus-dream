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
)

/** Сценарий — именованная цепочка шагов. */
data class Scenario(
    val name: String,
    val steps: MutableList<ScenarioStep>,
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
                        )
                    }
                    scenarios += Scenario(o.get("name").asString, steps)
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
                val steps = com.google.gson.JsonArray()
                for (step in scenario.steps) {
                    val sto = JsonObject()
                    sto.addProperty("target", step.target)
                    sto.addProperty("action", step.action)
                    sto.add("args", step.args)
                    sto.addProperty("delay", step.delay)
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
    private var runningScenario: Scenario? = null

    val isRunning: Boolean get() = runningScenario != null
    val runningName: String? get() = runningScenario?.name
    val progressLine: String?
        get() = runningScenario?.let { "Выполнение «${it.name}»: шаг ${stepIndex + 1}/${it.steps.size}" }

    fun start(scenario: Scenario) {
        if (scenario.steps.isEmpty()) return
        runningScenario = scenario
        stepIndex = 0
        ticksLeft = 1
        ShigusDreamClient.chatFeedback("§b[Shigu's Dream]§7 Запуск сценария «${scenario.name}» (${scenario.steps.size} шагов)")
    }

    fun stop() {
        if (runningScenario != null) {
            ShigusDreamClient.chatFeedback("§7[Shigu's Dream] Сценарий остановлен")
        }
        runningScenario = null
    }

    fun tick() {
        val scenario = runningScenario ?: return
        if (--ticksLeft > 0) return

        val step = scenario.steps.getOrNull(stepIndex)
        if (step == null) {
            ShigusDreamClient.chatFeedback("§a[Shigu's Dream]§7 Сценарий «${scenario.name}» выполнен")
            runningScenario = null
            return
        }
        val requestId = ShigusDreamClient.connection.sendExecute(step.target, step.action, step.args)
        ShigusDream.LOGGER.info("scenario[{}] step {} -> {} (request_id={})", scenario.name, stepIndex + 1, step.action, requestId)
        stepIndex++
        ticksLeft = step.delay.coerceAtLeast(1)
    }
}
