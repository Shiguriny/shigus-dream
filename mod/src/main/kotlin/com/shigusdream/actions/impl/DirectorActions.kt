package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.client.AmbientAudio
import com.shigusdream.client.CameraShake
import com.shigusdream.client.CinematicFx
import com.shigusdream.client.ScreenFx
import com.shigusdream.client.SoundPlayback
import net.minecraft.client.Minecraft

/** Кинематографический режим: letterbox + скрытие HUD. */
object CinematicAction : ClientAction {
    override val id = "shigusdream:cinematic"
    override val displayName = "Cinematic"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "duration", type = FieldType.INT, min = 40.0, max = 24000.0, default = 400, description = "Тики (20/с)"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 400
        CinematicFx.start(duration)
        return ActionResult.ok()
    }
}

/** Тряска камеры: style fine|wide. */
object CameraShakeAction : ClientAction {
    override val id = "shigusdream:camera_shake"
    override val displayName = "Camera Shake"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "duration", type = FieldType.INT, required = true, min = 10.0, max = 600.0, default = 40, description = "Тики (20/с)"),
            SchemaField(key = "intensity", type = FieldType.FLOAT, min = 0.1, max = 3.0, default = 1.0, description = "Сила тряски"),
            SchemaField(key = "style", type = FieldType.STRING, allowedValues = listOf("fine", "wide"), default = "fine", description = "Мелкая дрожь или широкие качания"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val duration = context.args.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 40
        val intensity = context.args.get("intensity")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        val style = context.args.get("style")?.takeIf { it.isJsonPrimitive }?.asString ?: "fine"
        CameraShake.start(duration, intensity, style)
        return ActionResult.ok()
    }
}

/** Позиционный/зацикленный эмбиент-звук. */
object PlayAmbientAction : ClientAction {
    override val id = "shigusdream:play_ambient"
    override val displayName = "Play Ambient"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "sound", type = FieldType.IDENTIFIER, required = true, description = "Например minecraft:ambient.cave"),
            SchemaField(key = "mode", type = FieldType.STRING, allowedValues = listOf("point", "follow"), default = "point", description = "Точка или позиция игрока-цели"),
            SchemaField(key = "x", type = FieldType.FLOAT, description = "X точки"),
            SchemaField(key = "y", type = FieldType.FLOAT, description = "Y точки"),
            SchemaField(key = "z", type = FieldType.FLOAT, description = "Z точки"),
            SchemaField(key = "interval", type = FieldType.INT, min = 5.0, max = 1200.0, default = 40, description = "Повтор каждые N тиков"),
            SchemaField(key = "duration", type = FieldType.INT, required = true, min = 20.0, max = 24000.0, description = "Общая длительность в тиках"),
            SchemaField(key = "volume", type = FieldType.FLOAT, min = 0.0, max = 4.0, default = 1.0, description = "Громкость"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val sound = a.get("sound")?.asString ?: return ActionResult.fail("missing sound")
        val mode = a.get("mode")?.takeIf { it.isJsonPrimitive }?.asString ?: "point"
        val interval = a.get("interval")?.takeIf { it.isJsonPrimitive }?.asInt ?: 40
        val duration = a.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
        val volume = a.get("volume")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        var x = a.get("x")?.takeIf { it.isJsonPrimitive }?.asDouble
        var y = a.get("y")?.takeIf { it.isJsonPrimitive }?.asDouble
        var z = a.get("z")?.takeIf { it.isJsonPrimitive }?.asDouble
        if (mode == "point" && (x == null || y == null || z == null)) {
            // Точка не задана — берём позицию самого игрока-цели в момент получения.
            val p = Minecraft.getInstance().player ?: return ActionResult.fail("no_player")
            x = p.x; y = p.y; z = p.z
        }
        AmbientAudio.play(sound, mode, x ?: 0.0, y ?: 0.0, z ?: 0.0, interval, duration, volume)
        return ActionResult.ok()
    }
}

