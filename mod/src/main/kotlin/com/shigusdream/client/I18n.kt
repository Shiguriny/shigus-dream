package com.shigusdream.client

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/** Small client-side localization facade used by screens and chat feedback. */
object I18n {
    fun component(key: String, vararg args: Any): MutableComponent = Component.translatable(key, *args)
    fun text(key: String, vararg args: Any): String = component(key, *args).string
}
