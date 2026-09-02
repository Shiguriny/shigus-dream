package com.shigusdream.backend.auth

import com.shigusdream.backend.repository.User
import java.util.UUID

/**
 * Проверка прав до доставки команды.
 * Роль admin имеет все права ("client.admin" + все действия).
 * Обычным пользователям права выдаются точечно: "client.action.<name>".
 */
class PermissionService(private val permissionsOf: (UUID) -> Set<String>) {

    fun has(user: User, permission: String): Boolean =
        user.isAdmin || permissionsOf(user.id).contains(permission)

    /** Точечные права пользователя (без учёта роли). */
    fun explicitPermissions(user: User): Set<String> = permissionsOf(user.id)

    companion object {
        const val ADMIN_PERMISSION = "client.admin"
    }
}