/** Микро-пугалки: случайный вариант из набора (flash, blink, shake, шёпот за спиной). */
object ScareAction : ClientAction {
    override val id = "shigusdream:scare"
    override val displayName = "Scare"
    override val schema = ActionSchema(
        listOf(
            SchemaField(key = "intensity", type = FieldType.FLOAT, min = 0.1, max = 2.0, default = 1.0, description = "Сила испуга"),
        ),
    )

    private val WHISPERS = listOf(
        "minecraft:ambient.cave",
        "minecraft:entity.enderman.teleport",
        "minecraft:entity.warden.heartbeat",
        "minecraft:block.portal.ambient",
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val intensity = context.args.get("intensity")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1.0f
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return ActionResult.fail("no_player")

        when ((0..3).random()) {
            0 -> ScreenFx.apply("invert", 4, 1.0f, "#FFFFFF")
            1 -> ScreenFx.apply("darkening", (15 * intensity).toInt().coerceAtLeast(10), 1.0f, "#FFFFFF")
            2 -> CameraShake.start(25, 0.8f * intensity, "fine")
            else -> {
                // Звук «за спиной»: случайная точка позади игрока
                val yaw = Math.toRadians(player.yRot.toDouble())
                val dist = 4.0 + Math.random() * 4.0
                val x = player.x - Math.sin(yaw) * dist
                val z = player.z + Math.cos(yaw) * dist
                val sound = WHISPERS.random()
                SoundPlayback.playAt(sound, x, player.y + 1.0, z, 0.9f * intensity)
            }
        }
        return ActionResult.ok()
    }
}

/** Выделение игрока (glow) или блока (HUD-маркер). */
object HighlightAction : ClientAction {
    override val id = "shigusdream:highlight"
    override val displayName = "Highlight"
    override val schema = ActionSchema(
        listOf(
            SchemaField(
                key = "what", type = FieldType.STRING, required = true,
                allowedValues = listOf("entity", "block"),
                description = "Кого/что выделять",
            ),
            SchemaField(key = "entity", type = FieldType.STRING, maxLength = 16, description = "Ник игрока (для entity)"),
            SchemaField(key = "pos", type = FieldType.STRING, maxLength = 64, description = "Блок в формате x y z (для block)"),
            SchemaField(key = "duration", type = FieldType.INT, required = true, min = 20.0, max = 12000.0, description = "Тики (20/с)"),
            SchemaField(key = "color", type = FieldType.STRING, maxLength = 7, default = "#FF5555", description = "Цвет маркера блока, #RRGGBB"),
        ),
    )

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        val a = context.args
        val what = a.get("what")?.asString ?: return ActionResult.fail("missing what")
        val duration = a.get("duration")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
        val color = runCatching {
            Integer.parseInt((a.get("color")?.asString ?: "#FF5555").removePrefix("#"), 16) and 0xFFFFFF
        }.getOrDefault(0xFF5555)
        val mc = Minecraft.getInstance()

        when (what) {
            "entity" -> {
                val name = a.get("entity")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return ActionResult.fail("missing entity")
                val target = mc.level?.players()?.firstOrNull {
                    it.gameProfile.name.equals(name, ignoreCase = true)
                } ?: return ActionResult.fail("player not found: $name")
                com.shigusdream.client.Highlight.addGlow(target.uuid, duration)
                return ActionResult.ok()
            }

            "block" -> {
                val posStr = a.get("pos")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: return ActionResult.fail("missing pos (x y z)")
                val parts = posStr.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                if (parts.size != 3) return ActionResult.fail("pos must be 'x y z'")
                com.shigusdream.client.Highlight.addBlock(
                    id = "block:${parts.joinToString(",")}",
                    pos = net.minecraft.world.phys.Vec3(parts[0] + 0.5, parts[1] + 0.5, parts[2] + 0.5),
                    color = color,
                    ticks = duration,
                )
                return ActionResult.ok()
            }

            else -> return ActionResult.fail("unknown what: $what")
        }
    }
}
