package com.shigusdream.admin

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.shigusdream.ShigusDream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class CommandHistoryEntry(
    val requestId: String,
    val target: String,
    val action: String,
    val args: JsonObject,
    val sentAt: String = Instant.now().toString(),
    var status: String = "pending",
    var error: String? = null,
    val local: Boolean = false,
)

/** Last commands with delivery result. Kept across restarts and capped at 100 rows. */
object CommandHistory {
    private const val MAX_ENTRIES = 100
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val entries = mutableListOf<CommandHistoryEntry>()
    private lateinit var file: Path

    fun init(configDir: Path) {
        file = configDir.resolve("shigusdream_history.json")
        entries.clear()
        try {
            if (Files.exists(file)) {
                val type = object : TypeToken<List<CommandHistoryEntry>>() {}.type
                entries += gson.fromJson<List<CommandHistoryEntry>>(Files.readString(file), type).orEmpty().takeLast(MAX_ENTRIES)
            }
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Could not read shigusdream_history.json", e)
        }
    }

    @Synchronized
    fun record(requestId: String, target: String, action: String, args: JsonObject, local: Boolean = false, status: String = "pending", error: String? = null) {
        entries += CommandHistoryEntry(requestId, target, action, args.deepCopy(), status = status, error = error, local = local)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        save()
    }

    @Synchronized
    fun complete(requestId: String, status: String, error: String?) {
        entries.lastOrNull { it.requestId == requestId }?.let {
            it.status = status
            it.error = error
            save()
        }
    }

    @Synchronized
    fun newest(): List<CommandHistoryEntry> = entries.asReversed().map { it.copy(args = it.args.deepCopy()) }

    data class Stats(
        val total: Int,
        val executed: Int,
        val failed: Int,
        val topActions: List<Pair<String, Int>>,
        val topTargets: List<Pair<String, Int>>,
    )

    @Synchronized
    fun stats(): Stats {
        val byAction = entries.groupingBy { it.action }.eachCount().toList().sortedByDescending { it.second }.take(5)
        val byTarget = entries.groupingBy { it.target }.eachCount().toList().sortedByDescending { it.second }.take(5)
        return Stats(
            total = entries.size,
            executed = entries.count { it.status == "executed" },
            failed = entries.count { it.status == "failed" },
            topActions = byAction,
            topTargets = byTarget,
        )
    }

    @Synchronized
    private fun save() {
        if (!::file.isInitialized) return
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(entries))
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("Could not save shigusdream_history.json", e)
        }
    }
}
