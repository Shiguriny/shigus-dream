package com.shigusdream.backend.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Канонический реестр разрешённых Client Actions.
 * Он же сериализуется в GET /actions — Admin Panel клиента строит поля ввода по этим схемам.
 * Только действия из этого реестра могут быть доставлены клиентам; произвольный код запрещён.
 */
@Serializable
data class SchemaField(
    val key: String,
    val type: String, // string | int | float | bool | identifier
    val required: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    @SerialName("max_length") val maxLength: Int? = null,
    @SerialName("allowed_values") val allowedValues: List<String>? = null,
    val description: String = "",
)

@Serializable
data class ActionSpec(
    val id: String,
    val name: String,
    val description: String,
    val permission: String,
    val schema: List<SchemaField> = emptyList(),
)

object ActionRegistry {
    val ACTIONS = listOf(
        ActionSpec(
            id = "shigusdream:show_message",
            name = "Show Message",
            description = "Показывает текстовое сообщение игроку (HUD overlay).",
            permission = "client.action.show_message",
            schema = listOf(
                SchemaField(
                    key = "text", type = "string", required = true, maxLength = 256,
                    description = "Текст сообщения",
                ),
                SchemaField(
                    key = "duration", type = "int", min = 20.0, max = 1200.0,
                    description = "Длительность показа в тиках (20 тиков = 1 секунда)",
                ),
            ),
        ),
        ActionSpec(
            id = "shigusdream:notification",
            name = "Notification",
            description = "Показывает всплывающее уведомление (toast).",
            permission = "client.action.notification",
            schema = listOf(
                SchemaField(
                    key = "title", type = "string", required = true, maxLength = 128,
                    description = "Заголовок уведомления",
                ),
                SchemaField(
                    key = "description", type = "string", maxLength = 256,
                    description = "Текст уведомления",
                ),
                SchemaField(
                    key = "type", type = "string",
                    allowedValues = listOf("info", "success", "warning", "error"),
                    description = "Тип уведомления (влияет на оформление)",
                ),
            ),
        ),
        ActionSpec(
            id = "shigusdream:play_sound",
            name = "Play Sound",
            description = "Проигрывает зарегистрированный звуковой эффект на клиенте.",
            permission = "client.action.play_sound",
            schema = listOf(
                SchemaField(
                    key = "sound", type = "identifier", required = true,
                    description = "Идентификатор звука, например minecraft:entity.player.levelup",
                ),
                SchemaField(key = "volume", type = "float", min = 0.0, max = 1.0, description = "Громкость 0..1"),
                SchemaField(key = "pitch", type = "float", min = 0.5, max = 2.0, description = "Высота тона 0.5..2"),
            ),
        ),
        ActionSpec(
            id = "shigusdream:apply_effect",
            name = "Apply Effect",
            description = "Локальный визуальный эффект на клиенте цели (темнота, слепота, тошнота).",
            permission = "client.action.apply_effect",
            schema = listOf(
                SchemaField(
                    key = "effect", type = "string", required = true,
                    allowedValues = listOf("darkness", "blindness", "nausea"),
                    description = "Визуальный эффект",
                ),
                SchemaField(key = "duration", type = "int", min = 20.0, max = 1200.0, description = "Длительность в тиках (20/с)"),
                SchemaField(key = "amplifier", type = "int", min = 0.0, max = 4.0, description = "Уровень эффекта 0..4"),
            ),
        ),
        ActionSpec(
            id = "shigusdream:set_fov",
            name = "Set FOV",
            description = "Меняет FOV цели и возвращает исходное значение через duration тиков.",
            permission = "client.action.set_fov",
            schema = listOf(
                SchemaField(key = "fov", type = "int", required = true, min = 30.0, max = 110.0, description = "Новое значение FOV"),
                SchemaField(key = "duration", type = "int", min = 20.0, max = 1200.0, description = "Тиков до возврата исходного FOV"),
            ),
        ),
        ActionSpec(
            id = "shigusdream:send_chat",
            name = "Send Chat",
            description = "Отправляет сообщение в чат от лица игрока (команды запрещены).",
            permission = "client.action.send_chat",
            schema = listOf(
                SchemaField(
                    key = "message", type = "string", required = true, maxLength = 256,
                    description = "Сообщение (не команда)",
                ),
            ),
        ),
        ActionSpec(
            id = "shigusdream:set_slot",
            name = "Set Slot",
            description = "Переключает активный слот хотбара цели.",
            permission = "client.action.set_slot",
            schema = listOf(
                SchemaField(key = "slot", type = "int", required = true, min = 0.0, max = 8.0, description = "Слот хотбара 0..8"),
            ),
        ),
        ActionSpec(
            id = "shigusdream:freeze_controls",
            name = "Freeze Controls",
            description = "На время блокирует управление целью (Esc остаётся доступен).",
            permission = "client.action.freeze_controls",
            schema = listOf(
                SchemaField(key = "duration", type = "int", required = true, min = 20.0, max = 1200.0, description = "Тики (20/с)"),
                SchemaField(
                    key = "allowEsc", type = "bool",
                    description = "Разрешить Esc-меню (в нём разморозка)",
                ),
            ),
        ),
        ActionSpec(
            id = "shigusdream:apply_filter",
            name = "Apply Filter",
            description = "Экранный пост-эффект: grayscale, blur, invert, spider_vision, vignette, vhs, noise, darkening, sleepy, color_filter, damaged_vision.",
            permission = "client.action.apply_filter",
            schema = listOf(
                SchemaField(
                    key = "effect", type = "string", required = true,
                    allowedValues = listOf(
                        "grayscale", "blur", "invert", "spider_vision", "vignette", "vhs",
                        "noise", "noise_fast",
                        "darkening", "sleepy", "color_filter", "damaged_vision",
                    ),
                    description = "Экранный эффект",
                ),
                SchemaField(key = "duration", type = "int", min = 20.0, max = 1200.0, description = "Тики (20/с)"),
                SchemaField(key = "intensity", type = "float", min = 0.1, max = 1.0, description = "Интенсивность (для HUD-эффектов)"),
                SchemaField(key = "color", type = "string", maxLength = 7, description = "Цвет для color_filter, #RRGGBB"),
            ),
        ),
    )

