package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.ClientEffects
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects

/**
 * shigusdream:apply_effect — локальный визуальный эффект на клиенте цели
 * (темнота / слепота / тошнота). Эффект чисто клиентский: на геймплей сервера не влияет.
 */
object ApplyEffectAction : ClientAction {
    override val id = "shigusdream:apply_effect"
    override val displayName = "Apply Effect"
    override val schema = ActionSchema(
        listOf(
            SchemaField(
                key = "effect", type = FieldType.STRING, required = true,
                allowedValues = listOf("darkness", "blindness", "nausea"),
                description = "Визуальный эффект",
            ),
            SchemaField(key = "duration", type = FieldType.INT, min = 20.0, max = 1200.0, default = 100, description = "Тики (20/с)"),
            SchemaField(key = "amplifier", type = FieldType.INT, min = 0.0, max = 4.0, default = 0, description = "Уровень 0..4"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val effect = context.args.get("effect")?.asString ?: return ActionResult.fail("missing effect")
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 100
        val amplifier = context.args.get("amplifier")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        val holder = when (effect) {
            "darkness" -> MobEffects.DARKNESS
            "blindness" -> MobEffects.BLINDNESS
            "nausea" -> MobEffects.NAUSEA
            else -> return ActionResult.fail("unknown effect: $effect")
        }
        val player = Minecraft.getInstance().player ?: return ActionResult.fail("no_player")
        player.addEffect(MobEffectInstance(holder, duration, amplifier))
        return ActionResult.ok()
    }
}

/**
 * shigusdream:set_fov — меняет FOV клиента и возвращает исходное значение через duration тиков.
 */
object SetFovAction : ClientAction {
    override val id = "shigusdream:set_fov"
    override val displayName = "Set FOV"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "fov", type = FieldType.INT, required = true, min = 30.0, max = 110.0, description = "Новое значение FOV"),
            SchemaField(key = "duration", type = FieldType.INT, min = 20.0, max = 1200.0, default = 100, description = "Тиков до возврата"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val fov = context.args.get("fov")?.takeIf { it.isJsonPrimitive }?.asInt ?: return ActionResult.fail("missing fov")
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 100
        if (fov !in 30..110) return ActionResult.fail("fov must be 30..110")
        ClientEffects.setFovFor(fov, duration)
        return ActionResult.ok()
    }
}

/**
 * shigusdream:send_chat — отправка сообщения в чат от лица игрока.
 * Команды запрещены: сообщение, начинающееся с '/', отклоняется.
 */
object SendChatAction : ClientAction {
    override val id = "shigusdream:send_chat"
    override val displayName = "Send Chat"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "message", type = FieldType.STRING, required = true, maxLength = 256, description = "Сообщение (не команда)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val message = context.args.get("message")?.asString ?: return ActionResult.fail("missing message")
        if (message.startsWith("/")) return ActionResult.fail("commands are not allowed")
        val player = Minecraft.getInstance().player ?: return ActionResult.fail("no_player")
        player.connection.sendChat(message)
        return ActionResult.ok()
    }
}
