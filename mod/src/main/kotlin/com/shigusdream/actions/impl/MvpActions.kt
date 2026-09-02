package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField

/**
 * shigusdream:show_message — текстовое сообщение в HUD-оверлее.
 * Тексты ставятся в очередь MessageOverlay (не чат), показываются duration тиков.
 */
object ShowMessageAction : ClientAction {
    override val id = "shigusdream:show_message"
    override val displayName = "Show Message"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "text", type = FieldType.STRING, required = true, maxLength = 256, description = "Текст сообщения"),
            SchemaField(key = "duration", type = FieldType.INT, min = 20.0, max = 1200.0, default = 100, description = "Длительность в тиках (20/с)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val text = context.args.get("text")?.asString ?: return ActionResult.fail("missing text")
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 100
        com.shigusdream.client.MessageOverlay.enqueue(text, duration)
        return ActionResult.ok()
    }
}

/**
 * shigusdream:notification — всплывающее уведомление (toast) в углу экрана.
 */
object NotificationAction : ClientAction {
    override val id = "shigusdream:notification"
    override val displayName = "Notification"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "title", type = FieldType.STRING, required = true, maxLength = 128, description = "Заголовок"),
            SchemaField(key = "description", type = FieldType.STRING, maxLength = 256, description = "Описание"),
            SchemaField(
                key = "type", type = FieldType.STRING, allowedValues = listOf("info", "success", "warning", "error"),
                default = "info", description = "Тип уведомления",
            ),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val title = context.args.get("title")?.asString ?: return ActionResult.fail("missing title")
        val description = context.args.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val type = context.args.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "info"
        com.shigusdream.client.NotificationToasts.show(title, description, type)
        return ActionResult.ok()
    }
}

/**
 * shigusdream:play_sound — воспроизводит звук, зарегистрированный в реестре SoundEvent.
 * Произвольные идентификаторы отклоняются проверкой по реестру.
 */
object PlaySoundAction : ClientAction {
    override val id = "shigusdream:play_sound"
    override val displayName = "Play Sound"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "sound", type = FieldType.IDENTIFIER, required = true, description = "Например minecraft:entity.player.levelup"),
            SchemaField(key = "volume", type = FieldType.FLOAT, min = 0.0, max = 1.0, default = 1.0f, description = "Громкость 0..1"),
            SchemaField(key = "pitch", type = FieldType.FLOAT, min = 0.5, max = 2.0, default = 1.0f, description = "Высота тона 0.5..2"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val soundId = context.args.get("sound")?.asString ?: return ActionResult.fail("missing sound")
        val volume = context.args.get("volume")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        val pitch = context.args.get("pitch")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        return com.shigusdream.client.SoundPlayback.play(soundId, volume, pitch)
    }
}
