package com.shigusdream.admin

import com.shigusdream.ShigusDreamRuntime
import com.shigusdream.client.I18n
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class GroupsScreen(private val parent: Screen) :
    Screen(Minecraft.getInstance(), Minecraft.getInstance().font, I18n.component("shigusdream.groups.title")) {

    private var left = 12
    private var boxWidth = 500
    private var selectedGroup = 0
    private var groupScroll = 0
    private var userScroll = 0

    override fun init() {
        clearWidgets()
        boxWidth = minOf(560, width - 24).coerceAtLeast(240)
        left = (width - boxWidth) / 2
        val third = (boxWidth - 12) / 3
        addRenderableWidget(UiButton(left, height - 54, third, 20, I18n.component("shigusdream.groups.new"), {
            AdminDataStore.createGroup()
            selectedGroup = AdminDataStore.groups.lastIndex
        }, true))
        addRenderableWidget(UiButton(left + third + 6, height - 54, third, 20, I18n.component("shigusdream.common.delete"), {
            AdminDataStore.groups.getOrNull(selectedGroup)?.let { AdminDataStore.deleteGroup(it.name) }
            selectedGroup = selectedGroup.coerceIn(0, AdminDataStore.groups.lastIndex.coerceAtLeast(0))
        }))
        addRenderableWidget(UiButton(left + (third + 6) * 2, height - 54, boxWidth - (third + 6) * 2, 20,
            I18n.component("shigusdream.common.back"), ::onClose))
    }

    override fun mouseClicked(e: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = e.x.toInt()
        val my = e.y.toInt()
        val split = left + boxWidth / 3
        val top = 44
        val bottom = height - 68
        if (my in top until bottom) {
            if (mx in left until split) {
                val idx = groupScroll + (my - top) / 22
                if (idx in AdminDataStore.groups.indices) {
                    selectedGroup = idx
                    userScroll = 0
                    return true
                }
            } else if (mx in (split + 8)..(left + boxWidth)) {
                val user = ShigusDreamRuntime.presenceUsers.getOrNull(userScroll + (my - top) / 22)
                val group = AdminDataStore.groups.getOrNull(selectedGroup)
                if (user != null && group != null) {
                    AdminDataStore.toggleGroupMember(group.name, user.username)
                    return true
                }
            }
        }
        return super.mouseClicked(e, doubled)
    }

    override fun mouseScrolled(x: Double, y: Double, xDelta: Double, yDelta: Double): Boolean {
        val rows = ((height - 112) / 22).coerceAtLeast(1)
        if (x < left + boxWidth / 3) {
            groupScroll = (groupScroll - yDelta.toInt()).coerceIn(0, (AdminDataStore.groups.size - rows).coerceAtLeast(0))
        } else {
            userScroll = (userScroll - yDelta.toInt()).coerceIn(0, (ShigusDreamRuntime.presenceUsers.size - rows).coerceAtLeast(0))
        }
        return true
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        g.fill(left - 8, 8, left + boxWidth + 8, height - 4, 0xDE10101A.toInt())
        g.outline(left - 8, 8, left + boxWidth + 8, height - 4, 0xFF34344C.toInt())
        g.text(font, title, left, 16, -1)
        val split = left + boxWidth / 3
        g.text(font, I18n.component("shigusdream.groups.groups"), left, 32, 0xFFA0A0B0.toInt())
        g.text(font, I18n.component("shigusdream.groups.members"), split + 8, 32, 0xFFA0A0B0.toInt())
        g.fill(split, 30, split + 1, height - 68, 0xFF4A4A6A.toInt())
        val rows = ((height - 112) / 22).coerceAtLeast(1)
        repeat(rows) { row ->
            val groupIndex = groupScroll + row
            AdminDataStore.groups.getOrNull(groupIndex)?.let { group ->
                val y = 44 + row * 22
                if (groupIndex == selectedGroup) g.fill(left, y, split - 4, y + 20, 0xFF2A3A55.toInt())
                g.text(font, Component.literal("${group.name} (${group.members.size})"), left + 5, y + 6, -1)
            }
            val user = ShigusDreamRuntime.presenceUsers.getOrNull(userScroll + row)
            val group = AdminDataStore.groups.getOrNull(selectedGroup)
            if (user != null) {
                val y = 44 + row * 22
                val selected = user.username in (group?.members ?: emptySet())
                g.text(font, Component.literal(if (selected) "☑ ${user.username}" else "☐ ${user.username}"),
                    split + 13, y + 6, if (selected) 0xFF55FF55.toInt() else 0xFFC8C8D8.toInt())
            }
        }
        if (AdminDataStore.groups.isEmpty()) g.text(font, I18n.component("shigusdream.groups.empty"), left + 5, 50, 0xFF909090.toInt())
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)
}
