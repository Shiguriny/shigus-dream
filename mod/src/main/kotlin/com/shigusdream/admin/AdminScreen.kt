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
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/**
 * Admin Panel: левое меню в едином тёмном стиле, справа сверху бейдж версии,
 * справа — MiniMessage-эдитор для show_message. Target и Action — выпадающие списки.
 */
class AdminScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Admin Panel")) {

    private companion object {
        const val LEFT = 8
        const val W = 220
        const val ROW = 14
        const val DD_TARGET_Y = 34
        const val DD_ACTION_Y = 74
        const val DD_H = 20
        const val MAX_SUGGESTIONS = 8
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_WHITE = -1
        const val C_DIM = 0xFF909090.toInt()
        const val C_ACCENT = 0xFF7C5CFF.toInt()
        const val C_BG_BOX = 0xFF22223A.toInt()
        const val C_BG_HOVER = 0xFF3A3A55.toInt()
        const val C_BG_LIST = 0xF0101020.toInt()
        const val C_BG_SUGGEST = 0xF0080818.toInt()
        const val C_PURPLE = 0xFFB088FF.toInt()
        const val C_YELLOW = 0xFFFFD76E.toInt()
        const val C_FIELD = 0xFFC8C8D8.toInt()
    }

    private var selectedTarget: String = ""
    private var selectedAction: ClientAction? = null
    private val fieldValues = LinkedHashMap<String, String>()
    private val fieldWidgets = mutableListOf<Pair<SchemaField, EditBox>>()
    private var statusLine: String = ""

    private var versionComponent: MutableComponent = Component.empty()

    private enum class Dropdown { NONE, TARGET, ACTION }
    private var openDropdown = Dropdown.NONE

    private var soundBox: EditBox? = null
    private var soundSuggestions: List<String> = emptyList()
    private var soundPreviewRect: Pair<Int, Int>? = null

    private var editor: MiniMessageEditor? = null
    private var textField: EditBox? = null

    private var mouseX = 0.0
    private var mouseY = 0.0
    private var settingsRect: Pair<Int, Int>? = null
    private var settingsLabel: Component = Component.literal("⚙ Настройки")

    /** Виджеты, скрываемые при открытом выпадающем списке (иначе рисуются поверх списка). */
    private fun setFieldsVisible(visible: Boolean) {
        fieldWidgets.forEach { (_, widget) -> widget.visible = visible }
        editor?.setWidgetsVisible(visible)
    }

    override fun init() {
        val users = ShigusDreamRuntime.presenceUsers
        selectedTarget = selectedTarget.takeIf { t -> users.any { it.username == t } }
            ?: users.firstOrNull()?.username
            ?: "(нет данных)"

        versionComponent = Component.literal("[Shigu's Dream v${ShigusDreamClient.modVersion()}]")
            .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }

        val actions = ShigusDreamClient.registry.all()
        if (selectedAction == null || selectedAction !in actions) selectedAction = actions.first()

        buildArgumentFields(selectedAction!!)

        addRenderableWidget(UiButton(LEFT, height - 50, W, 20, "SEND", { send() }, true))
        addRenderableWidget(UiButton(LEFT, height - 26, W, 20, "Закрыть", { onClose() }))
    }

    private fun rebuildArgumentFields() {
        fieldWidgets.forEach { (_, widget) -> removeWidget(widget) }
        fieldWidgets.clear()
        soundBox = null
        soundSuggestions = emptyList()
        editor?.removeWidgets()
        editor = null
        textField = null
        buildArgumentFields(selectedAction ?: return)
    }

    private fun buildArgumentFields(action: ClientAction) {
        var y = 118
        for (field in action.schema.fields) {
            val widget = EditBox(font, LEFT, y + 10, W, 16, Component.literal(field.key))
            widget.setMaxLength(256)
            if (field.description.isNotBlank()) widget.setHint(Component.literal(field.description))
            widget.setValue(fieldValues[field.key] ?: defaultFor(field))
            if (field.key == "sound") {
                widget.setResponder { value -> updateSoundSuggestions(value) }
                soundBox = widget
            }
            if (field.key == "text" && action.id == "shigusdream:show_message") {
                textField = widget
                editor = MiniMessageEditor(
                    widget, LEFT + W + 16, 34, 200,
                    { w -> addEditorWidget(w) },
                    { w -> removeEditorWidget(w) },
                ).also { it.initWidgets() }
            }
            addRenderableWidget(widget)
            fieldWidgets += field to widget
            y += 34
        }
    }

    /** Виджеты эдитора добавляются через эти хелперы (addRenderableWidget/removeWidget — protected). */
    fun addEditorWidget(widget: net.minecraft.client.gui.components.AbstractWidget) = addRenderableWidget(widget)
    fun removeEditorWidget(widget: net.minecraft.client.gui.components.AbstractWidget) = removeWidget(widget)

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

        editor?.let { if (it.handleHexClick(mx, my)) return true }

        // ⚙ Настройки
        settingsRect?.let { (rx, ry) ->
            if (mx >= rx && mx <= rx + font.width(settingsLabel) && my >= ry && my <= ry + 12) {
                Minecraft.getInstance().setScreen(com.shigusdream.config.ConfigScreen(this))
                return true
            }
        }

        // Превью звука: ▶ справа от поля sound
        soundPreviewRect?.let { (rx, ry) ->
            if (mx in rx..(rx + 14) && my in ry..(ry + 16)) {
                val value = soundBox?.getValue()?.trim()
                if (!value.isNullOrBlank()) {
                    com.shigusdream.client.SoundPlayback.play(value, 0.6f, 1.0f)
                }
                return true
            }
        }

        // Автодополнение звука: клик по строке подставляет значение и проигрывает превью.
        val sb = soundBox
        if (sb != null && soundSuggestions.isNotEmpty()) {
            val listY = sb.y + 16
            val listBottom = listY + soundSuggestions.size * ROW
            if (mx in LEFT..(LEFT + W) && my >= listY && my < listBottom) {
                val idx = ((my - listY) / ROW).coerceIn(0, soundSuggestions.size - 1)
                val picked = soundSuggestions[idx]
                sb.setValue(picked)
                com.shigusdream.client.SoundPlayback.play(picked, 0.4f, 1.0f)
                soundSuggestions = emptyList()
                return true
            }
        }

        val inTarget = mx in LEFT..(LEFT + W) && my >= DD_TARGET_Y && my < DD_TARGET_Y + DD_H
        val inAction = mx in LEFT..(LEFT + W) && my >= DD_ACTION_Y && my < DD_ACTION_Y + DD_H

        if (openDropdown != Dropdown.NONE) {
            val listY = if (openDropdown == Dropdown.TARGET) DD_TARGET_Y + DD_H else DD_ACTION_Y + DD_H
            val rowIdx = ((my - listY) / ROW).toInt()
            if (my >= listY && mx in LEFT..(LEFT + W)) {
                if (openDropdown == Dropdown.TARGET) {
                    ShigusDreamRuntime.presenceUsers.getOrNull(rowIdx)?.let { selectedTarget = it.username }
                } else {
                    ShigusDreamClient.registry.all().getOrNull(rowIdx)?.let {
                        selectedAction = it
                        rebuildArgumentFields()
                    }
                }
            }
            openDropdown = Dropdown.NONE
            setFieldsVisible(true)
            return true
        }
        if (inTarget) {
            setFieldsVisible(false)
            openDropdown = Dropdown.TARGET
            return true
        }
        if (inAction) {
            setFieldsVisible(false)
            openDropdown = Dropdown.ACTION
            return true
        }

        return super.mouseClicked(e, doubled)
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        if (openDropdown != Dropdown.NONE) {
            openDropdown = Dropdown.NONE
            return true
        }
        return super.mouseScrolled(x, y, xDelta, yDelta)
    }

    // ------------------------------------------------------------------ helpers

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

        g.text(font, Component.literal("Target"), LEFT, 22, C_LABEL)
        g.text(font, Component.literal("Action"), LEFT, 62, C_LABEL)
        g.text(font, Component.literal("Arguments"), LEFT, 106, C_LABEL)

        g.text(font, versionComponent, width - font.width(versionComponent) - 4, 6, C_WHITE)

        // ⚙ Настройки под бейджем версии
        val sw = font.width(settingsLabel)
        val sx = width - sw - 4
        settingsRect = sx to 18
        val sHovered = mouseX >= sx && mouseX <= sx + sw && mouseY >= 18 && mouseY <= 30
        g.text(font, settingsLabel, sx, 18, if (sHovered) C_YELLOW else C_LABEL)

        drawDropdownHeader(g, DD_TARGET_Y, targetLabel(selectedTarget, ShigusDreamRuntime.presenceUsers.firstOrNull { it.username == selectedTarget }?.online ?: false), selectedTarget == "(нет данных)")

        val action = selectedAction
        drawDropdownHeader(g, DD_ACTION_Y, Component.literal(action?.displayName ?: ""), false)

        var y = 118
        for ((field, _) in fieldWidgets) {
            val label = Component.literal("${field.key} (${field.type.wireName})")
            if (field.required) {
                label.append(Component.literal(" *").withStyle { it.withColor(TextColor.fromRgb(0xFFFF5555.toInt())) })
            }
            g.text(font, label, LEFT, y, C_FIELD)
            // Кнопка ▶ превью для поля sound
            if (field.key == "sound" && soundBox != null) {
                val rx = LEFT + W + 4
                val ry = y + 10
                soundPreviewRect = rx to ry
                val hovered = mouseX >= rx && mouseX <= rx + 14 && mouseY >= ry && mouseY <= ry + 16
                g.fill(rx, ry, rx + 14, ry + 16, if (hovered) 0xFF3A3A55.toInt() else 0xFF22223A.toInt())
                g.text(font, Component.literal("▶"), rx + 3, ry + 4, 0xFF55FF55.toInt())
            }
            y += 34
        }

        val sb = soundBox
        if (sb != null && soundSuggestions.isNotEmpty()) {
            drawSuggestions(g, sb.y + 16, soundSuggestions)
        }

        editor?.render(g, mouseX.toDouble(), mouseY.toDouble())

        val online = ShigusDreamRuntime.presenceUsers.count { it.online }
        g.text(
            font,
            Component.literal("Онлайн: $online | Роль: ${ShigusDreamClient.myRole ?: "?"}"),
            LEFT, height - 68, C_DIM,
        )
        if (statusLine.isNotBlank()) {
            g.text(font, Component.literal(stripLegacy(statusLine)), LEFT, height - 82, C_WHITE)
        }

        // Выпадающие списки — самыми последними, чтобы перекрывать любые подписи и панели
        if (openDropdown == Dropdown.TARGET) {
            drawTargetList(g)
        }
        if (openDropdown == Dropdown.ACTION) {
            drawActionList(g)
        }
    }

    private fun drawDropdownHeader(g: GuiGraphicsExtractor, y: Int, label: Component, dim: Boolean) {
        val hovered = mouseY >= y && mouseY <= y + DD_H && mouseX >= LEFT && mouseX <= LEFT + W
        g.fill(LEFT, y, LEFT + W, y + DD_H, if (hovered) C_BG_HOVER else C_BG_BOX)
        g.fill(LEFT, y, LEFT + 1, y + DD_H, C_ACCENT)
        g.text(font, label, LEFT + 6, y + 6, if (dim) C_DIM else C_WHITE)
        g.text(font, Component.literal("▼"), LEFT + W - 14, y + 6, C_LABEL)
    }

    private fun drawTargetList(g: GuiGraphicsExtractor) {
        val users = ShigusDreamRuntime.presenceUsers
        if (users.isEmpty()) {
            g.fill(LEFT, DD_TARGET_Y + DD_H, LEFT + W, DD_TARGET_Y + DD_H + ROW, C_BG_LIST)
            g.text(font, Component.literal("(нет данных)"), LEFT + 6, DD_TARGET_Y + DD_H + 3, C_DIM)
            return
        }
        val listY = DD_TARGET_Y + DD_H
        val listH = users.size * ROW
        g.fill(LEFT, listY, LEFT + W, listY + listH, C_BG_LIST)
        g.fill(LEFT, listY, LEFT + 1, listY + listH, C_ACCENT)
        var y = listY
        for (user in users) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER)
            g.text(font, targetLabel(user.username, user.online), LEFT + 6, y + 3, C_WHITE)
            y += ROW
        }
    }

    private fun drawActionList(g: GuiGraphicsExtractor) {
        val actions = ShigusDreamClient.registry.all()
        val listY = DD_ACTION_Y + DD_H
        val listH = actions.size * ROW
        g.fill(LEFT, listY, LEFT + W, listY + listH, C_BG_LIST)
        g.fill(LEFT, listY, LEFT + 1, listY + listH, C_ACCENT)
        var y = listY
        for (action in actions) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER)
            val label = Component.literal(action.displayName).let {
                if (action == selectedAction) {
                    it.withStyle { st -> st.withColor(TextColor.fromRgb(0xB088FF)) }
                } else {
                    it
                }
            }
            g.text(font, label, LEFT + 6, y + 3, C_WHITE)
            y += ROW
        }
    }

    private fun drawSuggestions(g: GuiGraphicsExtractor, startY: Int, suggestions: List<String>) {
        val listH = suggestions.size * ROW
        g.fill(LEFT, startY, LEFT + W, startY + listH, C_BG_SUGGEST)
        var y = startY
        for (suggestion in suggestions) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER)
            g.text(font, Component.literal(suggestion), LEFT + 6, y + 3, if (hovered) C_YELLOW else C_FIELD)
            y += ROW
        }
    }
}
