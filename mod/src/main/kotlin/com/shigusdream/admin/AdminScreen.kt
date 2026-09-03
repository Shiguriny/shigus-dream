package com.shigusdream.admin

import com.google.gson.JsonObject
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
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/**
 * Admin Panel: меню прижато к левому краю, справа сверху — версия мода.
 * Target — выпадающий список со статусами игроков; поля ввода с подсказками;
 * для звука — автодополнение по реестру.
 * Весь кастомный текст рисуется Component-перегрузками (ваниль-паттерн 26.x).
 */
class AdminScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Admin Panel")) {

    private companion object {
        const val LEFT = 8
        const val W = 220
        const val ROW = 14
        const val DD_Y = 34
        const val DD_H = 20
        const val MAX_SUGGESTIONS = 8
        // ВАЖНО: цвет текста — полный ARGB. RGB с нулевым альфа-байтом = полностью прозрачный текст.
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_FIELD = 0xFFC8C8D8.toInt()
        const val C_WHITE = -1 // 0xFFFFFFFF
        const val C_DIM = 0xFF909090.toInt()
        const val C_ACCENT = 0xFF7C5CFF.toInt()
        const val C_BG_BOX = 0xFF2A2A44.toInt()
        const val C_BG_HOVER = 0xFF3A3A55.toInt()
        const val C_BG_HOVER2 = 0xFF3A3A66.toInt()
        const val C_BG_LIST = 0xF0101020.toInt()
        const val C_BG_SUGGEST = 0xF0080818.toInt()
        const val C_PURPLE = 0xFFB088FF.toInt()
        const val C_YELLOW = 0xFFFFD76E.toInt()
        const val C_RED = 0xFFFF5555.toInt()
    }

    private var selectedTarget: String = ""
    private var selectedAction: ClientAction? = null
    private val fieldValues = LinkedHashMap<String, String>()
    private val fieldWidgets = mutableListOf<Pair<SchemaField, EditBox>>()
    private var statusLine: String = ""

    private var versionComponent: MutableComponent = Component.empty()

    // Выпадающий список таргетов
    private var dropdownOpen = false

    // Автодополнение звука
    private var soundBox: EditBox? = null
    private var soundSuggestions: List<String> = emptyList()

    private var mouseX = 0.0
    private var mouseY = 0.0

    override fun init() {
        val users = ShigusDreamRuntime.presenceUsers
        selectedTarget = selectedTarget.takeIf { t -> users.any { it.username == t } }
            ?: users.firstOrNull()?.username
            ?: "(нет данных)"

        versionComponent = Component.literal("[Shigu's Dream v${ShigusDreamClient.modVersion()}]")
            .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }

        val actions = ShigusDreamClient.registry.all()
        if (selectedAction == null || selectedAction !in actions) selectedAction = actions.first()
        val action = selectedAction!!

        addRenderableWidget(
            CycleButton.builder<ClientAction>({ Component.literal(it.displayName) }, action)
                .withValues(actions)
                .create(LEFT, 74, W, 20, Component.literal("Action")) { _, value ->
                    selectedAction = value
                    rebuildArgumentFields()
                },
        )

        buildArgumentFields(action)

        addRenderableWidget(
            Button.builder(Component.literal("SEND")) { send() }
                .bounds(LEFT, height - 50, W, 20)
                .build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Закрыть")) { onClose() }
                .bounds(LEFT, height - 26, W, 20)
                .build(),
        )
    }

    private fun rebuildArgumentFields() {
        fieldWidgets.forEach { (_, widget) -> removeWidget(widget) }
        fieldWidgets.clear()
        soundBox = null
        soundSuggestions = emptyList()
        buildArgumentFields(selectedAction ?: return)
    }

    private fun buildArgumentFields(action: ClientAction) {
        var y = 112
        for (field in action.schema.fields) {
            val widget = EditBox(font, LEFT, y + 10, W, 16, Component.literal(field.key))
            widget.setMaxLength(256)
            if (field.description.isNotBlank()) widget.setHint(Component.literal(field.description))
            widget.setValue(fieldValues[field.key] ?: defaultFor(field))
            if (field.key == "sound") {
                widget.setResponder { value -> updateSoundSuggestions(value) }
                soundBox = widget
            }
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

    private fun updateSoundSuggestions(value: String) {
        val all = BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }
        soundSuggestions = (if (value.isBlank()) all else all.filter { it.contains(value, ignoreCase = true) })
            .sorted()
            .take(MAX_SUGGESTIONS)
    }

    private fun send() {
        for ((field, widget) in fieldWidgets) {
            fieldValues[field.key] = widget.getValue()
        }

        val args = JsonObject()
        for (field in selectedAction?.schema?.fields ?: emptyList()) {
            val raw = fieldValues[field.key]?.trim() ?: ""
            if (raw.isEmpty()) continue
            when (field.type) {
                FieldType.INT -> {
                    val v = raw.toIntOrNull()
                    if (v == null) {
                        statusLine = "'${field.key}' должен быть целым числом"
                        return
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.FLOAT -> {
                    val v = raw.toFloatOrNull()
                    if (v == null) {
                        statusLine = "'${field.key}' должен быть числом"
                        return
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.BOOL -> {
                    if (raw != "true" && raw != "false") {
                        statusLine = "'${field.key}' должен быть true/false"
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
            statusLine = errors.joinToString("; ")
            return
        }

        if (selectedTarget == "(нет данных)") {
            statusLine = "Нет данных presence — переподключитесь (J)"
            return
        }

        statusLine = "Отправка..."
        ShigusDreamClient.connection.sendExecute(selectedTarget, action.id, args)
    }

    // ------------------------------------------------------------------ mouse

    override fun mouseClicked(e: MouseButtonEvent, doubled: Boolean): Boolean {
        mouseX = e.x
        mouseY = e.y
        val mx = e.x.toInt()
        val my = e.y.toInt()

        // Автодополнение звука: клик по строке подставляет значение.
        val sb = soundBox
        if (sb != null && soundSuggestions.isNotEmpty()) {
            val listY = sb.y + 16
            val listBottom = listY + soundSuggestions.size * ROW
            if (mx in LEFT..(LEFT + W) && my >= listY && my < listBottom) {
                val idx = ((my - listY) / ROW).coerceIn(0, soundSuggestions.size - 1)
                sb.setValue(soundSuggestions[idx])
                soundSuggestions = emptyList()
                return true
            }
        }

        // Выпадающий список таргетов.
        val inDropdownArea = mx in LEFT..(LEFT + W) && my >= DD_Y && my < DD_Y + DD_H
        if (dropdownOpen) {
            val users = ShigusDreamRuntime.presenceUsers
            val listY = DD_Y + DD_H
            val picked = users.getOrNull(((my - listY) / ROW).toInt())
                ?.takeIf { mx in LEFT..(LEFT + W) && my >= listY }
            picked?.let {
                selectedTarget = it.username
                statusLine = ""
            }
            dropdownOpen = false
            return true
        }
        if (inDropdownArea) {
            dropdownOpen = true
            return true
        }

        return super.mouseClicked(e, doubled)
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        if (dropdownOpen) {
            dropdownOpen = false
            return true
        }
        return super.mouseScrolled(x, y, xDelta, yDelta)
    }

    // ------------------------------------------------------------------ components for custom text

    private fun statusLabel(online: Boolean): MutableComponent =
        Component.literal("● ").withStyle { it.withColor(TextColor.fromRgb(if (online) 0x55FF55 else 0x707070)) }

    private fun targetLabel(username: String, online: Boolean): MutableComponent =
        statusLabel(online).append(Component.literal(username).withStyle { it.withColor(TextColor.fromRgb(C_WHITE)) })

    private fun stripLegacy(text: String): String = text.replace(Regex("§."), "")

    // ------------------------------------------------------------------ render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(g, mouseX, mouseY, delta)
        this.mouseX = mouseX.toDouble()
        this.mouseY = mouseY.toDouble()

        // Заголовки секций
        g.text(font, Component.literal("Target"), LEFT, 22, C_LABEL)
        g.text(font, Component.literal("Action"), LEFT, 62, C_LABEL)
        g.text(font, Component.literal("Arguments"), LEFT, 100, C_LABEL)

        // Версия справа сверху (цвет задан стилем компонента)
        g.text(font, versionComponent, width - font.width(versionComponent) - 4, 6, C_WHITE)

        // Поле таргета (кнопка выпадающего списка)
        drawDropdownButton(g)

        // Открытый список таргетов — поверх остальных виджетов
        if (dropdownOpen) {
            drawDropdownList(g)
        }

        // Подписи полей аргументов
        var y = 112
        for ((field, _) in fieldWidgets) {
            val label = Component.literal("${field.key} (${field.type.wireName})")
            if (field.required) {
                label.append(Component.literal(" *").withStyle { it.withColor(TextColor.fromRgb(0xFF5555)) })
            }
            g.text(font, label, LEFT, y, C_WHITE)
            y += 34
        }

        // Автодополнение звука — поверх всего
        val sb = soundBox
        if (sb != null && soundSuggestions.isNotEmpty()) {
            drawSuggestions(g, sb.y + 16, soundSuggestions)
        }

        // Нижние строки
        val online = ShigusDreamRuntime.presenceUsers.count { it.online }
        g.text(
            font,
            Component.literal("Онлайн: $online | Соединение: ${ShigusDreamClient.connection.currentState}"),
            LEFT, height - 68, C_DIM,
        )
        if (statusLine.isNotBlank()) {
            g.text(font, Component.literal(stripLegacy(statusLine)), LEFT, height - 82, C_WHITE)
        }
    }

    private fun drawDropdownButton(g: GuiGraphicsExtractor) {
        val hovered = mouseX >= LEFT && mouseX <= LEFT + W && mouseY >= DD_Y && mouseY <= DD_Y + DD_H
        g.fill(LEFT, DD_Y, LEFT + W, DD_Y + DD_H, if (hovered) C_BG_HOVER else C_BG_BOX)
        g.fill(LEFT, DD_Y, LEFT + 1, DD_Y + DD_H, C_ACCENT)
        val users = ShigusDreamRuntime.presenceUsers
        val selected = users.firstOrNull { it.username == selectedTarget }
        val label = if (selectedTarget == "(нет данных)") {
            Component.literal("(нет данных)").withStyle { it.withColor(TextColor.fromRgb(C_DIM)) }
        } else {
            targetLabel(selectedTarget, selected?.online ?: false)
        }
        g.text(font, label, LEFT + 6, DD_Y + 6, C_WHITE)
        g.text(font, Component.literal("▼"), LEFT + W - 14, DD_Y + 6, C_LABEL)
    }

    private fun drawDropdownList(g: GuiGraphicsExtractor) {
        val users = ShigusDreamRuntime.presenceUsers
        if (users.isEmpty()) {
            g.fill(LEFT, DD_Y + DD_H, LEFT + W, DD_Y + DD_H + ROW, C_BG_LIST)
            g.text(font, Component.literal("(нет данных)"), LEFT + 6, DD_Y + DD_H + 3, C_DIM)
            return
        }
        val listY = DD_Y + DD_H
        val listH = users.size * ROW
        g.fill(LEFT, listY, LEFT + W, listY + listH, C_BG_LIST)
        g.fill(LEFT, listY, LEFT + 1, listY + listH, C_ACCENT)

        var y = listY
        for (user in users) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) {
                g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER2)
            }
            g.text(font, targetLabel(user.username, user.online), LEFT + 6, y + 3, C_WHITE)
            y += ROW
        }
    }

    private fun drawSuggestions(g: GuiGraphicsExtractor, startY: Int, suggestions: List<String>) {
        val listH = suggestions.size * ROW
        g.fill(LEFT, startY, LEFT + W, startY + listH, C_BG_SUGGEST)
        var y = startY
        for (suggestion in suggestions) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) {
                g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER2)
            }
            g.text(
                font,
                Component.literal(suggestion),
                LEFT + 6, y + 3,
                if (hovered) C_YELLOW else C_FIELD,
            )
            y += ROW
        }
    }
}
