package com.shigusdream

import com.shigusdream.text.MiniMessageParser.parseText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniMessageTest {

    @Test
    fun `plain text without tags`() {
        val spans = parseText("Привет, мир")
        assertEquals(1, spans.size)
        assertEquals("Привет, мир", spans[0].text)
        assertNull(spans[0].color)
    }

    @Test
    fun `color tag`() {
        val spans = parseText("<red>Опасность")
        assertEquals(1, spans.size)
        assertEquals(0xFF5555, spans[0].color)
        assertEquals("Опасность", spans[0].text)
    }

    @Test
    fun `hex color tag`() {
        val spans = parseText("<#FF8800>Огонь")
        assertEquals(0xFF8800, spans[0].color)
    }

    @Test
    fun `bold inside color inherits color`() {
        val spans = parseText("<red>A<bold>B</bold>C")
        assertEquals(3, spans.size)
        assertEquals("A", spans[0].text)
        assertEquals(0xFF5555, spans[0].color)
        assertEquals("B", spans[1].text)
        assertEquals(0xFF5555, spans[1].color)
        assertEquals(true, spans[1].bold)
        assertEquals("C", spans[2].text)
        assertEquals(0xFF5555, spans[2].color)
        assertEquals(null, spans[2].bold)
    }

    @Test
    fun `reset clears everything`() {
        val spans = parseText("<red><bold>A<reset>B")
        assertEquals("B", spans[1].text)
        assertNull(spans[1].color)
        assertNull(spans[1].bold)
    }

    @Test
    fun `unknown tag stays literal`() {
        val spans = parseText("<foo>бар")
        assertEquals("<foo>бар", spans[0].text)
    }

    @Test
    fun `unclosed tag is literal`() {
        val spans = parseText("abc <red")
        assertEquals("abc <red", spans[0].text)
    }

    @Test
    fun `multiple colors`() {
        val spans = parseText("<red>1</red><green>2")
        assertEquals(2, spans.size)
        assertEquals(0xFF5555, spans[0].color)
        assertEquals("2", spans[1].text)
        assertEquals(0x55FF55, spans[1].color)
    }
}
