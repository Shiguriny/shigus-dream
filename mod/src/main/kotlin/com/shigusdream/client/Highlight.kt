package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Выделение для цели: игроки подсвечиваются ванильным glow (миксин в shouldEntityAppearGlowing),
 * блоки — проекцией каркаса куба на экран (12 рёбер по 8 спроецированным углам).
 * Блоки за камерой не рисуются (NDC-зеркало давало артефакт «на экране спереди»).
 */
object Highlight {

    class BlockMarker(val center: Vec3, val color: Int, var ticksLeft: Int)

    private val glowingPlayers = ConcurrentHashMap<UUID, Int>()
    private val blockMarkers = ConcurrentHashMap<String, BlockMarker>()

    fun addGlow(uuid: UUID, ticks: Int) {
        glowingPlayers[uuid] = ticks.coerceAtLeast(1)
    }

    fun addBlock(id: String, center: Vec3, color: Int, ticks: Int) {
        blockMarkers[id] = BlockMarker(center, color, ticks.coerceAtLeast(1))
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

    /** Проекция точки мира в пиксели GUI; null, если вне экрана. */
    private fun project(mc: Minecraft, g: GuiGraphicsExtractor, point: Vec3): Pair<Double, Double>? {
        val ndc = runCatching {
            mc.gameRenderer.projectPointToScreen(point)
        }.getOrNull() ?: return null
        if (ndc.x < -1.2 || ndc.x > 1.2 || ndc.y < -1.2 || ndc.y > 1.2) return null
        val sx = (ndc.x + 1.0) / 2.0 * g.guiWidth()
        val sy = (1.0 - ndc.y) / 2.0 * g.guiHeight()
        return sx to sy
    }

    private fun drawLine(g: GuiGraphicsExtractor, a: Pair<Double, Double>, b: Pair<Double, Double>, color: Int) {
        val steps = Math.max(Math.abs(b.first - a.first), Math.abs(b.second - a.second)).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val x = a.first + (b.first - a.first) * t
            val y = a.second + (b.second - a.second) * t
            g.fill(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1, color)
        }
    }

    /** Вызывается из HUD-фазы: рисует каркасы блоков. */
    fun render(g: GuiGraphicsExtractor) {
        if (blockMarkers.isEmpty()) return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val player = mc.player ?: return
        val look = player.getViewVector(1.0f)

        for (marker in blockMarkers.values) {
            // Блок за камерой — не рисуем.
            val toBlock = marker.center.subtract(player.getEyePosition(1.0f))
            if (toBlock.dot(look) < 0) continue

            val h = 0.5
            val corners = listOf(
                Vec3(marker.center.x - h, marker.center.y - h, marker.center.z - h),
                Vec3(marker.center.x + h, marker.center.y - h, marker.center.z - h),
                Vec3(marker.center.x - h, marker.center.y + h, marker.center.z - h),
                Vec3(marker.center.x + h, marker.center.y + h, marker.center.z - h),
                Vec3(marker.center.x - h, marker.center.y - h, marker.center.z + h),
                Vec3(marker.center.x + h, marker.center.y - h, marker.center.z + h),
                Vec3(marker.center.x - h, marker.center.y + h, marker.center.z + h),
                Vec3(marker.center.x + h, marker.center.y + h, marker.center.z + h),
            ).map { project(mc, g, it) }
            if (corners.any { it == null }) continue
            @Suppress("UNCHECKED_CAST")
            val pts = corners as List<Pair<Double, Double>>

            val edges = listOf(
                0 to 1, 1 to 3, 3 to 2, 2 to 0,
                4 to 5, 5 to 7, 7 to 6, 6 to 4,
                0 to 4, 1 to 5, 2 to 6, 3 to 7,
            )
            val color = 0xFF000000.toInt() or marker.color
            for ((a, b) in edges) {
                drawLine(g, pts[a], pts[b], color)
            }

            // Подпись расстояния у верхнего левого переднего угла
            val top = pts[6]
            val dist = player.position().distanceTo(marker.center)
            g.text(font, Component.literal("${"%.0f".format(dist)} м"), top.first.toInt() + 3, top.second.toInt() - 8, 0xFFFFFFFF.toInt())
        }
    }
}
