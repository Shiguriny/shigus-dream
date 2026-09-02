package com.shigusdream.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

/**
 * Toast-уведомления (системные, как advancement). Тип info/success/warning/error
 * влияет на префикс заголовка.
 */
object NotificationToasts {

    fun show(title: String, description: String, type: String) {
        val client = Minecraft.getInstance()
        val prefix = when (type) {
            "success" -> "✔ "
            "warning" -> "⚠ "
            "error" -> "✖ "
            else -> "ℹ "
        }
        SystemToast.add(
            client.toastManager,
            SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal(prefix + title),
            Component.literal(description),
        )
    }
}
