package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Выделение для цели: игроки подсвечиваются ванильным glow (миксин в shouldEntityAppearGlowing),
 * блоки — маркерами на HUD (проекция точки мира на экран).
 */
object Highlight {

    class BlockMarker(val pos: Vec3, val color: Int, var ticksLeft: Int)

    private val glowingPlayers = ConcurrentHashMap<UUID, Int>() // uuid -> ticksLeft
    private val blockMarkers = ConcurrentHashMap<String, BlockMarker>()

    fun addGlow(uuid: UUID, ticks: Int) {
        glowingPlayers[uuid] = ticks.coerceAtLeast(1)
    }

    fun addBlock(id: String, pos: Vec3, color: Int, ticks: Int) {
        blockMarkers[id] = BlockMarker(pos, color, ticks.coerceAtLeast(1))
    }

    fun isGlowing(uuid: UUID): Boolean = glowingPlayers.containsKey(uuid)

    fun tick(mc: Minecraft) {
        val glowIter = glowingPlayers.entries.iterator()
        while (glowIter.hasNext()) {
            val entry = glowIter.next()
            val left = entry.value - 1
            if (left <= 0) glowIter.remove() else entry.setValue(left)
        }
        for (marker in blockMarkers.values) {
            marker.ticksLeft -= 1
        }
        blockMarkers.entries.removeIf { it.value.ticksLeft <= 0 }
    }

    fun cancelAll() {
        glowingPlayers.clear()
        blockMarkers.clear()
    }

    /** Вызывается из HUD-фазы: рисует маркеры блоков. */
    fun render(g: GuiGraphicsExtractor) {
        if (blockMarkers.isEmpty()) return
        val mc = Minecraft.getInstance()
        val font = mc.font
        for (marker in blockMarkers.values) {
            val projected = runCatching {
                mc.gameRenderer.projectPointToScreen(marker.pos)
            }.getOrNull() ?: continue
            val sx = projected.x
            val sy = projected.y
            if (sx < 0 || sy < 0 || sx > g.guiWidth() || sy > g.guiHeight()) continue

            val size = 28
            val color = 0xFF000000.toInt() or marker.color
            g.fill((sx - size / 2).toInt(), (sy - size / 2).toInt(), (sx + size / 2).toInt(), (sy - size / 2).toInt() + 2, color)
            g.fill((sx - size / 2).toInt(), (sy + size / 2).toInt() - 2, (sx + size / 2).toInt(), (sy + size / 2).toInt(), color)
            g.fill((sx - size / 2).toInt(), (sy - size / 2).toInt(), (sx - size / 2).toInt() + 2, (sy + size / 2).toInt(), color)
            g.fill((sx + size / 2).toInt() - 2, (sy - size / 2).toInt(), (sx + size / 2).toInt(), (sy + size / 2).toInt(), color)

            val dist = mc.player?.let { it.position().distanceTo(marker.pos) } ?: 0.0
            g.text(font, Component.literal("${"%.0f".format(dist)} м"), sx.toInt() + size / 2 + 2, sy.toInt() - 4, 0xFFFFFFFF.toInt())
        }
    }
}
