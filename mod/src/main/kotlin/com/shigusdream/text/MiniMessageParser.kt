package com.shigusdream.text

/**
 * Подмножество MiniMessage: цветовые теги (имена + <#rrggbb>), стили и <reset>.
 * Не зависит от Minecraft — покрыт юнит-тестами.
 */
object MiniMessageParser {

    data class Span(
        val text: String,
        val color: Int?,        // RGB (24 бита)
        val bold: Boolean?,
        val italic: Boolean?,
        val underline: Boolean?,
        val strike: Boolean?,
        val obfuscated: Boolean?,
    )

    data class Style(
        val color: Int? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val obfuscated: Boolean? = null,
    )

    private val COLORS: Map<String, Int> = mapOf(
        "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00, "dark_aqua" to 0x00AAAA,
        "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA, "gold" to 0xFFAA00, "gray" to 0xAAAAAA,
        "dark_gray" to 0x555555, "blue" to 0x5555FF, "green" to 0x55FF55, "aqua" to 0x55FFFF,
        "red" to 0xFF5555, "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
    )

    private val FLAGS: Map<String, (Style, Boolean) -> Style> = mapOf(
        "bold" to { s, v -> s.copy(bold = v) }, "b" to { s, v -> s.copy(bold = v) },
        "italic" to { s, v -> s.copy(italic = v) }, "i" to { s, v -> s.copy(italic = v) },
        "underlined" to { s, v -> s.copy(underline = v) }, "u" to { s, v -> s.copy(underline = v) },
        "strikethrough" to { s, v -> s.copy(strike = v) }, "st" to { s, v -> s.copy(strike = v) },
        "obfuscated" to { s, v -> s.copy(obfuscated = v) }, "obf" to { s, v -> s.copy(obfuscated = v) },
    )

    private fun currentStyle(stack: ArrayDeque<Style>): Style = stack.lastOrNull() ?: Style()

    /** Возвращает список спанов (текст + стиль) для преобразования в Component. */
    fun parseText(input: String): List<Span> {
        val spans = mutableListOf<Span>()
        val stack = ArrayDeque<Style>()
        stack.addLast(Style())
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                val style = currentStyle(stack)
                spans += Span(buffer.toString(), style.color, style.bold, style.italic, style.underline, style.strike, style.obfuscated)
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '<') {
                val close = input.indexOf('>', i)
                if (close == -1) {
                    buffer.append(input.substring(i))
                    break
                }
                val tag = input.substring(i + 1, close).trim()
                i = close + 1
                if (tag.isEmpty()) {
                    buffer.append("<>")
                    continue
                }
                if (tag.startsWith("/")) {
                    // Закрывающий тег: снимаем верхний стиль, если он не корневой.
                    flush()
                    if (stack.size > 1) stack.removeLast()
                    continue
                }
                if (tag == "reset" || tag == "r") {
                    flush()
                    stack.clear()
                    stack.addLast(Style())
                    continue
                }
                val color = COLORS[tag] ?: tag.takeIf { it.matches(HEX_REGEX) }?.let { Integer.parseInt(it.substring(1), 16) }
                val flag = FLAGS[tag]
                when {
                    color != null -> {
                        flush()
                        stack.addLast(currentStyle(stack).copy(color = color))
                    }

                    flag != null -> {
                        flush()
                        stack.addLast(flag(currentStyle(stack), true))
                    }

                    else -> buffer.append("<$tag>") // неизвестный тег — обычный текст
                }
            } else {
                buffer.append(ch)
                i++
            }
        }
        flush()
        return spans
    }

    private val HEX_REGEX = Regex("^#[0-9a-fA-F]{6}$")
}
