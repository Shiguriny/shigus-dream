package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.HiddenEntities
import net.minecraft.client.Minecraft

/**
 * shigusdream:hide_player — скрывает или показывает игрока для цели.
 * Скрытый игрок полностью исчезает из рендера (модель, ник, частицы).
 * Сервер продолжает видеть его — это чисто клиентский визуальный эффект.
 */
object HidePlayerAction : ClientAction {
    override val id = "shigusdream:hide_player"
    override val displayName = "Hide Player"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "entity", type = FieldType.STRING, required = true, maxLength = 16, description = "Ник игрока"),
            SchemaField(key = "visible", type = FieldType.BOOL, default = false, description = "Показать (true) или скрыть (false)"),
            SchemaField(key = "duration", type = FieldType.INT, min = 0.0, max = 24000.0, default = 0, description = "Тиков (0 = пока не вернуть)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val entityName = a.get("entity")?.asString ?: return ActionResult.fail("missing entity")
        val visible = a.get("visible")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val duration = a.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val mc = Minecraft.getInstance()
        val target = mc.level?.players()?.firstOrNull {
            it.gameProfile.name.equals(entityName, ignoreCase = true)
        } ?: return ActionResult.fail("player not found: $entityName (не в мире на вашем клиенте)")

        val uuid = target.uuid
        if (visible) HiddenEntities.show(uuid) else HiddenEntities.hide(uuid, duration)
        return ActionResult.ok()
    }
}
