package com.shigusdream.config

import com.shigusdream.ShigusDreamClient
import com.shigusdream.admin.UiButton
import com.shigusdream.admin.UiToggle
import com.shigusdream.client.I18n
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

class SafetyScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.safety.title")) {

    private val blocked = ShigusDreamClient.config.blockedActions.toMutableSet()
    private var left = 12
    private var boxWidth = 400

    override fun init() {
        clearWidgets()
        boxWidth = minOf(440, width - 24).coerceAtLeast(220)
        left = (width - boxWidth) / 2
        ShigusDreamClient.registry.all().forEachIndexed { index, action ->
            addRenderableWidget(UiToggle(left, 42 + index * 24, boxWidth, 18,
                I18n.component("shigusdream.action.${action.id.substringAfter(':')}.name"), action.id !in blocked) { allowed ->
                if (allowed) blocked.remove(action.id) else blocked.add(action.id)
            })
        }
        addRenderableWidget(UiButton(left, height - 78, (boxWidth - 6) / 2, 20,
            I18n.component("shigusdream.safety.recommended"), {
                blocked.clear()
                blocked += setOf("shigusdream:freeze_controls", "shigusdream:send_chat", "shigusdream:set_slot")
                refreshSafetyWidgets()
            }))
        addRenderableWidget(UiButton(left + (boxWidth + 6) / 2, height - 78, (boxWidth - 6) / 2, 20,
            I18n.component("shigusdream.safety.allow_all"), { blocked.clear(); refreshSafetyWidgets() }))
        addRenderableWidget(UiButton(left, height - 53, boxWidth, 20,
            I18n.component("shigusdream.common.save"), ::save, true))
        addRenderableWidget(UiButton(left, height - 28, boxWidth, 20,
            I18n.component("shigusdream.common.back"), ::onClose))
    }

    private fun refreshSafetyWidgets() = init()

    private fun save() {
        val cfg = ShigusDreamClient.config.copy(blockedActions = blocked.toSet())
        ModConfig.save(FabricLoader.getInstance().configDir, cfg)
        ShigusDreamClient.applyConfig(cfg)
        onClose()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.outline(left - 8, 8, left + boxWidth + 8, height - 4, 0xFF34344C.toInt())
        g.text(font, title, left, 14, -1)
        g.text(font, I18n.component("shigusdream.safety.help"), left, 28, 0xFFA0A0B0.toInt())
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}
