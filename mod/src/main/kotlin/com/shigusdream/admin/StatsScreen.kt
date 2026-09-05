package com.shigusdream.admin

import com.shigusdream.ShigusDreamClient
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Дашборд статистики поверх CommandHistory. */
class StatsScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.stats.title")) {

    private var left = 12
    private var boxWidth = 380

    override fun init() {
        clearWidgets()
        boxWidth = minOf(420, width - 24).coerceAtLeast(200)
        left = (width - boxWidth) / 2
        addRenderableWidget(
            UiButton(left, height - 30, boxWidth, 20, I18n.text("shigusdream.common.back"), { onClose() }),
        )
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.text(font, title, left, 16, -1)

        val stats = CommandHistory.stats()
        var y = 40
        g.text(font, I18n.component("shigusdream.stats.total", stats.total), left, y, 0xFFC8C8D8.toInt()); y += 16
        g.text(font, I18n.component("shigusdream.stats.executed", stats.executed), left, y, 0xFF55FF55.toInt()); y += 16
        g.text(font, I18n.component("shigusdream.stats.failed", stats.failed), left, y, 0xFFFF5555.toInt()); y += 24

        g.text(font, I18n.text("shigusdream.stats.top_actions"), left, y, 0xFFA0A0B0.toInt()); y += 14
        for ((action, count) in stats.topActions) {
            g.text(font, Component.literal("  $action — $count"), left, y, 0xFFC8C8D8.toInt()); y += 13
        }
        y += 10

        g.text(font, I18n.text("shigusdream.stats.top_targets"), left, y, 0xFFA0A0B0.toInt()); y += 14
        for ((target, count) in stats.topTargets) {
            g.text(font, Component.literal("  $target — $count"), left, y, 0xFFC8C8D8.toInt()); y += 13
        }
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}
