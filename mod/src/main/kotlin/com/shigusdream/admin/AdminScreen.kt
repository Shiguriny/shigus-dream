package com.shigusdream.admin

import com.google.gson.JsonObject
import com.shigusdream.ShigusDreamClient
import com.shigusdream.ShigusDreamRuntime
import com.shigusdream.actions.ActionValidator
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import kotlin.math.max
import kotlin.math.min

/**
 * Адаптивная панель администратора. Основная карточка центрируется, редактор
 * форматирования занимает отдельную колонку, а длинное содержимое прокручивается.
 */
class AdminScreen : Screen(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Shigu's Dream — Admin Panel")) {
    private companion object {
        const val ROW = 16
        const val FIELD_STEP = 42
        const val MAX_DROPDOWN_ROWS = 6
        const val C_LABEL = 0xFFA0A0B0.toInt()
        const val C_FIELD = 0xFFC8C8D8.toInt()
        const val C_WHITE = -1
        const val C_DIM = 0xFF909090.toInt()
        const val C_ACCENT = 0xFF7C5CFF.toInt()
        const val C_BG_BOX = 0xEE22223A.toInt()
        const val C_BG_HOVER = 0xFF3A3A55.toInt()
        const val C_BG_HOVER2 = 0xFF3A3A66.toInt()
        const val C_BG_LIST = 0xF0101020.toInt()
        const val C_RED = 0xFFFF5555.toInt()
        const val NO_TARGET = "__none__"
    }

    private enum class PanelMode { ACTIONS, SCENARIOS }
    private enum class Dropdown { NONE, TARGET, ACTION, SCENARIO, EFFECT, PRESET }

    private var panelMode = PanelMode.ACTIONS
    private var openDropdown = Dropdown.NONE
    private var dropdownScroll = 0
    private var contentScroll = 0
    private var stepsScroll = 0
    private var selectedStepIdx = -1
    private var selectedTarget = ""
    private var selectedAction: ClientAction? = null
    private var selectedPreset = ""
    private val fieldValues = LinkedHashMap<String, String>()
    private val fieldWidgets = mutableListOf<Pair<SchemaField, UiTextField>>()
    private var effectBox: UiTextField? = null
    private var soundBox: UiTextField? = null
    private var soundSuggestions: List<String> = emptyList()
    private var editor: MiniMessageEditor? = null
    private var scenarioDelayBox: UiTextField? = null
    private var scenarioBeforeBox: UiTextField? = null
    private var scenarioRepeatBox: UiTextField? = null
    private var scenarioLoopsBox: UiTextField? = null
    private var waitResultToggle: UiToggle? = null
    private var stopOnErrorToggle: UiToggle? = null

    private lateinit var sendButton: UiButton
    private lateinit var closeButton: UiButton
    private lateinit var newScenarioButton: UiButton
    private lateinit var deleteScenarioButton: UiButton
    private lateinit var addStepButton: UiButton
    private lateinit var moveUpButton: UiButton
    private lateinit var moveDownButton: UiButton
    private lateinit var deleteStepButton: UiButton
    private lateinit var runButton: UiButton
    private lateinit var stopButton: UiButton
    private lateinit var previewButton: UiButton
    private lateinit var favoriteButton: UiButton
    private lateinit var savePresetButton: UiButton
    private lateinit var deletePresetButton: UiButton
    private lateinit var historyButton: UiButton
    private lateinit var groupsButton: UiButton
    private lateinit var effectsButton: UiButton

    private var statusLine = ""
    private var statusColor = C_WHITE
    private var versionComponent: MutableComponent = Component.empty()
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var settingsRect: Pair<Int, Int>? = null
    private val settingsLabel = I18n.component("shigusdream.admin.settings")

    private var left = 8
    private var panelWidth = 220
    private var editorColumnWidth = 0
    private val right get() = left + panelWidth
    private val editorLeft get() = right + 12
    private val cardRight get() = if (editorColumnWidth > 0) editorLeft + editorColumnWidth else right
    private val tabY get() = 8
    private val selectorTargetY get() = 52
    private val selectorActionY get() = 96
    private val fieldsTop get() = 182
    private val contentBottom get() = height - 120
    private val footerSendY get() = height - 54
    private val footerCloseY get() = height - 29

    override fun init() {
        // init вызывается вновь при смене разрешения; удаляем прежние children,
        // иначе Screen продолжает рисовать их поверх новой раскладки.
        clearWidgets()
        fieldWidgets.clear()
        effectBox = null
        soundBox = null
        soundSuggestions = emptyList()
        editor = null
        val users = ShigusDreamRuntime.presenceUsers
        selectedTarget = selectedTarget.takeIf { target ->
            users.any { it.username == target } || (AdminDataStore.isGroupTarget(target) && AdminDataStore.resolveTargets(target).isNotEmpty())
        } ?: users.firstOrNull()?.username ?: AdminDataStore.groups.firstOrNull()?.let { AdminDataStore.groupTarget(it.name) } ?: NO_TARGET
        val actions = ShigusDreamClient.registry.all()
        if (selectedAction == null || selectedAction !in actions) selectedAction = actions.firstOrNull()
        updatePanelBounds()
        versionComponent = Component.literal("[Shigu's Dream v${ShigusDreamClient.modVersion()}]")
            .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }

        selectedAction?.let(::buildArgumentFields)
        buildFixedWidgets()
        layoutWidgets()
        syncScenarioWidgets()
        updateWidgetVisibility()
    }

    private fun updatePanelBounds() {
        val margin = 12
        val available = max(180, width - margin * 2)
        val wantsEditor = panelMode == PanelMode.ACTIONS && selectedAction?.id == "shigusdream:show_message"
        val totalWidth = min(if (wantsEditor && available >= 620) 680 else 460, available)
        editorColumnWidth = if (wantsEditor && totalWidth >= 620) 220 else 0
        panelWidth = totalWidth - if (editorColumnWidth > 0) editorColumnWidth + 12 else 0
        left = (width - totalWidth) / 2
    }

    private fun buildFixedWidgets() {
        scenarioDelayBox = UiTextField(font, left, 0, 72, 18, Component.literal("delay")).apply {
            setMaxLength(5)
            setResponder { value ->
                ScenarioStore.current?.steps?.getOrNull(selectedStepIdx)?.let { step ->
                    step.delay = value.toIntOrNull()?.coerceIn(0, 1200) ?: step.delay
                    ScenarioStore.save()
                }
            }
            addRenderableWidget(this)
        }
        scenarioBeforeBox = stepNumberBox("delayBefore", 0, 1200) { step, value -> step.delayBefore = value }
        scenarioRepeatBox = stepNumberBox("repeat", 1, 100) { step, value -> step.repeat = value }
        scenarioLoopsBox = UiTextField(font, left, 0, 40, 10, Component.literal("loops")).apply {
            setMaxLength(3)
            setResponder { value -> ScenarioStore.current?.let { it.loops = value.toIntOrNull()?.coerceIn(1, 100) ?: it.loops; ScenarioStore.save() } }
            addRenderableWidget(this)
        }
        waitResultToggle = addRenderableWidget(UiToggle(left, 0, 0, 18, I18n.component("shigusdream.scenario.wait_result"), true) { value ->
            ScenarioStore.current?.steps?.getOrNull(selectedStepIdx)?.let { it.waitForResult = value; ScenarioStore.save() }
        })
        stopOnErrorToggle = addRenderableWidget(UiToggle(left, 0, 0, 18, I18n.component("shigusdream.scenario.stop_error"), true) { value ->
            ScenarioStore.current?.steps?.getOrNull(selectedStepIdx)?.let { it.stopOnError = value; ScenarioStore.save() }
        })
        sendButton = addRenderableWidget(UiButton(left, footerSendY, panelWidth, 20, I18n.component("shigusdream.admin.send"), ::send, true))
        closeButton = addRenderableWidget(UiButton(left, footerCloseY, panelWidth, 20, I18n.component("shigusdream.common.close"), ::onClose))
        newScenarioButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.scenario.new"), {
            ScenarioStore.create(I18n.text("shigusdream.scenario.generated", ScenarioStore.scenarios.size + 1))
            selectedStepIdx = -1
            layoutWidgets()
        }))
        deleteScenarioButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.common.delete"), {
            ScenarioStore.deleteCurrent()
            selectedStepIdx = -1
            layoutWidgets()
        }))
        addStepButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.scenario.add_step"), ::addStepFromCurrent))
        moveUpButton = addRenderableWidget(UiButton(left, 0, 0, 18, "▲", { moveStep(-1) }))
        moveDownButton = addRenderableWidget(UiButton(left, 0, 0, 18, "▼", { moveStep(1) }))
        deleteStepButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.scenario.delete_step"), ::deleteStep))
        runButton = addRenderableWidget(UiButton(left, 0, 0, 20, I18n.component("shigusdream.scenario.run"), ::runScenario, true))
        stopButton = addRenderableWidget(UiButton(left, 0, 0, 20, I18n.component("shigusdream.scenario.stop"), ScenarioRunner::stop))

        previewButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.admin.preview"), ::preview, true))
        favoriteButton = addRenderableWidget(UiButton(left, 0, 0, 18, favoriteLabel(), ::toggleFavorite))
        savePresetButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.preset.save"), ::savePreset))
        deletePresetButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.preset.delete"), ::deletePreset))
        historyButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.history.short"), {
            Minecraft.getInstance().setScreen(HistoryScreen(this))
        }))
        groupsButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.groups.short"), {
            Minecraft.getInstance().setScreen(GroupsScreen(this))
        }))
        effectsButton = addRenderableWidget(UiButton(left, 0, 0, 18, I18n.component("shigusdream.effects.short"), {
            Minecraft.getInstance().setScreen(ActiveEffectsScreen(this))
        }))
    }

    private fun stepNumberBox(name: String, min: Int, max: Int, setter: (ScenarioStep, Int) -> Unit): UiTextField =
        UiTextField(font, left, 0, 40, 10, Component.literal(name)).apply {
            setMaxLength(4)
            setResponder { value ->
                ScenarioStore.current?.steps?.getOrNull(selectedStepIdx)?.let { step ->
                    setter(step, value.toIntOrNull()?.coerceIn(min, max) ?: return@setResponder)
                    ScenarioStore.save()
                }
            }
            addRenderableWidget(this)
        }

    private fun buildArgumentFields(action: ClientAction) {
        for (field in action.schema.fields) {
            val widget = UiTextField(font, left, 0, panelWidth, 18, Component.literal(field.key)).apply {
                setMaxLength(field.maxLength ?: 256)
                if (field.description.isNotBlank()) setHint(Component.literal(field.description))
                setValue(fieldValues[field.key] ?: defaultFor(field))
                if (field.key == "sound") {
                    setResponder { updateSoundSuggestions(it) }
                    soundBox = this
                }
                if (field.key == "effect" && field.allowedValues != null) {
                    setEditable(false)
                    effectBox = this
                }
                addRenderableWidget(this)
            }
            fieldWidgets += field to widget
        }
        // Редактор форматирования не выходит за границы на компактном экране.
        if (action.id == "shigusdream:show_message" && editorColumnWidth > 0) {
            fieldWidgets.firstOrNull { it.first.key == "text" }?.second?.let { text ->
                editor = MiniMessageEditor(text, editorLeft, fieldsTop, editorColumnWidth,
                    { addRenderableWidget(it) }, { removeWidget(it) }).also { it.initWidgets() }
            }
        }
    }

    private fun rememberFieldValues() {
        fieldWidgets.forEach { (field, widget) -> fieldValues[field.key] = widget.getValue() }
    }

    private fun rebuildArgumentFields() {
        rememberFieldValues()
        fieldWidgets.forEach { (_, widget) -> removeWidget(widget) }
        editor?.removeWidgets()
        fieldWidgets.clear()
        effectBox = null
        soundBox = null
        soundSuggestions = emptyList()
        editor = null
        updatePanelBounds()
        selectedAction?.let(::buildArgumentFields)
        contentScroll = 0
        layoutWidgets()
        updateWidgetVisibility()
    }

    private fun layoutWidgets() {
        // В MC 26.x порядок аргументов LayoutElement.setRectangle:
        // width, height, x, y (не x, y, width, height).
        sendButton.setRectangle(panelWidth, 20, left, footerSendY)
        closeButton.setRectangle(panelWidth, 20, left, footerCloseY)
        val toolGap = 4
        val toolWidth = (panelWidth - toolGap * 3) / 4
        favoriteButton.setRectangle(toolWidth, 18, left, 120)
        previewButton.setRectangle(toolWidth, 18, left + toolWidth + toolGap, 120)
        savePresetButton.setRectangle(toolWidth, 18, left + (toolWidth + toolGap) * 2, 120)
        deletePresetButton.setRectangle(panelWidth - (toolWidth + toolGap) * 3, 18, left + (toolWidth + toolGap) * 3, 120)
        val utilityWidth = (panelWidth - 12) / 3
        historyButton.setRectangle(utilityWidth, 18, left, height - 102)
        groupsButton.setRectangle(utilityWidth, 18, left + utilityWidth + 6, height - 102)
        effectsButton.setRectangle(panelWidth - utilityWidth * 2 - 12, 18, left + utilityWidth * 2 + 12, height - 102)
        fieldWidgets.forEachIndexed { index, (_, field) ->
            field.setRectangle(
                panelWidth - UiTextField.HORIZONTAL_PADDING * 2,
                10,
                left + UiTextField.HORIZONTAL_PADDING,
                fieldsTop + index * FIELD_STEP - contentScroll + 14 + UiTextField.VERTICAL_PADDING,
            )
        }

        val manageY = 76
        val manageSpace = (panelWidth - 72).coerceAtLeast(80)
        val half = (manageSpace - 6) / 2
        newScenarioButton.setRectangle(half, 18, left, manageY)
        deleteScenarioButton.setRectangle(manageSpace - half - 6, 18, left + half + 6, manageY)
        val optionY = height - 137
        val optionWidth = (panelWidth - 12) / 3
        scenarioBeforeBox?.setRectangle(optionWidth - 10, 10, left + 5, optionY + 5)
        scenarioDelayBox?.setRectangle(optionWidth - 10, 10, left + optionWidth + 11, optionY + 5)
        scenarioRepeatBox?.setRectangle(panelWidth - optionWidth * 2 - 22, 10, left + optionWidth * 2 + 17, optionY + 5)
        waitResultToggle?.setRectangle((panelWidth - 6) / 2, 18, left, height - 112)
        stopOnErrorToggle?.setRectangle((panelWidth - 6) / 2, 18, left + (panelWidth + 6) / 2, height - 112)
        val actionY = height - 89
        val small = max(28, (panelWidth - 12) / 5)
        addStepButton.setRectangle(panelWidth - small * 3 - 12, 18, left, actionY)
        moveUpButton.setRectangle(small, 18, right - small * 3 - 8, actionY)
        moveDownButton.setRectangle(small, 18, right - small * 2 - 4, actionY)
        deleteStepButton.setRectangle(small, 18, right - small, actionY)
        runButton.setRectangle((panelWidth - 6) / 2, 20, left, height - 65)
        stopButton.setRectangle((panelWidth - 6) / 2, 20, left + (panelWidth + 6) / 2, height - 65)
        scenarioLoopsBox?.setRectangle(34, 10, right - 39, 81)
    }

    private fun updateWidgetVisibility() {
        val actions = panelMode == PanelMode.ACTIONS
        val scenarios = panelMode == PanelMode.SCENARIOS
        sendButton.visible = actions
        closeButton.visible = true
        previewButton.visible = actions
        favoriteButton.visible = actions
        savePresetButton.visible = actions
        deletePresetButton.visible = actions
        historyButton.visible = actions
        groupsButton.visible = actions
        effectsButton.visible = actions
        fieldWidgets.forEach { (_, field) ->
            field.visible = actions && field.outerY >= fieldsTop && field.outerBottom <= contentBottom
        }
        editor?.setWidgetsVisible(actions && contentScroll == 0 && fieldsTop + 90 < contentBottom)
        newScenarioButton.visible = scenarios
        deleteScenarioButton.visible = scenarios
        addStepButton.visible = scenarios
        moveUpButton.visible = scenarios
        moveDownButton.visible = scenarios
        deleteStepButton.visible = scenarios
        runButton.visible = scenarios
        stopButton.visible = scenarios
        scenarioDelayBox?.visible = scenarios && selectedStepIdx >= 0
        scenarioBeforeBox?.visible = scenarios && selectedStepIdx >= 0
        scenarioRepeatBox?.visible = scenarios && selectedStepIdx >= 0
        scenarioLoopsBox?.visible = scenarios && ScenarioStore.current != null
        waitResultToggle?.visible = scenarios && selectedStepIdx >= 0
        stopOnErrorToggle?.visible = scenarios && selectedStepIdx >= 0
    }

    private fun setPanelMode(mode: PanelMode) {
        if (panelMode == mode) return
        panelMode = mode
        openDropdown = Dropdown.NONE
        contentScroll = 0
        selectedStepIdx = -1
        stepsScroll = 0
        updatePanelBounds()
        layoutWidgets()
        updateWidgetVisibility()
    }

    private fun defaultFor(field: SchemaField): String = when {
        field.allowedValues != null -> field.allowedValues.first()
        field.default != null -> field.default.toString()
        else -> ""
    }

    private fun updateSoundSuggestions(value: String) {
        val all = BuiltInRegistries.SOUND_EVENT.keySet().map { it.toString() }
        soundSuggestions = (if (value.isBlank()) all else all.filter { it.contains(value, ignoreCase = true) }).sorted().take(MAX_DROPDOWN_ROWS)
    }

    private fun buildArgsOrNull(): JsonObject? {
        rememberFieldValues()
        val args = JsonObject()
        for (field in selectedAction?.schema?.fields ?: emptyList()) {
            val raw = fieldValues[field.key]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            when (field.type) {
                FieldType.INT -> raw.toIntOrNull()?.let { args.addProperty(field.key, it) } ?: return failInput("'${field.key}' должен быть целым числом")
                FieldType.FLOAT -> raw.toFloatOrNull()?.let { args.addProperty(field.key, it) } ?: return failInput("'${field.key}' должен быть числом")
                FieldType.BOOL -> if (raw == "true" || raw == "false") args.addProperty(field.key, raw.toBoolean()) else return failInput("'${field.key}' должен быть true/false")
                else -> args.addProperty(field.key, raw)
            }
        }
        val action = selectedAction ?: return null
        val errors = ActionValidator.validate(action.schema, args)
        if (errors.isNotEmpty()) return failInput(errors.joinToString("; "))
        return args
    }

    private fun failInput(message: String): JsonObject? {
        statusLine = message
        statusColor = C_RED
        return null
    }

    private fun send() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return
        if (selectedTarget == NO_TARGET) { failInput(I18n.text("shigusdream.admin.no_presence")); return }
        val targets = AdminDataStore.resolveTargets(selectedTarget)
        if (targets.isEmpty()) { failInput(I18n.text("shigusdream.admin.empty_group")); return }
        statusLine = I18n.text("shigusdream.admin.sending", targets.size)
        statusColor = C_WHITE
        val ids = ShigusDreamClient.sendAction(selectedTarget, action.id, args)
        if (ids.isEmpty()) failInput(I18n.text("shigusdream.admin.not_connected"))
    }

    private fun preview() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return
        val result = ShigusDreamClient.previewAction(action.id, args)
        statusLine = I18n.text(if (result.executed) "shigusdream.admin.preview_ok" else "shigusdream.admin.preview_failed", result.error ?: "")
        statusColor = if (result.executed) 0xFF55FF55.toInt() else C_RED
    }

    private fun toggleFavorite() {
        selectedAction?.let { AdminDataStore.toggleFavorite(it.id) }
        favoriteButton.setMessage(favoriteLabel())
    }

    private fun favoriteLabel(): Component = I18n.component(
        if (selectedAction?.id in AdminDataStore.favorites) "shigusdream.favorite.remove" else "shigusdream.favorite.add",
    )

    private fun savePreset() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return
        val preset = AdminDataStore.createPreset(action.id, args)
        selectedPreset = preset.name
        statusLine = I18n.text("shigusdream.preset.saved", preset.name)
        statusColor = 0xFF55FF55.toInt()
    }

    private fun deletePreset() {
        if (selectedPreset.isBlank()) return
        AdminDataStore.deletePreset(selectedPreset)
        statusLine = I18n.text("shigusdream.preset.deleted", selectedPreset)
        selectedPreset = ""
    }

    private fun loadPreset(name: String) {
        val preset = AdminDataStore.presets.firstOrNull { it.name == name } ?: return
        selectedPreset = name
        ShigusDreamClient.registry.byId(preset.action)?.let { selectedAction = it }
        rebuildArgumentFields()
        fieldWidgets.forEach { (field, widget) ->
            preset.args.get(field.key)?.takeIf { it.isJsonPrimitive }?.let { widget.setValue(it.asString) }
        }
        rememberFieldValues()
        favoriteButton.setMessage(favoriteLabel())
    }

    private fun addStepFromCurrent() {
        val args = buildArgsOrNull() ?: return
        val action = selectedAction ?: return
        if (selectedTarget == NO_TARGET) return
        val scenario = ScenarioStore.current ?: ScenarioStore.create(I18n.text("shigusdream.scenario.generated", 1))
        scenario.steps += ScenarioStep(selectedTarget, action.id, args, delay = 20)
        selectedStepIdx = scenario.steps.lastIndex
        ScenarioStore.save()
        layoutWidgets()
        updateWidgetVisibility()
    }

    private fun moveStep(direction: Int) {
        val scenario = ScenarioStore.current ?: return
        val target = selectedStepIdx + direction
        if (selectedStepIdx !in scenario.steps.indices || target !in scenario.steps.indices) return
        scenario.steps.add(target, scenario.steps.removeAt(selectedStepIdx))
        selectedStepIdx = target
        ScenarioStore.save()
    }

    private fun deleteStep() {
        val scenario = ScenarioStore.current ?: return
        if (selectedStepIdx !in scenario.steps.indices) return
        scenario.steps.removeAt(selectedStepIdx)
        selectedStepIdx = -1
        ScenarioStore.save()
        updateWidgetVisibility()
    }

    private fun runScenario() { ScenarioStore.current?.let(ScenarioRunner::start) }

    private fun syncScenarioWidgets() {
        val scenario = ScenarioStore.current
        scenarioLoopsBox?.setValue((scenario?.loops ?: 1).toString())
        val step = scenario?.steps?.getOrNull(selectedStepIdx)
        scenarioBeforeBox?.setValue((step?.delayBefore ?: 0).toString())
        scenarioDelayBox?.setValue((step?.delay ?: 20).toString())
        scenarioRepeatBox?.setValue((step?.repeat ?: 1).toString())
        waitResultToggle?.checked = step?.waitForResult ?: true
        stopOnErrorToggle?.checked = step?.stopOnError ?: true
    }

    override fun mouseClicked(e: MouseButtonEvent, doubled: Boolean): Boolean {
        mouseX = e.x
        mouseY = e.y
        val mx = e.x.toInt()
        val my = e.y.toInt()
        if (my in tabY..(tabY + 20)) {
            if (mx in left..(left + 88)) { setPanelMode(PanelMode.ACTIONS); return true }
            if (mx in (left + 94)..(left + 188)) { setPanelMode(PanelMode.SCENARIOS); return true }
        }
        settingsRect?.let { (x, y) ->
            if (mx in x..(x + font.width(settingsLabel)) && my in y..(y + 12)) {
                Minecraft.getInstance().setScreen(com.shigusdream.config.ConfigScreen(this)); return true
            }
        }
        editor?.let { if (it.handleHexClick(mx, my)) return true }

        if (openDropdown != Dropdown.NONE) {
            val entries = dropdownEntries()
            val listY = dropdownListY(entries.size)
            val rows = min(MAX_DROPDOWN_ROWS, entries.size)
            if (mx in left..right && my in listY until (listY + rows * ROW)) entries.getOrNull(dropdownScroll + (my - listY) / ROW)?.let(::selectDropdownEntry)
            openDropdown = Dropdown.NONE
            return true
        }
        if (panelMode == PanelMode.ACTIONS) {
            if (mx in left..right && my in selectorTargetY until (selectorTargetY + 20)) return open(Dropdown.TARGET)
            if (mx in left..right && my in selectorActionY until (selectorActionY + 20)) return open(Dropdown.ACTION)
            if (mx in left..right && my in 144 until 164) return open(Dropdown.PRESET)
            effectBox?.takeIf { it.visible }?.let { box -> if (mx in box.outerX..box.outerRight && my in box.outerY..box.outerBottom) return open(Dropdown.EFFECT) }
            val sound = soundBox
            if (sound != null && sound.visible && soundSuggestions.isNotEmpty() && mx in left..right) {
                val listY = sound.outerBottom
                if (my in listY until (listY + soundSuggestions.size * ROW)) {
                    sound.setValue(soundSuggestions[(my - listY) / ROW]); soundSuggestions = emptyList(); return true
                }
            }
        } else {
            if (mx in left..right && my in selectorTargetY until (selectorTargetY + 20)) return open(Dropdown.SCENARIO)
            val scenario = ScenarioStore.current
            val listY = 120
            if (scenario != null && mx in left..right && my in listY until (listY + scenarioListHeight())) {
                val index = stepsScroll + (my - listY) / ROW
                if (index in scenario.steps.indices) {
                    selectedStepIdx = index
                    syncScenarioWidgets()
                    updateWidgetVisibility()
                    return true
                }
            }
        }
        return super.mouseClicked(e, doubled)
    }

    private fun open(dropdown: Dropdown): Boolean {
        openDropdown = dropdown
        dropdownScroll = 0
        soundSuggestions = emptyList()
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        if (openDropdown != Dropdown.NONE) {
            dropdownScroll = (dropdownScroll - yDelta.toInt()).coerceIn(0, max(0, dropdownEntries().size - MAX_DROPDOWN_ROWS))
            return true
        }
        if (panelMode == PanelMode.ACTIONS && y in fieldsTop.toDouble()..contentBottom.toDouble()) {
            contentScroll = (contentScroll - yDelta.toInt() * 20).coerceIn(0, max(0, fieldWidgets.size * FIELD_STEP - (contentBottom - fieldsTop)))
            layoutWidgets()
            updateWidgetVisibility()
            return true
        }
        if (panelMode == PanelMode.SCENARIOS) {
            val count = ScenarioStore.current?.steps?.size ?: 0
            stepsScroll = (stepsScroll - yDelta.toInt()).coerceIn(0, max(0, count - max(1, scenarioListHeight() / ROW)))
            return true
        }
        return super.mouseScrolled(x, y, xDelta, yDelta)
    }

    private fun dropdownEntries(): List<String> = when (openDropdown) {
        Dropdown.TARGET -> AdminDataStore.groups.map { AdminDataStore.groupTarget(it.name) } + ShigusDreamRuntime.presenceUsers.map { it.username }
        Dropdown.ACTION -> ShigusDreamClient.registry.all().map { it.id }.sortedWith(compareBy<String> { it !in AdminDataStore.favorites }.thenBy { it })
        Dropdown.SCENARIO -> ScenarioStore.scenarios.map { it.name }
        Dropdown.EFFECT -> selectedAction?.schema?.fields?.firstOrNull { it.key == "effect" }?.allowedValues ?: emptyList()
        Dropdown.PRESET -> AdminDataStore.presets.map { it.name }
        Dropdown.NONE -> emptyList()
    }

    private fun selectDropdownEntry(entry: String) {
        when (openDropdown) {
            Dropdown.TARGET -> selectedTarget = entry
            Dropdown.ACTION -> ShigusDreamClient.registry.byId(entry)?.let {
                selectedAction = it
                selectedPreset = ""
                rebuildArgumentFields()
                favoriteButton.setMessage(favoriteLabel())
            }
            Dropdown.SCENARIO -> ScenarioStore.scenarios.indexOfFirst { it.name == entry }.takeIf { it >= 0 }?.let {
                ScenarioStore.currentIndex = it
                selectedStepIdx = -1
                layoutWidgets()
                syncScenarioWidgets()
                updateWidgetVisibility()
            }
            Dropdown.EFFECT -> effectBox?.setValue(entry)
            Dropdown.PRESET -> loadPreset(entry)
            Dropdown.NONE -> Unit
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        this.mouseX = mouseX.toDouble()
        this.mouseY = mouseY.toDouble()

        // Сначала поверхность и подписи, затем настоящие виджеты. Обратный порядок
        // перекрывал EditBox фоном и создавал впечатление, что поля исчезли.
        g.fill(left - 8, 4, cardRight + 8, height - 2, 0xDE10101A.toInt())
        g.outline(left - 8, 4, cardRight + 8, height - 2, 0xFF34344C.toInt())
        if (editorColumnWidth > 0) {
            g.fill(editorLeft - 6, fieldsTop - 10, cardRight + 6, min(contentBottom, fieldsTop + 160), 0xAA1B1B2C.toInt())
            g.fill(editorLeft - 6, fieldsTop - 10, editorLeft - 4, min(contentBottom, fieldsTop + 160), C_ACCENT)
        }
        drawTabs(g)
        val settingX = cardRight - font.width(settingsLabel)
        settingsRect = settingX to 20
        g.text(font, versionComponent, cardRight - font.width(versionComponent), 7, C_WHITE)
        g.text(font, settingsLabel, settingX, 20, if (mouseX in settingX..(settingX + font.width(settingsLabel)) && mouseY in 20..32) C_ACCENT else C_LABEL)
        if (panelMode == PanelMode.ACTIONS) renderActions(g) else renderScenarios(g)
        super.extractRenderState(g, mouseX, mouseY, delta)

        // Списки всегда находятся над полями и кнопками.
        if (openDropdown != Dropdown.NONE || soundSuggestions.isNotEmpty()) {
            g.nextStratum()
            soundBox?.takeIf { it.visible && soundSuggestions.isNotEmpty() }?.let { drawSoundSuggestions(g, it) }
            if (openDropdown != Dropdown.NONE) drawDropdown(g)
        }
    }

    private fun drawTabs(g: GuiGraphicsExtractor) {
        fun tab(x: Int, width: Int, title: String, selected: Boolean) {
            g.fill(x, tabY, x + width, tabY + 20, if (selected) C_BG_HOVER2 else C_BG_BOX)
            g.fill(x, tabY, x + 2, tabY + 20, if (selected) C_ACCENT else 0xFF4A4A6A.toInt())
            g.centeredText(font, Component.literal(title), x + width / 2, tabY + 6, if (selected) C_WHITE else C_LABEL)
        }
        tab(left, 88, I18n.text("shigusdream.admin.actions"), panelMode == PanelMode.ACTIONS)
        tab(left + 94, 94, I18n.text("shigusdream.admin.scenarios"), panelMode == PanelMode.SCENARIOS)
    }

    private fun renderActions(g: GuiGraphicsExtractor) {
        g.text(font, I18n.component("shigusdream.admin.target"), left, 40, C_LABEL)
        drawDropdownHeader(g, selectorTargetY, targetLabel(selectedTarget), selectedTarget == NO_TARGET)
        g.text(font, I18n.component("shigusdream.admin.action"), left, 84, C_LABEL)
        drawDropdownHeader(g, selectorActionY, actionLabel(selectedAction), false)
        drawDropdownHeader(g, 144,
            if (selectedPreset.isBlank()) I18n.component("shigusdream.preset.choose") else Component.literal(selectedPreset),
            selectedPreset.isBlank())
        g.text(font, I18n.component("shigusdream.admin.arguments"), left, 170, C_LABEL)
        g.enableScissor(left, fieldsTop, right, contentBottom)
        fieldWidgets.forEachIndexed { index, (field, _) ->
            val y = fieldsTop + index * FIELD_STEP - contentScroll
            val label = Component.literal("${field.key} (${field.type.wireName})")
            if (field.required) label.append(Component.literal(" *").withStyle { it.withColor(TextColor.fromRgb(C_RED)) })
            g.text(font, label, left, y, C_FIELD)
        }
        g.disableScissor()
        if (contentScroll == 0) editor?.render(g, mouseX, mouseY)
        drawFooter(g)
    }

    private fun renderScenarios(g: GuiGraphicsExtractor) {
        val scenario = ScenarioStore.current
        g.text(font, I18n.component("shigusdream.admin.scenario"), left, 40, C_LABEL)
        drawDropdownHeader(g, selectorTargetY, scenario?.let { Component.literal(it.name) } ?: I18n.component("shigusdream.scenario.none"), scenario == null)
        g.text(font, I18n.component("shigusdream.scenario.loops_short"), right - 70, 82, C_LABEL)
        val progress = ScenarioRunner.progressLine
        g.text(font, progress?.let(Component::literal) ?: I18n.component("shigusdream.scenario.steps"), left, 104, C_LABEL)
        val listY = 120
        val listH = scenarioListHeight()
        g.fill(left, listY, right, listY + listH, C_BG_LIST)
        if (listH < ROW) {
            g.text(font, I18n.component("shigusdream.common.more_space"), left + 6, listY + 5, C_DIM)
        } else if (scenario == null || scenario.steps.isEmpty()) {
            g.text(font, I18n.component("shigusdream.scenario.empty"), left + 6, listY + 5, C_DIM)
        } else {
            for (row in 0 until (listH / ROW)) {
                val index = stepsScroll + row
                val step = scenario.steps.getOrNull(index) ?: break
                val y = listY + row * ROW
                if (index == selectedStepIdx) g.fill(left, y, right, y + ROW, 0xFF2A3A55.toInt())
                val action = actionLabel(ShigusDreamClient.registry.byId(step.action)).string
                g.text(font, I18n.component("shigusdream.scenario.step_row", index + 1, action, step.target, step.delay, step.repeat), left + 6, y + 4, if (index == selectedStepIdx) 0xFFB088FF.toInt() else C_FIELD)
            }
        }
        val optionWidth = (panelWidth - 12) / 3
        g.text(font, I18n.component("shigusdream.scenario.before"), left, height - 151, C_LABEL)
        g.text(font, I18n.component("shigusdream.scenario.after"), left + optionWidth + 6, height - 151, C_LABEL)
        g.text(font, I18n.component("shigusdream.scenario.repeat"), left + optionWidth * 2 + 12, height - 151, C_LABEL)
    }

    /** Резервируем отдельные строки под задержку, управление и закрытие. */
    private fun scenarioListHeight(): Int = max(0, height - 280)

    private fun drawFooter(g: GuiGraphicsExtractor) {
        if (height >= 280 && panelMode == PanelMode.ACTIONS) {
            val online = ShigusDreamRuntime.presenceUsers.count { it.online }
            g.text(font, I18n.component("shigusdream.admin.online_role", online, ShigusDreamClient.myRole ?: "?"), left, height - 76, C_DIM)
            if (statusLine.isNotBlank()) g.text(font, Component.literal(stripLegacy(statusLine)), left, height - 114, statusColor)
        }
    }

    private fun drawDropdownHeader(g: GuiGraphicsExtractor, y: Int, label: Component, dim: Boolean) {
        val hovered = mouseX in left.toDouble()..right.toDouble() && mouseY in y.toDouble()..(y + 20).toDouble()
        g.fill(left, y, right, y + 20, if (hovered) C_BG_HOVER else C_BG_BOX)
        g.fill(left, y, left + 2, y + 20, C_ACCENT)
        g.text(font, label, left + 7, y + 6, if (dim) C_DIM else C_WHITE)
        g.text(font, Component.literal("▼"), right - 15, y + 6, C_LABEL)
    }

    private fun drawDropdown(g: GuiGraphicsExtractor) {
        val entries = dropdownEntries()
        val listY = dropdownListY(entries.size)
        val rows = min(MAX_DROPDOWN_ROWS, entries.size)
        if (entries.isEmpty()) {
            g.fill(left, listY, right, listY + ROW, C_BG_LIST)
            g.text(font, I18n.component("shigusdream.common.no_data"), left + 6, listY + 4, C_DIM)
            return
        }
        g.fill(left, listY, right, listY + rows * ROW, C_BG_LIST)
        for (row in 0 until rows) {
            val value = entries[dropdownScroll + row]
            val y = listY + row * ROW
            if (mouseX in left.toDouble()..right.toDouble() && mouseY in y.toDouble()..(y + ROW).toDouble()) g.fill(left, y, right, y + ROW, C_BG_HOVER)
            val display = when (openDropdown) {
                Dropdown.TARGET -> targetLabel(value)
                Dropdown.ACTION -> actionLabel(ShigusDreamClient.registry.byId(value)).let { label ->
                    if (value in AdminDataStore.favorites) Component.literal("★ ").append(label) else label
                }
                else -> Component.literal(value)
            }
            g.text(font, display, left + 6, y + 4, C_FIELD)
        }
        if (entries.size > rows) g.text(font, I18n.component("shigusdream.common.scroll_range", dropdownScroll + 1, dropdownScroll + rows, entries.size), right - 120, listY + rows * ROW + 3, C_DIM)
    }

    private fun dropdownListY(size: Int): Int {
        val anchor = when (openDropdown) {
            Dropdown.TARGET, Dropdown.SCENARIO -> selectorTargetY + 20
            Dropdown.ACTION -> selectorActionY + 20
            Dropdown.EFFECT -> effectBox?.outerBottom ?: fieldsTop
            Dropdown.PRESET -> 164
            Dropdown.NONE -> fieldsTop
        }
        return min(anchor, height - min(MAX_DROPDOWN_ROWS, size.coerceAtLeast(1)) * ROW - 4)
    }

    private fun drawSoundSuggestions(g: GuiGraphicsExtractor, sound: UiTextField) {
        val y = min(sound.outerBottom, contentBottom - soundSuggestions.size * ROW)
        g.fill(left, y, right, y + soundSuggestions.size * ROW, C_BG_LIST)
        soundSuggestions.forEachIndexed { index, value -> g.text(font, Component.literal(value), left + 6, y + index * ROW + 4, C_FIELD) }
    }

    private fun targetLabel(username: String): MutableComponent {
        if (username == NO_TARGET) return I18n.component("shigusdream.common.no_data")
        if (AdminDataStore.isGroupTarget(username)) {
            val count = AdminDataStore.resolveTargets(username).size
            return Component.literal("◆ ${username.removePrefix(AdminDataStore.GROUP_PREFIX)} ($count)")
                .withStyle { it.withColor(TextColor.fromRgb(0xB088FF)) }
        }
        val online = ShigusDreamRuntime.presenceUsers.firstOrNull { it.username == username }?.online ?: false
        return Component.literal("● ").withStyle { it.withColor(TextColor.fromRgb(if (online) 0x55FF55 else 0x707070)) }
            .append(Component.literal(username).withStyle { it.withColor(TextColor.fromRgb(C_WHITE)) })
    }

    private fun actionLabel(action: ClientAction?): MutableComponent = action?.let {
        I18n.component("shigusdream.action.${it.id.substringAfter(':')}.name")
    } ?: Component.empty()

    private fun stripLegacy(text: String): String = text.replace(Regex("§."), "")
}
