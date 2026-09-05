package com.shigusdream

import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft

/**
 * /shigu users  — список аккаунтов с ролями и статусами.
 * /shigu role <ник> <admin|user> — выдать/снять админку (только для владельца).
 * Выполняется на стороне backend через admin-API с access-токеном.
 */
object RoleCommand {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("shigu")
                    .then(ClientCommands.literal("config").executes {
                        net.minecraft.client.Minecraft.getInstance().setScreen(
                            com.shigusdream.config.ConfigScreen(),
                        )
                        1
                    })
                    .then(ClientCommands.literal("upload").executes { upload(); 1 })
                    .then(ClientCommands.literal("users").executes { list(it); 1 })
                    .then(
                        ClientCommands.literal("role")
                            .then(
                                ClientCommands.argument("username", StringArgumentType.word())
                                    .then(
                                        ClientCommands.argument("role", StringArgumentType.word())
                                            .executes { ctx ->
                                                setRole(
                                                    StringArgumentType.getString(ctx, "username"),
                                                    StringArgumentType.getString(ctx, "role"),
                                                )
                                                1
                                            },
                                    ),
                            ),
                    ),
            )
        }
    }

    /** Заливает собственный jar мода на backend (/shigu upload). */
    private fun upload() {
        if (!ShigusDreamClient.isAdminOrOwner) {
            ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Команда только для администраторов")
            return
        }
        // Loader отдаёт rootPaths как корень jar-файлсистемы, а не файл — ищем jar в папке mods.
        val modsDir = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("mods")
        val exact = "shigusdream-${ShigusDreamClient.modVersion()}.jar"
        val jar = java.nio.file.Files.list(modsDir).use { stream ->
            val candidates = stream
                .filter { p ->
                    val name = p.fileName.toString()
                    name.startsWith("shigusdream-") && name.endsWith(".jar")
                }
                .toList()
            candidates.firstOrNull { it.fileName.toString() == exact }
                ?: candidates.maxByOrNull { java.nio.file.Files.getLastModifiedTime(it).toMillis() }
        }
        if (jar == null) {
            ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 shigusdream-*.jar не найден в ${modsDir.toAbsolutePath()}")
            return
        }
        val version = ShigusDreamClient.modVersion()
        Thread {
            ShigusDreamClient.chatFeedback("§7[Shigu's Dream] Загружаю v$version на backend...")
            val (status, body) = ShigusDreamClient.auth.uploadModJar(jar, version)
            when {
                status == 200 -> ShigusDreamClient.chatFeedback("§a[Shigu's Dream]§7 Обновление залито: §fv$version§7 — игроки получат его при следующем входе в мир")
                status == 401 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Токен истёк — переподключитесь (J) и повторите")
                status == 403 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Недостаточно прав (нужен admin/owner)")
                else -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Ошибка загрузки (HTTP $status) ${body?.take(120) ?: ""}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun list(ctx: CommandContext<*>) {
        if (!ShigusDreamClient.isAdminOrOwner) {
            ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Команда только для администраторов")
            return
        }
        Thread {
            val (status, body) = ShigusDreamClient.auth.adminApi("GET", "/users")
            when {
                status == 200 && body != null -> {
                    val users = JsonParser.parseString(body).asJsonObject.getAsJsonArray("users")
                    ShigusDreamClient.chatFeedback("§b[Shigu's Dream]§7 Аккаунты (${users.size()}):")
                    for (u in users) {
                        val o = u.asJsonObject
                        val dot = if (o.get("online").asBoolean) "§a●" else "§7○"
                        ShigusDreamClient.chatFeedback(" $dot §f${o.get("username").asString} §7— ${o.get("role").asString}")
                    }
                }

                status == 401 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Токен истёк — переподключитесь (J)")
                status == 403 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Недостаточно прав")
                else -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Ошибка запроса (HTTP $status)")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun setRole(username: String, role: String) {
        if (!ShigusDreamClient.isOwner) {
            ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Управлять ролями может только владелец (owner)")
            return
        }
        if (role != "admin" && role != "user") {
            ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Роль должна быть admin или user")
            return
        }
        Thread {
            val (status, body) = ShigusDreamClient.auth.adminApi("GET", "/users")
            val userId = if (status == 200 && body != null) {
                JsonParser.parseString(body).asJsonObject.getAsJsonArray("users")
                    .firstOrNull { u -> u.asJsonObject.get("username").asString.equals(username, ignoreCase = true) }
                    ?.asJsonObject?.get("id")?.asString
            } else null
            if (userId == null) {
                ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Пользователь '$username' не найден")
                return@Thread
            }
            val (postStatus, postBody) = ShigusDreamClient.auth.adminApi(
                "POST",
                "/users/$userId/role",
                """{"role":"$role"}""",
            )
            when {
                postStatus == 200 -> ShigusDreamClient.chatFeedback("§a[Shigu's Dream]§7 $username → $role")
                postStatus == 401 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Токен истёк — переподключитесь (J)")
                postStatus == 403 -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Только владелец может менять роли")
                else -> ShigusDreamClient.chatFeedback("§c[Shigu's Dream]§7 Ошибка (HTTP $postStatus) ${postBody?.take(120) ?: ""}")
            }
        }.apply { isDaemon = true }.start()
    }
}
