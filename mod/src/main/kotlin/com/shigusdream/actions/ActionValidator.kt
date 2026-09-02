package com.shigusdream.actions

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Валидация аргументов против схемы (та же семантика, что на backend). */
object ActionValidator {

    fun validate(schema: ActionSchema, args: JsonObject): List<String> {
        val errors = mutableListOf<String>()

        for (field in schema.fields) {
            val value: JsonElement? = args.get(field.key)
            if (value == null || value is JsonNull) {
                if (field.required) errors += "missing required field '${field.key}'"
                continue
            }
            if (value !is JsonPrimitive) {
                errors += "field '${field.key}' must be a primitive value"
                continue
            }
            when (field.type) {
                FieldType.STRING, FieldType.IDENTIFIER -> {
                    if (!value.isString) {
                        errors += "field '${field.key}' must be a string"
                        continue
                    }
                    val s = value.asString
                    if (field.maxLength != null && s.length > field.maxLength) {
                        errors += "field '${field.key}' exceeds max length ${field.maxLength}"
                    }
                    if (field.allowedValues != null && s !in field.allowedValues) {
                        errors += "field '${field.key}' must be one of ${field.allowedValues}"
                    }
                    if (field.type == FieldType.IDENTIFIER) {
                        val idx = s.indexOf(':')
                        if (idx <= 0 || idx == s.length - 1 || !s.matches(IDENTIFIER_REGEX)) {
                            errors += "field '${field.key}' must be a valid identifier like 'minecraft:entity.player.levelup'"
                        }
                    }
                }

                FieldType.INT -> {
                    val n = value.asString.toLongOrNull()
                    if (n == null || value.isString) {
                        errors += "field '${field.key}' must be an integer"
                    } else {
                        checkRange(errors, field, n.toDouble())
                    }
                }

                FieldType.FLOAT -> {
                    val n = value.asString.toDoubleOrNull()
                    if (n == null || value.isString) {
                        errors += "field '${field.key}' must be a number"
                    } else {
                        checkRange(errors, field, n)
                    }
                }

                FieldType.BOOL -> {
                    if (!value.isBoolean) {
                        errors += "field '${field.key}' must be a boolean"
                    }
                }
            }
        }

        for (key in args.keySet()) {
            if (schema.fields.none { it.key == key }) errors += "unknown field '$key'"
        }

        return errors
    }

    private fun checkRange(errors: MutableList<String>, field: SchemaField, value: Double) {
        if (field.min != null && value < field.min) errors += "field '${field.key}' must be >= ${field.min}"
        if (field.max != null && value > field.max) errors += "field '${field.key}' must be <= ${field.max}"
    }

    private val IDENTIFIER_REGEX = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
}
