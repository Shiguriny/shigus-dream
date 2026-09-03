package com.shigusdream

import com.shigusdream.config.ConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/**
 * Интеграция с Mod Menu: кнопка «Настройки» у мода открывает ConfigScreen.
 * Загружается только при наличии Mod Menu (entrypoint "modmenu").
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> ConfigScreen(parent) }
}
