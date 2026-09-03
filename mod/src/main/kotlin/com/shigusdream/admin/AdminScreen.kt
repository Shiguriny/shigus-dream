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
 * Admin Panel: табы «Действия | Сценарии». Действия — левое меню в тёмном стиле,
 * справа MiniMessage-эдитор для show_message. Сценарии — цепочки шагов с задержками.
 */
class AdminScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Admin Panel")) {

    private companion object {
        const val LEFT = 8
        const val W = 220
        const val ROW = 14
        const val DD_TARGET_Y = 34
        const val DD_ACTION_Y = 74
        const val DD_SCEN_Y = 34
        const val DD_H = 20
        const val MAX_SUGGESTIONS = 8
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_FIELD = 0xFFC8C8D8.toInt()
        const val C_WHITE = -1
        const val C_DIM = 0xFF909090.toInt()
        const val C_ACCENT = 0xFF7C5CFF.toInt()
        const val C_BG_BOX = 0xFF22223A.toInt()
        const val C_BG_HOVER = 0xFF3A3A55.toInt()
        const val C_BG_HOVER2 = 0xFF3A3A66.toInt()
        const val C_BG_LIST = 0xF0101020.toInt()
        const val C_BG_SUGGEST = 0xF0080818.toInt()
        const val C_PURPLE = 0xFFB088FF.toInt()
        const val C_YELLOW = 0xFFFFD76E.toInt()
        const val C_RED = 0xFFFF5555.toInt()
        const val C_GREEN = 0xFF55FF55.toInt()
    }

    private enum class PanelMode { ACTIONS, SCENARIOS }
    private enum class Dropdown { NONE, TARGET, ACTION, SCENARIO }

    private var panelMode = PanelMode.ACTIONS
    private var openDropdown = Dropdown.NONE

    private var selectedTarget: String = ""
    private var selectedAction: ClientAction? = null
    private val fieldValues = LinkedHashMap<String, String>()
    private val fieldWidgets = mutableListOf<Pair<SchemaField, EditBox>>()
    private var statusLine: String = ""
    private var statusColor: Int = C_WHITE

    private var versionComponent: MutableComponent = Component.empty()

    private var soundBox: EditBox? = null
    private var soundSuggestions: List<String> = emptyList()
    private var soundPreviewRect: Pair<Int, Int>? = null

    private var editor: MiniMessageEditor? = null
    private var textField: EditBox? = null

    // Сценарии
    private var scenarioDelayBox: EditBox? = null
    private var selectedStepIdx = -1
    private var stepsScroll = 0

    private var mouseX = 0.0
    private var mouseY = 0.0
    private var settingsRect: Pair<Int, Int>? = null
    private val settingsLabel = Component.literal("⚙ Настройки")

    override fun init() {
        val users = ShigusDreamRuntime.presenceUsers
        selectedTarget = selectedTarget.takeIf { t -> users.any { it.username == t } }
            ?: users.firstOrNull()?.username
            ?: "(нет данных)"

        versionComponent = Component.literal("[Shigu's Dream v${ShigusDreamClient.modVersion()}]")
            .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }

        val actions = ShigusDreamClient.registry.all()
        if (selectedAction == null || selectedAction !in actions) selectedAction = actions.first()

        scenarioDelayBox = EditBox(font, LEFT + 150, height - 100, 70, 14, Component.literal("delay")).apply {
            setMaxLength(5)
            addRenderableWidget(this)
        }
        scenarioDelayBox?.setResponder { value ->
            val scenario = ScenarioStore.current
            val step = scenario?.steps?.getOrNull(selectedStepIdx)
            if (step != null) {
                step.delay = value.toIntOrNull()?.coerceIn(0, 1200) ?: step.delay
                ScenarioStore.save()
            }
        }

        buildArgumentFields(selectedAction!!)

        addRenderableWidget(UiButton(LEFT, height - 50, W, 20, "SEND", { send() }, true))
        addRenderableWidget(UiButton(LEFT, height - 26, W, 20, "Закрыть", { onClose() }))

        val scenario = ScenarioStore.current
        addRenderableWidget(UiButton(LEFT, height - 76, 100, 18, "＋ Шаг из текущих", { addStepFromCurrent() }))
        addRenderableWidget(UiButton(LEFT + 104, height - 76, 32, 18, "▲", { moveStep(-1) }))
        addRenderableWidget(UiButton(LEFT + 140, height - 76, 32, 18, "▼", { moveStep(1) }))
        addRenderableWidget(UiButton(LEFT + 176, height - 76, 52, 18, "✕ Шаг", { deleteStep() }))
        addRenderableWidget(UiButton(LEFT, height - 52, 100, 18, "▶ Запуск", { runScenario() }, true))
        addRenderableWidget(UiButton(LEFT + 104, height - 52, 124, 18, "■ Стоп", { ScenarioRunner.stop() }))

        applyModeVisibility()
    }

    /** Видимость виджетов в зависимости от режима (визуально режим отрисовывается всегда). */
    private fun applyModeVisibility() {
        val mainVisible = panelMode == PanelMode.ACTIONS
        fieldWidgets.forEach { (_, widget) -> widget.visible = mainVisible }
        soundBox?.visible = mainVisible
        editor?.setWidgetsVisible(mainVisible)
        scenarioDelayBox?.visible = panelMode == PanelMode.SCENARIOS && selectedStepIdx >= 0
    }

    private fun setPanelMode(mode: PanelMode) {
        if (panelMode == mode) return
        panelMode = mode
        openDropdown = Dropdown.NONE
        selectedStepIdx = -1
        stepsScroll = 0
        applyModeVisibility()
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
        applyModeVisibility()
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

    /** Собирает аргументы из текущих полей; null при ошибке валидации (текст ошибки в statusLine). */
    private fun buildArgsOrNull(): JsonObject? {
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
                        return null
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.FLOAT -> {
                    val v = raw.toFloatOrNull()
                    if (v == null) {
                        statusLine = "'${field.key}' должен быть числом"
                        return null
                    }
                    args.addProperty(field.key, v)
                }

                FieldType.BOOL -> {
                    if (raw != "true" && raw != "false") {
                        statusLine = "'${field.key}' должен быть true/false"
                        return null
                    }
                    args.addProperty(field.key, raw.toBoolean())
                }

                else -> args.addProperty(field.key, raw)
            }
        }
        val action = selectedAction ?: return null
        val errors = ActionValidator.validate(action.schema, args)
        if (errors.isNotEmpty()) {
            statusLine = errors.joinToString("; ")
            return null
        }
        return args
    }

    private fun send() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return

        if (selectedTarget == "(нет данных)") {
            statusLine = "Нет данных presence — переподключитесь (J)"
            return
        }

        statusLine = "Отправка..."
        ShigusDreamClient.connection.sendExecute(selectedTarget, action.id, args)
    }

    // ------------------------------------------------------------------ сценарии

    private fun addStepFromCurrent() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return
        if (selectedTarget == "(нет данных)") {
            statusLine = "Нет данных presence"
            return
        }
        val scenario = ScenarioStore.current ?: ScenarioStore.create("Сценарий 1")
        scenario.steps += ScenarioStep(selectedTarget, action.id, args, delay = 20)
        selectedStepIdx = scenario.steps.size - 1
        ScenarioStore.save()
        statusLine = "Шаг ${scenario.steps.size} добавлен"
    }

    private fun moveStep(dir: Int) {
        val scenario = ScenarioStore.current ?: return
        val from = selectedStepIdx
        val to = from + dir
        if (from < 0 || to < 0 || to >= scenario.steps.size) return
        val step = scenario.steps.removeAt(from)
        scenario.steps.add(to, step)
        selectedStepIdx = to
        ScenarioStore.save()
    }

    private fun deleteStep() {
        val scenario = ScenarioStore.current ?: return
        if (selectedStepIdx in scenario.steps.indices) {
            scenario.steps.removeAt(selectedStepIdx)
            selectedStepIdx = -1
            ScenarioStore.save()
        }
    }

    private fun runScenario() {
        val scenario = ScenarioStore.current ?: return
        ScenarioRunner.start(scenario)
    }

    // ------------------------------------------------------------------ mouse

    private fun tabsHitTest(mx: Int, my: Int): PanelMode? = when {
        my in 4..18 && mx in LEFT..(LEFT + 70) -> PanelMode.ACTIONS
        my in 4..18 && mx in (LEFT + 74)..(LEFT + 144) -> PanelMode.SCENARIOS
        else -> null
    }

    override fun mouseClicked(e: MouseButtonEvent, doubled: Boolean): Boolean {
        mouseX = e.x
        mouseY = e.y
        val mx = e.x.toInt()
        val my = e.y.toInt()

        tabsHitTest(mx, my)?.let {
            setPanelMode(it)
            return true
        }

        // ⚙ Настройки
        settingsRect?.let { (rx, ry) ->
            if (mx >= rx && mx <= rx + font.width(settingsLabel) && my >= ry && my <= ry + 12) {
                Minecraft.getInstance().setScreen(com.shigusdream.config.ConfigScreen(this))
                return true
            }
        }

        editor?.let { if (it.handleHexClick(mx, my)) return true }

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

        // Кнопки сценариев: новый/удалить сценарий
        if (panelMode == PanelMode.SCENARIOS && my in DD_SCEN_Y..(DD_SCEN_Y + DD_H)) {
            if (mx in (LEFT + W + 6)..(LEFT + W + 52)) {
                ScenarioStore.create("Сценарий ${ScenarioStore.scenarios.size + 1}")
                selectedStepIdx = -1
                return true
            }
            if (mx in (LEFT + W + 56)..(LEFT + W + 104)) {
                ScenarioStore.deleteCurrent()
                selectedStepIdx = -1
                return true
            }
            // Клик по шагу в списке
            val scenario = ScenarioStore.current
            if (scenario != null) {
                val listY = DD_SCEN_Y + DD_H
                val row = ((my - listY) / ROW).toInt() + stepsScroll
                if (my >= listY && row in scenario.steps.indices) {
                    selectedStepIdx = row
                    scenarioDelayBox?.setValue(scenario.steps[row].delay.toString())
                    return true
                }
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

        val inTarget = panelMode == PanelMode.ACTIONS && mx in LEFT..(LEFT + W) && my >= DD_TARGET_Y && my < DD_TARGET_Y + DD_H
        val inAction = panelMode == PanelMode.ACTIONS && mx in LEFT..(LEFT + W) && my >= DD_ACTION_Y && my < DD_ACTION_Y + DD_H
        val inScenario = panelMode == PanelMode.SCENARIOS && mx in LEFT..(LEFT + W) && my >= DD_SCEN_Y && my < DD_SCEN_Y + DD_H

        if (openDropdown != Dropdown.NONE) {
            val listY = when (openDropdown) {
                Dropdown.TARGET -> DD_TARGET_Y + DD_H
                Dropdown.ACTION -> DD_ACTION_Y + DD_H
                else -> DD_SCEN_Y + DD_H
            }
            val rowIdx = ((my - listY) / ROW).toInt()
            if (my >= listY && mx in LEFT..(LEFT + W)) {
                when (openDropdown) {
                    Dropdown.TARGET -> ShigusDreamRuntime.presenceUsers.getOrNull(rowIdx)?.let { selectedTarget = it.username }
                    Dropdown.ACTION -> ShigusDreamClient.registry.all().getOrNull(rowIdx)?.let {
                        selectedAction = it
                        rebuildArgumentFields()
                    }

                    Dropdown.SCENARIO -> {
                        ScenarioStore.scenarios.getOrNull(rowIdx)?.let {
                            ScenarioStore.currentIndex = rowIdx
                            selectedStepIdx = -1
                        }
                    }

                    Dropdown.NONE -> {}
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
        if (inScenario) {
            setFieldsVisible(false)
            openDropdown = Dropdown.SCENARIO
            return true
        }

        return super.mouseClicked(e, doubled)
    }

    private fun setFieldsVisible(visible: Boolean) {
        fieldWidgets.forEach { (_, widget) -> widget.visible = visible }
        soundBox?.visible = visible
        editor?.setWidgetsVisible(visible)
        if (panelMode == PanelMode.SCENARIOS) {
            scenarioDelayBox?.visible = visible && selectedStepIdx >= 0
        }
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        if (openDropdown != Dropdown.NONE) {
            openDropdown = Dropdown.NONE
            setFieldsVisible(true)
            return true
        }
        if (panelMode == PanelMode.SCENARIOS) {
            val scenario = ScenarioStore.current
            if (scenario != null && scenario.steps.size > 8) {
                stepsScroll = (stepsScroll - yDelta.toInt()).coerceIn(0, scenario.steps.size - 8)
                return true
            }
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

        // Табы
        val activeTabBg = 0xFF3A3A66.toInt()
        val tabBg = 0xFF22223A.toInt()
        g.fill(LEFT, 4, LEFT + 70, 18, if (panelMode == PanelMode.ACTIONS) activeTabBg else tabBg)
        g.text(font, Component.literal("Действия"), LEFT + 6, 7, if (panelMode == PanelMode.ACTIONS) C_WHITE else C_LABEL)
        g.fill(LEFT + 74, 4, LEFT + 144, 18, if (panelMode == PanelMode.SCENARIOS) activeTabBg else tabBg)
        g.text(font, Component.literal("Сценарии"), LEFT + 80, 7, if (panelMode == PanelMode.SCENARIOS) C_WHITE else C_LABEL)

        // Версия и настройки
        g.text(font, versionComponent, width - font.width(versionComponent) - 4, 6, C_WHITE)
        val sw = font.width(settingsLabel)
        val sx = width - sw - 4
        settingsRect = sx to 18
        val sHovered = mouseX >= sx && mouseX <= sx + sw && mouseY >= 18 && mouseY <= 30
        g.text(font, settingsLabel, sx, 18, if (sHovered) C_YELLOW else C_LABEL)

        if (panelMode == PanelMode.ACTIONS) {
            renderActionsMode(g)
        } else {
            renderScenariosMode(g)
        }

        // Выпадающие списки — самыми последними
        when (openDropdown) {
            Dropdown.TARGET -> drawTargetList(g)
            Dropdown.ACTION -> drawActionList(g)
            Dropdown.SCENARIO -> drawScenarioList(g)
            Dropdown.NONE -> {}
        }
    }

    private fun renderActionsMode(g: GuiGraphicsExtractor) {
        g.text(font, Component.literal("Target"), LEFT, 22, C_LABEL)
        g.text(font, Component.literal("Action"), LEFT, 62, C_LABEL)
        g.text(font, Component.literal("Arguments"), LEFT, 106, C_LABEL)

        drawDropdownHeader(g, DD_TARGET_Y, targetLabel(selectedTarget, ShigusDreamRuntime.presenceUsers.firstOrNull { it.username == selectedTarget }?.online ?: false), selectedTarget == "(нет данных)")
        val action = selectedAction
        drawDropdownHeader(g, DD_ACTION_Y, Component.literal(action?.displayName ?: ""), false)

        var y = 118
        for ((field, _) in fieldWidgets) {
            val label = Component.literal("${field.key} (${field.type.wireName})")
            if (field.required) {
                label.append(Component.literal(" *").withStyle { it.withColor(TextColor.fromRgb(C_RED)) })
            }
            g.text(font, label, LEFT, y, C_FIELD)
            if (field.key == "sound" && soundBox != null) {
                val rx = LEFT + W + 4
                val ry = y + 10
                soundPreviewRect = rx to ry
                val hovered = mouseX >= rx && mouseX <= rx + 14 && mouseY >= ry && mouseY <= ry + 16
                g.fill(rx, ry, rx + 14, ry + 16, if (hovered) C_BG_HOVER else C_BG_BOX)
                g.text(font, Component.literal("▶"), rx + 3, ry + 4, C_GREEN)
            }
            y += 34
        }

        val sb = soundBox
        if (sb != null && soundSuggestions.isNotEmpty()) {
            drawSuggestions(g, sb.y + 16, soundSuggestions)
        }

        editor?.render(g, mouseX, mouseY)

        val online = ShigusDreamRuntime.presenceUsers.count { it.online }
        g.text(
            font,
            Component.literal("Онлайн: $online | Роль: ${ShigusDreamClient.myRole ?: "?"}"),
            LEFT, height - 68, C_DIM,
        )
        if (statusLine.isNotBlank()) {
            g.text(font, Component.literal(stripLegacy(statusLine)), LEFT, height - 82, statusColor)
        }
    }

    private fun renderScenariosMode(g: GuiGraphicsExtractor) {
        val scenario = ScenarioStore.current

        g.text(font, Component.literal("Сценарий"), LEFT, 22, C_LABEL)
        drawDropdownHeader(
            g, DD_SCEN_Y,
            Component.literal(scenario?.name ?: "(нет сценариев)"),
            scenario == null,
        )
        // Кнопки [+ новый] [✕] справа от селектора
        val bx = LEFT + W + 6
        g.fill(bx, DD_SCEN_Y, bx + 46, DD_SCEN_Y + DD_H, C_BG_BOX)
        g.text(font, Component.literal("+ новый"), bx + 4, DD_SCEN_Y + 6, C_WHITE)
        g.fill(bx + 50, DD_SCEN_Y, bx + 96, DD_SCEN_Y + DD_H, C_BG_BOX)
        g.text(font, Component.literal("✕ удалить"), bx + 54, DD_SCEN_Y + 6, C_RED)

        // Шаги
        g.text(font, Component.literal("Шаги"), LEFT, 62, C_LABEL)
        if (scenario == null || scenario.steps.isEmpty()) {
            g.fill(LEFT, DD_SCEN_Y + DD_H, LEFT + W, DD_SCEN_Y + DD_H + ROW, C_BG_LIST)
            g.text(font, Component.literal("(нет шагов — добавьте из настроек действия)"), LEFT + 6, DD_SCEN_Y + DD_H + 3, C_DIM)
        } else {
            val visible = minOf(8, scenario.steps.size)
            val listY = DD_SCEN_Y + DD_H
            g.fill(LEFT, listY, LEFT + W, listY + visible * ROW, C_BG_LIST)
            for (i in 0 until visible) {
                val idx = i + stepsScroll
                val step = scenario.steps.getOrNull(idx) ?: break
                val rowY = listY + i * ROW
                val hovered = mouseY >= rowY && mouseY < rowY + ROW && mouseX >= LEFT && mouseX <= LEFT + W
                if (idx == selectedStepIdx) {
                    g.fill(LEFT, rowY, LEFT + W, rowY + ROW, 0xFF2A3A55.toInt())
                } else if (hovered) {
                    g.fill(LEFT, rowY, LEFT + W, rowY + ROW, C_BG_HOVER2)
                }
                val actionName = ShigusDreamClient.registry.byId(step.action)?.displayName ?: step.action
                g.text(
                    font,
                    Component.literal("${idx + 1}. ${actionName} → ${step.target} (задержка ${step.delay}т)"),
                    LEFT + 6, rowY + 3, if (idx == selectedStepIdx) 0xFFB088FF.toInt() else C_FIELD,
                )
            }
        }

        // Задержка выбранного шага
        val step = scenario?.steps?.getOrNull(selectedStepIdx)
        val delayText = if (step != null) {
            Component.literal("Задержка шага ${selectedStepIdx + 1}, тиков:")
        } else {
            Component.literal("Задержка (выберите шаг):")
        }
        g.text(font, delayText, LEFT, height - 100, C_LABEL)

        // Футер
        ScenarioRunner.progressLine?.let {
            g.text(font, Component.literal(it), LEFT, height - 84, 0xFFB088FF.toInt())
        }
        val online = ShigusDreamRuntime.presenceUsers.count { it.online }
        g.text(font, Component.literal("Онлайн: $online | Роль: ${ShigusDreamClient.myRole ?: "?"}"), LEFT, height - 68, C_DIM)
        if (statusLine.isNotBlank()) {
            g.text(font, Component.literal(stripLegacy(statusLine)), LEFT, height - 82, statusColor)
        }
    }

    private fun drawScenarioList(g: GuiGraphicsExtractor) {
        val scenarios = ScenarioStore.scenarios
        if (scenarios.isEmpty()) {
            g.fill(LEFT, DD_SCEN_Y + DD_H, LEFT + W, DD_SCEN_Y + DD_H + ROW, C_BG_LIST)
            g.text(font, Component.literal("(нет сценариев)"), LEFT + 6, DD_SCEN_Y + DD_H + 3, C_DIM)
            return
        }
        val listY = DD_SCEN_Y + DD_H
        val listH = scenarios.size * ROW
        g.fill(LEFT, listY, LEFT + W, listY + listH, C_BG_LIST)
        g.fill(LEFT, listY, LEFT + 1, listY + listH, C_ACCENT)
        var y = listY
        for ((idx, scenario) in scenarios.withIndex()) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER2)
            val color = if (idx == ScenarioStore.currentIndex) 0xFFB088FF.toInt() else C_FIELD
            g.text(font, Component.literal(scenario.name), LEFT + 6, y + 3, color)
            y += ROW
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
        for (a in actions) {
            val hovered = mouseY >= y && mouseY < y + ROW && mouseX >= LEFT && mouseX <= LEFT + W
            if (hovered) g.fill(LEFT, y, LEFT + W, y + ROW, C_BG_HOVER)
            val label = Component.literal(a.displayName).let {
                if (a == selectedAction) it.withStyle { st -> st.withColor(TextColor.fromRgb(0xB088FF)) } else it
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
