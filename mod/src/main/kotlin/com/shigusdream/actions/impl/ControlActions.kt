package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.ClientControls
import net.minecraft.client.Minecraft

/**
 * shigusdream:set_slot — меняет активный слот хотбара цели (0..8).
 * Ваниль сама синхронизирует смену слота с сервером на следующем тике.
 */
object SetSlotAction : ClientAction {
    override val id = "shigusdream:set_slot"
    override val displayName = "Set Slot"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "slot", type = FieldType.INT, required = true, min = 0.0, max = 8.0, description = "Слот хотбара 0..8"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val slot = context.args.get("slot")?.takeIf { it.isJsonPrimitive }?.asInt ?: return ActionResult.fail("missing slot")
        if (slot !in 0..8) return ActionResult.fail("slot must be 0..8")
        val player = Minecraft.getInstance().player ?: return ActionResult.fail("no_player")
        player.inventory.setSelectedSlot(slot)
        return ActionResult.ok()
    }
}

/**
 * shigusdream:freeze_controls — на duration тиков блокирует управление
 * (движение, прыжок, инвентарь, чат, атака/использование).
 * Esc остаётся доступен: пауза открывается и размораживает управление.
 */
object FreezeControlsAction : ClientAction {
    override val id = "shigusdream:freeze_controls"
    override val displayName = "Freeze Controls"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "duration", type = FieldType.INT, required = true, min = 20.0, max = 1200.0, description = "Тики (20/с)"),
            SchemaField(key = "allowEsc", type = FieldType.BOOL, default = true, description = "Разрешить Esc-меню (в нём разморозка)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: return ActionResult.fail("missing duration")
        val allowEsc = context.args.get("allowEsc")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        ClientControls.freeze(duration, allowEsc)
        return ActionResult.ok()
    }
}