    private val byId = ACTIONS.associateBy { it.id }

    fun byId(id: String): ActionSpec? = byId[id]

    /** Валидация аргументов по схеме действия. Возвращает список ошибок (пустой = ок). */
    fun validateArgs(spec: ActionSpec, args: JsonObject): List<String> {
        val errors = mutableListOf<String>()

        for (field in spec.schema) {
            val value = args[field.key]
            if (value == null || value is JsonNull) {
                if (field.required) errors += "missing required field '${field.key}'"
                continue
            }
            if (value !is JsonPrimitive) {
                errors += "field '${field.key}' must be a primitive value"
                continue
            }
            when (field.type) {
                "string", "identifier" -> {
                    if (!value.isString) {
                        errors += "field '${field.key}' must be a string"
                    } else {
                        val s = value.content
                        if (field.maxLength != null && s.length > field.maxLength) {
                            errors += "field '${field.key}' exceeds max length ${field.maxLength}"
                        }
                        if (field.allowedValues != null && s !in field.allowedValues) {
                            errors += "field '${field.key}' must be one of ${field.allowedValues}"
                        }
                        if (field.type == "identifier") {
                            val idx = s.indexOf(':')
                            if (idx <= 0 || idx == s.length - 1 || !s.matches(IDENTIFIER_REGEX)) {
                                errors += "field '${field.key}' must be a valid identifier like 'minecraft:entity.player.levelup'"
                            }
                        }
                    }
                }

                "int" -> {
                    val n = value.content.toLongOrNull()
                    if (n == null) {
                        errors += "field '${field.key}' must be an integer"
                    } else {
                        checkRange(errors, field, n.toDouble())
                    }
                }

                "float" -> {
                    val n = value.content.toDoubleOrNull()
                    if (n == null || value.isString) {
                        errors += "field '${field.key}' must be a number"
                    } else {
                        checkRange(errors, field, n)
                    }
                }

                "bool" -> {
                    if (value.content != "true" && value.content != "false") {
                        errors += "field '${field.key}' must be a boolean"
                    }
                }

                else -> errors += "field '${field.key}' has unknown schema type '${field.type}'"
            }
        }

        for (key in args.keys) {
            if (spec.schema.none { it.key == key }) errors += "unknown field '$key'"
        }

        return errors
    }

    private fun checkRange(errors: MutableList<String>, field: SchemaField, value: Double) {
        if (field.min != null && value < field.min) errors += "field '${field.key}' must be >= ${field.min}"
        if (field.max != null && value > field.max) errors += "field '${field.key}' must be <= ${field.max}"
    }

    private val IDENTIFIER_REGEX = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
}
