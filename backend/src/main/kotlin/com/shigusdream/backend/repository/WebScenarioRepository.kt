package com.shigusdream.backend.repository

/** Шаг веб-сценария. */
data class WebScenarioStep(
    val target: String,
    val action: String,
    val args: String, // JSON
    val delayMs: Int = 1000,
    val repeat: Int = 1,
    val waitForResult: Boolean = true,
    val stopOnError: Boolean = true,
)

/** Сценарий, хранящийся на backend и исполняемый им же (веб-панель). */
data class WebScenario(
    val name: String,
    val steps: List<WebScenarioStep>,
    val loops: Int = 1,
    val scheduledMinutes: Int = 0,
    val createdBy: String,
)

interface WebScenarioRepository {
    /** Сохраняет сценарий (создаёт или обновляет по имени). */
    fun save(scenario: WebScenario)

    fun list(): List<WebScenario>

    fun byName(name: String): WebScenario?

    fun delete(name: String): Boolean
}
