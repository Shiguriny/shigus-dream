package com.shigusdream.client

import net.minecraft.client.Minecraft
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Скрытие игроков на клиенте: UUID'ы из этого набора не рендерятся вообще
 * (ни модель, ни ник, ни частицы). Управляется действием hide_player.
 */
object HiddenEntities {
    private val hidden = ConcurrentHashMap<UUID, Int?>() // uuid -> ticksLeft (null = бесконечно)

    fun hide(uuid: UUID, ticks: Int = -1) {
        hidden[uuid] = if (ticks > 0) ticks else null
    }

    fun show(uuid: UUID) {
        hidden.remove(uuid)
    }

    fun showAll() {
        hidden.clear()
    }

    fun isHidden(uuid: UUID): Boolean = hidden.containsKey(uuid)

    fun tick() {
        val iter = hidden.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val left = entry.value ?: continue // null = бесконечно
            if (left - 1 <= 0) iter.remove() else entry.setValue(left - 1)
        }
    }
}
