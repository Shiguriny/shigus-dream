package com.shigusdream.admin

import com.shigusdream.ShigusDreamClient
import com.shigusdream.ShigusDreamRuntime
import com.shigusdream.actions.ActionValidator
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Admin Panel: Target | Action | динамические аргументы по схеме | SEND.
 * Поля ввода строятся динамически из ActionSchema выбранного действия.
 */
class AdminScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Admin Panel")) {

    private var selectedTarget: String = ""
    private var selectedAction: ClientAction? = null
    private val fieldValues = LinkedHashMap<String, String>()
    private val fieldWidgets = mutableListOf<Pair<SchemaField, EditBox>>()
    private var statusLine: String = ""

    override fun init() {
        val users = ShigusDreamRuntime.presenceUsers
        val names = users.map { it.username }.ifEmpty { listOf("(нет данных)") }
        selectedTarget = selectedTarget.takeIf { it in names } ?: names.first()

        val actions = ShigusDreamClient.registry.all()
        if (selectedAction == null || selectedAction !in actions) selectedAction = actions.first()
        val action = selectedAction!!

        addRenderableWidget(
            CycleButton.builder<String>({ Component.literal(it) }, selectedTarget)
                .withValues(names)
                .create(width / 2 - 100, 40, 200, 20, Component.literal("Target")) { _, value ->
                    selectedTarget = value
                },
        )

        addRenderableWidget(
            CycleButton.builder<ClientAction>({ Component.literal(it.displayName) }, action)
                .withValues(actions)
                .create(width / 2 - 100, 72, 200, 20, Component.literal("Action")) { _, value ->
                    selectedAction = value
                    rebuildArgumentFields()
                },
        )

        buildArgumentFields(action)

        addRenderableWidget(
            Button.builder(Component.literal("SEND")) { send() }
                .bounds(width / 2 - 100, height - 50, 200, 20)
                .build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Закрыть")) { onClose() }
                .bounds(width / 2 - 100, height - 26, 200, 20)
                .build(),
        )
    }

    /** Пересобирает поля ввода под схему выбранного действия. */
    private fun rebuildArgumentFields() {
        fieldWidgets.forEach { (_, widget) -> removeWidget(widget) }
        fieldWidgets.clear()
        fieldValues.clear()
        buildArgumentFields(selectedAction ?: return)
    }

    private fun buildArgumentFields(action: ClientAction) {
        var y = 118
        for (field in action.schema.fields) {
            val widget = EditBox(font, width / 2 - 100, y + 10, 200, 16, Component.literal(field.key))
            widget.setMaxLength(256)
            widget.setValue(fieldValues[field.key] ?: defaultFor(field))
            addRenderableWidget(widget)
            fieldWidgets += field to widget
            y += 34
        }
    }

    private fun defaultFor(field: SchemaField): String = when {
        field.allowedValues != null -> field.allowedValues.first()
        field.default != null -> field.default.toString()
        else -> ""
    }

    private fun send() {
        for ((field, widget) in fieldWidgets) {
            fieldValues[field.key] = widget.getValue()
        }

        val args = com.google.gson.JsonObject()
        for (field in selectedAction?.schema?.fields ?: emptyList()) {
            val raw = fieldValues[field.key]?.trim() ?: ""
            if (raw.isEmpty()) continue
            when (field.type) {
                FieldType.INT -> {
                    val v = raw.toIntOrNull()
                    if (v == null) {
                        statusLine = "§c'${field.key}' должен быть целым числом"
                        return
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.FLOAT -> {
                    val v = raw.toFloatOrNull()
                    if (v == null) {
                        statusLine = "§c'${field.key}' должен быть числом"
                        return
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.BOOL -> {
                    if (raw != "true" && raw != "false") {
                        statusLine = "§c'${field.key}' должен быть true/false"
                        return
                    }
                    args.addProperty(field.key, raw.toBoolean())
                }

                else -> args.addProperty(field.key, raw)
            }
        }

        val action = selectedAction ?: return
        val errors = ActionValidator.validate(action.schema, args)
        if (errors.isNotEmpty()) {
            statusLine = "§c" + errors.joinToString("; ")
            return
        }

        if (selectedTarget == "(нет данных)") {
            statusLine = "§cНет данных presence — переподключитесь (J)"
            return
        }

        statusLine = "§7Отправка..."
        ShigusDreamClient.connection.sendExecute(selectedTarget, action.id, args)
    }

    override fun tick() {
        super.tick()
        if (ShigusDreamClient.lastResultText.isNotBlank()) {
            statusLine = ShigusDreamClient.lastResultText
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        g.centeredText(font, title, width / 2, 12, 0xFFFFFF)

        g.text(font, "Target", width / 2 - 100, 28, 0xA0A0B0)
        g.text(font, "Action", width / 2 - 100, 60, 0xA0A0B0)
        g.text(font, "Arguments", width / 2 - 100, 104, 0xA0A0B0)

        var y = 118
        for ((field, _) in fieldWidgets) {
            val label = "${field.key} (${field.type.wireName}${if (field.required) ", required" else ""})"
            g.text(font, label, width / 2 - 100, y, 0xC8C8D8)
            y += 34
        }

        val online = ShigusDreamRuntime.presenceUsers.count { it.online }
        g.centeredText(font, "Онлайн: $online | Соединение: ${ShigusDreamClient.connection.currentState}", width / 2, height - 70, 0x909090)
        if (statusLine.isNotBlank()) {
            g.centeredText(font, Component.literal(statusLine), width / 2, height - 84, 0xFFFFFF)
        }
    }
}
