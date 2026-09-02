package com.shigusdream

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionValidator
import com.shigusdream.actions.impl.NotificationAction
import com.shigusdream.actions.impl.PlaySoundAction
import com.shigusdream.actions.impl.ShowMessageAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionValidatorTest {

    @Test
    fun `show_message valid args`() {
        val args = JsonObject().apply {
            addProperty("text", "Привет!")
            addProperty("duration", 100)
        }
        assertTrue(ActionValidator.validate(ShowMessageAction.schema, args).isEmpty())
    }

    @Test
    fun `show_message missing required text`() {
        val errors = ActionValidator.validate(ShowMessageAction.schema, JsonObject())
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("missing required field 'text'"))
    }

    @Test
    fun `show_message duration out of range`() {
        val args = JsonObject().apply {
            addProperty("text", "x")
            addProperty("duration", 5000)
        }
        val errors = ActionValidator.validate(ShowMessageAction.schema, args)
        assertTrue(errors.any { it.contains("must be <=") })
    }

    @Test
    fun `notification invalid type rejected`() {
        val args = JsonObject().apply {
            addProperty("title", "T")
            addProperty("type", "banana")
        }
        val errors = ActionValidator.validate(NotificationAction.schema, args)
        assertTrue(errors.any { it.contains("must be one of") })
    }

    @Test
    fun `play_sound unknown extra field rejected`() {
        val args = JsonObject().apply {
            addProperty("sound", "minecraft:entity.player.levelup")
            addProperty("hacker_field", "nope")
        }
        val errors = ActionValidator.validate(PlaySoundAction.schema, args)
        assertTrue(errors.any { it.contains("unknown field") })
    }

    @Test
    fun `identifier validation`() {
        val args = JsonObject().apply { addProperty("sound", "not an identifier!!") }
        val errors = ActionValidator.validate(PlaySoundAction.schema, args)
        assertTrue(errors.any { it.contains("valid identifier") })
    }
}
