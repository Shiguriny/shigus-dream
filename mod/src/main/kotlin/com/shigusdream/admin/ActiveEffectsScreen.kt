package com.shigusdream.admin

import com.shigusdream.client.ActiveEffectInfo
import com.shigusdream.client.ActiveEffects
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ActiveEffectsScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.effects.title")) {

    private var initial: List<ActiveEffectInfo> = emptyList()
    private var left = 12
    private var boxWidth = 360

    override fun init() {
        clearWidgets()
        initial = ActiveEffects.snapshot()
        boxWidth = minOf(420, width - 24).coerceAtLeast(180)
        left = (width - boxWidth) / 2
        initial.forEachIndexed { index, effect ->
            addRenderableWidget(UiButton(left + boxWidth - 92, 48 + index * 28, 88, 20,
                I18n.component("shigusdream.common.cancel"), { ActiveEffects.cancel(effect.id) }))
        }
        addRenderableWidget(UiButton(left, height - 54, boxWidth, 20,
            I18n.component("shigusdream.effects.cancel_all"), ActiveEffects::cancelAll, true))
        addRenderableWidget(UiButton(left, height - 29, boxWidth, 20,
            I18n.component("shigusdream.common.back"), ::onClose))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.outline(left - 8, 8, left + boxWidth + 8, height - 4, 0xFF34344C.toInt())
        g.text(font, title, left, 18, -1)
        val current = ActiveEffects.snapshot().associateBy { it.id }
        if (initial.isEmpty()) {
            g.text(font, I18n.component("shigusdream.effects.empty"), left, 50, 0xFF909090.toInt())
        }
        initial.forEachIndexed { index, original ->
            val effect = current[original.id]
            val seconds = ((effect?.remainingTicks ?: 0) / 20.0).coerceAtLeast(0.0)
            val line = if (effect == null) I18n.component("shigusdream.effects.finished", original.name)
                else I18n.component("shigusdream.effects.row", effect.name, String.format("%.1f", seconds))
            g.text(font, line, left, 54 + index * 28, if (effect == null) 0xFF707070.toInt() else 0xFFC8C8D8.toInt())
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}
