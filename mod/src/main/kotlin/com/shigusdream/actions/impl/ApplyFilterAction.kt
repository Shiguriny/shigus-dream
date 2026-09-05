package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.ScreenFx

/**
 * shigusdream:apply_filter — экранный пост-эффект на клиенте цели.
 * Шейдерные (grayscale, blur, invert, spider_vision, vignette, vhs)
 * и HUD-оверлейные (darkening, sleepy, color_filter, damaged_vision, noise).
 */
object ApplyFilterAction : ClientAction {
    override val id = "shigusdream:apply_filter"
    override val displayName = "Apply Filter"
    override val schema = ActionSchema(
        listOf(
            SchemaField(
                key = "effect", type = FieldType.STRING, required = true,
                allowedValues = listOf(
                    "grayscale", "blur", "invert", "spider_vision", "vignette", "vhs",
                    "noise", "noise_fast",
                    "darkening", "sleepy", "color_filter", "damaged_vision",
                ),
                description = "Экранный эффект",
            ),
            SchemaField(key = "duration", type = FieldType.INT, min = 20.0, max = 1200.0, default = 200, description = "Тики (20/с)"),
            SchemaField(key = "intensity", type = FieldType.FLOAT, min = 0.1, max = 1.0, default = 1.0, description = "Интенсивность (для HUD-эффектов)"),
            SchemaField(key = "color", type = FieldType.STRING, maxLength = 7, default = "#FFAA00", description = "Цвет для color_filter, #RRGGBB"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val effect = context.args.get("effect")?.asString ?: return ActionResult.fail("missing effect")
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
        val intensity = context.args.get("intensity")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        val color = context.args.get("color")?.takeIf { it.isJsonPrimitive }?.asString ?: "#FFAA00"
        return ScreenFx.apply(effect, duration, intensity, color)
    }
}
