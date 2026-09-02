package com.shigusdream.actions

import com.google.gson.JsonObject

/** Тип поля схемы действия; Admin Panel строит поля ввода динамически. */
enum class FieldType(val wireName: String) {
    STRING("string"),
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    IDENTIFIER("identifier"),
}

data class SchemaField(
    val key: String,
    val type: FieldType,
    val required: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val maxLength: Int? = null,
    val allowedValues: List<String>? = null,
    val default: Any? = null,
    val description: String = "",
)

data class ActionSchema(val fields: List<SchemaField>) {
    fun toJson(): JsonObject {
        val arr = com.google.gson.JsonArray()
        for (f in fields) {
            val o = JsonObject()
            o.addProperty("key", f.key)
            o.addProperty("type", f.type.wireName)
            o.addProperty("required", f.required)
            f.min?.let { o.addProperty("min", it) }
            f.max?.let { o.addProperty("max", it) }
            f.maxLength?.let { o.addProperty("max_length", it) }
            f.allowedValues?.let { o.add("allowed_values", com.google.gson.JsonArray().apply { it.forEach { v -> add(com.google.gson.JsonPrimitive(v)) } }) }
            f.default?.let { o.addProperty("default", it.toString()) }
            o.addProperty("description", f.description)
            arr.add(o)
        }
        return JsonObject().apply { add("fields", arr) }
    }
}

/** Контекст выполнения: request_id для ответа и аргументы действия. */
data class ActionContext(
    val requestId: String,
    val args: JsonObject,
)

data class ActionResult(
    val executed: Boolean,
    val error: String? = null,
) {
    companion object {
        fun ok() = ActionResult(true)
        fun fail(error: String) = ActionResult(false, error)
    }
}

/**
 * Разрешены только заранее зарегистрированные Client Actions.
 * Произвольный код, shell, eval и т.п. — запрещены архитектурой.
 */
interface ClientAction {
    val id: String
    val displayName: String
    val schema: ActionSchema

    /** Выполняется на клиентском потоке Minecraft (MinecraftClient.execute). */
    fun execute(client: Any?, context: ActionContext): ActionResult
}
