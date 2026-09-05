package com.shigusdream.admin

import com.shigusdream.ShigusDreamClient
import net.minecraft.client.Minecraft

/** Рантайм-состояние гео-триггеров: проверка входа в зону и запуск сценария. */
object GeoTriggerRuntime {
    private val fired = HashSet<String>()

    data class GeoLogEntry(val time: String, val trigger: String, val player: String)

    /** Журнал срабатываний (последние 50). */
    val log = ArrayDeque<GeoLogEntry>()

    /** Вызывается раз в секунду из END-тика. */
    fun check(mc: Minecraft) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val dimension = level.dimension().identifier().toString()
        val pos = player.position()

        for (trigger in AdminDataStore.triggers) {
            val id = trigger.name
            if (!trigger.enabled) continue
            if (trigger.dimension != dimension) continue

            val dx = pos.x - trigger.x
            val dy = pos.y - trigger.y
            val dz = pos.z - trigger.z
            val inside = dx * dx + dy * dy + dz * dz <= trigger.radius * trigger.radius

            if (inside && id !in fired) {
                fired += id
                val scenario = ScenarioStore.scenarios.firstOrNull { it.name == trigger.scenario }
                if (scenario != null) {
                    ShigusDreamClient.chatFeedback("§b[Shigu's Dream]§7 Триггер «${trigger.name}» → сценарий «${trigger.scenario}»")
                    ScenarioRunner.start(scenario)
                }
                if (log.size >= 50) log.removeFirst()
                log += GeoLogEntry(
                    java.time.LocalTime.now().toString().substring(0, 8),
                    trigger.name,
                    player.gameProfile.name,
                )
            } else if (!inside && id in fired) {
                fired -= id // вышел из зоны — триггер снова armed
            }
        }
    }

    fun reset() {
        fired.clear()
    }
}
