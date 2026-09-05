package com.shigusdream.admin

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.shigusdream.ShigusDream
import com.shigusdream.client.I18n
import java.nio.file.Files
import java.nio.file.Path

data class ActionPreset(
    val name: String,
    val action: String,
    val args: JsonObject,
)

data class PlayerGroup(
    val name: String,
    val members: MutableSet<String> = linkedSetOf(),
)

private data class AdminData(
    val favorites: MutableSet<String> = linkedSetOf(),
    val presets: MutableList<ActionPreset> = mutableListOf(),
    val groups: MutableList<PlayerGroup> = mutableListOf(),
)

/** Persistent favorites, argument presets and player groups. */
object AdminDataStore {
    const val GROUP_PREFIX = "@"

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: Path
    private var data = AdminData()

    val favorites: Set<String> get() = data.favorites
    val presets: List<ActionPreset> get() = data.presets
    val groups: List<PlayerGroup> get() = data.groups

    fun init(configDir: Path) {
        file = configDir.resolve("shigusdream_admin.json")
        data = try {
            if (Files.exists(file)) gson.fromJson(Files.readString(file), AdminData::class.java) ?: AdminData()
            else AdminData()
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Could not read shigusdream_admin.json", e)
            AdminData()
        }
        save()
    }

    fun toggleFavorite(action: String): Boolean {
        if (!data.favorites.add(action)) data.favorites.remove(action)
        save()
        return action in data.favorites
    }

    fun createPreset(action: String, args: JsonObject): ActionPreset {
        val base = I18n.text("shigusdream.preset.generated", action.substringAfter(':'))
        var name = base
        var suffix = 2
        while (data.presets.any { it.name == name }) name = "$base $suffix".also { suffix++ }
        return ActionPreset(name, action, args.deepCopy()).also {
            data.presets += it
            save()
        }
    }

    fun deletePreset(name: String) {
        data.presets.removeIf { it.name == name }
        save()
    }

    fun createGroup(): PlayerGroup {
        val base = I18n.text("shigusdream.group.generated")
        var name = base
        var suffix = 2
        while (data.groups.any { it.name == name }) name = "$base $suffix".also { suffix++ }
        return PlayerGroup(name).also {
            data.groups += it
            save()
        }
    }

    fun deleteGroup(name: String) {
        data.groups.removeIf { it.name == name }
        save()
    }

    fun toggleGroupMember(groupName: String, username: String): Boolean {
        val group = data.groups.firstOrNull { it.name == groupName } ?: return false
        if (!group.members.add(username)) group.members.remove(username)
        save()
        return username in group.members
    }

    fun groupTarget(name: String): String = "$GROUP_PREFIX$name"

    fun resolveTargets(selection: String): List<String> {
        if (!selection.startsWith(GROUP_PREFIX)) return listOf(selection)
        val name = selection.removePrefix(GROUP_PREFIX)
        return data.groups.firstOrNull { it.name == name }?.members?.toList().orEmpty()
    }

    fun isGroupTarget(selection: String): Boolean = selection.startsWith(GROUP_PREFIX)

    @Synchronized
    fun save() {
        if (!::file.isInitialized) return
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(data))
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Could not save shigusdream_admin.json", e)
        }
    }
}
