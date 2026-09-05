package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.MiniText
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType

/** Титры по центру экрана с fade-настройками (ванильные title/subtitle). */
object ShowTitleAction : ClientAction {
    override val id = "shigusdream:show_title"
    override val displayName = "Show Title"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "title", type = FieldType.STRING, required = true, maxLength = 256, description = "Большой текст (MiniMessage)"),
            SchemaField(key = "subtitle", type = FieldType.STRING, maxLength = 256, description = "Подзаголовок"),
            SchemaField(key = "fadeIn", type = FieldType.INT, min = 0.0, max = 100.0, default = 10, description = "Появление, тиков"),
            SchemaField(key = "stay", type = FieldType.INT, min = 10.0, max = 600.0, default = 70, description = "На экране, тиков"),
            SchemaField(key = "fadeOut", type = FieldType.INT, min = 0.0, max = 100.0, default = 20, description = "Исчезание, тиков"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val title = a.get("title")?.asString ?: return ActionResult.fail("missing title")
        val subtitle = a.get("subtitle")?.takeIf { it.isJsonPrimitive }?.asString
        val fadeIn = a.get("fadeIn")?.takeIf { it.isJsonPrimitive }?.asInt ?: 10
        val stay = a.get("stay")?.takeIf { it.isJsonPrimitive }?.asInt ?: 70
        val fadeOut = a.get("fadeOut")?.takeIf { it.isJsonPrimitive }?.asInt ?: 20

        val gui = Minecraft.getInstance().gui
        gui.setTimes(fadeIn.coerceAtLeast(0), stay.coerceAtLeast(1), fadeOut.coerceAtLeast(0))
        gui.setTitle(com.shigusdream.client.MiniText.parse(title))
        if (!subtitle.isNullOrBlank()) gui.setSubtitle(com.shigusdream.client.MiniText.parse(subtitle))
        return ActionResult.ok()
    }
}

/** Принудительная перспектива камеры цели (duration > 0 — вернуть исходную). */
object SetPerspectiveAction : ClientAction {
    override val id = "shigusdream:set_perspective"
    override val displayName = "Set Perspective"
    override val schema = ActionSchema(
        listOf(
            SchemaField(
                key = "perspective", type = FieldType.STRING, required = true,
                allowedValues = listOf("first_person", "third_back", "third_front"),
                description = "Перспектива",
            ),
            SchemaField(key = "duration", type = FieldType.INT, min = 0.0, max = 24000.0, default = 200, description = "Тиков до возврата (0 = не возвращать)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val perspective = a.get("perspective")?.asString ?: return ActionResult.fail("missing perspective")
        val duration = a.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
        val type = when (perspective) {
            "first_person" -> CameraType.FIRST_PERSON
            "third_back" -> CameraType.THIRD_PERSON_BACK
            "third_front" -> CameraType.THIRD_PERSON_FRONT
            else -> return ActionResult.fail("unknown perspective: $perspective")
        }
        com.shigusdream.client.PerspectiveFx.set(type, duration)
        return ActionResult.ok()
    }
}

/**
 * Интерактивный вопрос цели: на экране появляется окно с кнопками,
 * выбор отправляется владельцу через action.result.
 */
object AskAction : ClientAction {
    override val id = "shigusdream:ask"
    override val displayName = "Ask"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "question", type = FieldType.STRING, required = true, maxLength = 256, description = "Вопрос (MiniMessage)"),
            SchemaField(key = "options", type = FieldType.STRING, default = "Да|Нет", description = "Варианты через | (до 4)"),
            SchemaField(key = "duration", type = FieldType.INT, required = true, min = 60.0, max = 12000.0, description = "Сколько тиков ждать ответа"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val question = a.get("question")?.asString ?: return ActionResult.fail("missing question")
        val options = a.get("options")?.takeIf { it.isJsonPrimitive }?.asString ?: "Да|Нет"
        val duration = a.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 300
        val list = options.split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        if (list.isEmpty()) return ActionResult.fail("no options")
        Minecraft.getInstance().setScreen(
            com.shigusdream.client.AskScreen(context.requestId, question, list, duration),
        )
        return ActionResult.ok()
    }
}
