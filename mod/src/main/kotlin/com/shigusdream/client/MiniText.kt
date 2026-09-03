package com.shigusdream.client

import com.shigusdream.text.MiniMessageParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

/** Преобразование MiniMessage-спанов в MC-компоненты. */
object MiniText {

    fun parse(input: String): MutableComponent {
        val root = Component.empty()
        for (span in MiniMessageParser.parseText(input)) {
            root.append(
                Component.literal(span.text).withStyle { style ->
                    var s = style
                    span.color?.let { s = s.withColor(TextColor.fromRgb(it)) }
                    span.bold?.let { s = s.withBold(it) }
                    span.italic?.let { s = s.withItalic(it) }
                    span.underline?.let { s = s.withUnderlined(it) }
                    span.strike?.let { s = s.withStrikethrough(it) }
                    span.obfuscated?.let { s = s.withObfuscated(it) }
                    s
                },
            )
        }
        return root
    }
}
