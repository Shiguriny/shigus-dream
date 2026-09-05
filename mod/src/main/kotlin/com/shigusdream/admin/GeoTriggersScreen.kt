package com.shigusdream.admin

import com.shigusdream.ShigusDreamClient
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Гео-триггеры: зоны, запускающие сценарий при входе игрока. */
class GeoTriggersScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.triggers.title")) {

    private var left = 12
    private var boxWidth = 460
    private val radii = intArrayOf(8, 16, 32, 64)
    private var radiusIdx = 1
    private var currentScenarioText: String = ""

    override fun init() {
        clearWidgets()
        boxWidth = minOf(500, width - 24).coerceAtLeast(240)
        left = (width - boxWidth) / 2
        addRenderableWidget(
            UiButton(left, height - 78, (boxWidth - 6) / 2, 20, I18n.text("shigusdream.triggers.create_here"), {
                val mc = Minecraft.getInstance()
                val player = mc.player ?: return@UiButton
                val scenario = ScenarioStore.current?.name
                if (scenario == null) {
                    ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Сначала выберите сценарий")
                    return@UiButton
                }
                val name = I18n.text("shigusdream.triggers.generated", AdminDataStore.triggers.size + 1)
                val dim = mc.level?.dimension()?.identifier()?.toString() ?: "minecraft:overworld"
                AdminDataStore.createTrigger(
                    GeoTrigger(
                        name = name, dimension = dim,
                        x = player.x, y = player.y, z = player.z,
                        radius = radii[radiusIdx].toDouble(), scenario = scenario,
                    ),
                )
            }, true),
        )
        addRenderableWidget(
            UiButton(left + (boxWidth + 6) / 2, height - 78, (boxWidth + 6) / 2, 20, I18n.text("shigusdream.triggers.radius", radii[radiusIdx]), {
                radiusIdx = (radiusIdx + 1) % radii.size
                init()
            }),
        )
        currentScenarioText = com.shigusdream.admin.ScenarioStore.current?.name ?: "(не выбран — откройте панель и выберите)"
        addRenderableWidget(
            UiButton(left, height - 28, boxWidth, 20, I18n.text("shigusdream.common.back"), ::onClose),
        )
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.text(font, title, left, 14, -1)
        g.text(font, I18n.text("shigusdream.triggers.help"), left, 28, 0xFFA0A0B0.toInt())
        g.text(font, I18n.component("shigusdream.triggers.current_scenario", currentScenarioText), left, 40, 0xFFB088FF.toInt())

        var y = 44
        val triggers = AdminDataStore.triggers
        if (triggers.isEmpty()) {
            g.text(font, I18n.text("shigusdream.triggers.empty"), left, y, 0xFF909090.toInt())
        }
        for (trigger in triggers) {
            val state = if (trigger.enabled) "§aвкл" else "§7выкл"
            g.text(
                font,
                Component.literal("[$state] ${trigger.name}: ${trigger.scenario} @ (${trigger.x.toInt()}, ${trigger.y.toInt()}, ${trigger.z.toInt()}) r=${trigger.radius.toInt()}"),
                left, y, 0xFFC8C8D8.toInt(),
            )
            y += 16
        }
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}

