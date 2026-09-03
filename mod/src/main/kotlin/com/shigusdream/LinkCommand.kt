package com.shigusdream

import com.mojang.brigadier.arguments.StringArgumentType
import com.shigusdream.auth.confirmLink
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft

/**
 * /link <код> — подтверждение привязки без браузера.
 * Именем аккаунта становится ник игрока; backend привязывает UUID и
 * завершает аутентификацию по уже открытому WS-соединению.
 */
object LinkCommand {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("link")
                    .then(
                        ClientCommands.argument("code", StringArgumentType.word())
                            .executes { ctx ->
                                val code = StringArgumentType.getString(ctx, "code")
                                ShigusDreamClient.confirmLinkCode(code)
                                1
                            },
                    ),
            )
        }
    }
}
