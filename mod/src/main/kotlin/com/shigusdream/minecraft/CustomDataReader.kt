package com.shigusdream.minecraft

import com.shigusdream.ShigusDream
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Чтение компонента minecraft:custom_data у предметов.
 * Используется только для локальной идентификации специальных предметов
 * (например, admin_wand) — НЕ является источником прав доступа.
 */
object CustomDataReader {

    data class ShiguData(val type: String, val id: String, val version: Int)

    /** Достаёт shigusdream-блок из minecraft:custom_data стека. */
    fun read(stack: ItemStack?): ShiguData? {
        if (stack == null || stack.isEmpty) return null
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val root = customData.copyTag()
            if (!root.contains("shigusdream")) return null
            val shigu = root.getCompoundOrEmpty("shigusdream")
            ShiguData(
                type = shigu.getStringOr("type", ""),
                id = shigu.getStringOr("id", ""),
                version = shigu.getIntOr("version", 0),
            )
        } catch (e: Exception) {
            ShigusDream.LOGGER.debug("Не удалось прочитать custom_data: {}", e.message)
            null
        }
    }

    /** Является ли предмет «жезлом администратора» (custom_data: shigusdream{id:"admin_wand",type:"control_item"}). */
    fun isAdminWand(stack: ItemStack?): Boolean {
        val data = read(stack) ?: return false
        return data.id == "admin_wand" && data.type == "control_item"
    }
}
