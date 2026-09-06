package com.shigusdream.actions.impl

import com.google.gson.JsonObject
import com.shigusdream.actions.ActionContext
import com.shigusdream.actions.ActionResult
import com.shigusdream.actions.ClientAction
import com.shigusdream.actions.ActionSchema
import com.shigusdream.actions.FieldType
import com.shigusdream.actions.SchemaField
import com.shigusdream.ShigusDream
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/**
 * shigusdream:cpm_model — управление CPM-моделью цели.
 * Сервер должен иметь CPM для синхронизации. Работает через reflection — без compile-зависимости.
 *
 * mode=list  — возвращает список .cpmmodel файлов цели (через note)
 * mode=apply — применяет указанную модель к игроку (через NetHandler.setSkin)
 * mode=reset — сбрасывает на ванильный скин
 */
object CpmModelAction : ClientAction {
    override val id = "shigusdream:cpm_model"
    override val displayName = "CPM Model"
    override val schema = ActionSchema(
        listOf(
            SchemaField(
                key = "mode", type = FieldType.STRING, required = true,
                allowedValues = listOf("list", "apply", "reset"),
                description = "Операция с моделью",
            ),
            SchemaField(key = "model", type = FieldType.STRING, maxLength = 128, description = "Имя файла модели (для apply)"),
        ),
    )

    private const val CPM_MODELS_DIR = "player_models"

    override fun execute(client: Any?, context: ActionContext): ActionResult {
        // CPM должен быть загружен
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cpm")) {
            return ActionResult.fail("CPM не установлен на этом клиенте")
        }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return ActionResult.fail("no_player")

        val mode = context.args.get("mode")?.asString ?: return ActionResult.fail("missing mode")
        val modelsDir = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("config").resolve(CPM_MODELS_DIR)

        return when (mode) {
            "list" -> listModels(modelsDir)
            "apply" -> {
                val modelName = context.args.get("model")?.asString
                    ?: return ActionResult.fail("missing model filename")
                applyModel(player, modelsDir, modelName)
            }
            "reset" -> resetModel(player)
            else -> ActionResult.fail("unknown mode: $mode")
        }
    }

    // ------------------------------------------------------------------ list

    private fun listModels(modelsDir: Path): ActionResult {
        if (!Files.isDirectory(modelsDir)) return ActionResult.note("Моделей нет (папка $CPM_MODELS_DIR пуста)")
        val files = Files.list(modelsDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".cpmmodel") }
                .sorted()
                .toList()
        }
        if (files.isEmpty()) return ActionResult.note("Моделей нет")
        return ActionResult.note("Модели: ${files.joinToString(", ")}")
    }

    // ------------------------------------------------------------------ apply

    private fun applyModel(player: net.minecraft.client.player.LocalPlayer, modelsDir: Path, modelName: String): ActionResult {
        val fileName = if (modelName.endsWith(".cpmmodel")) modelName else "$modelName.cpmmodel"
        val modelFile = modelsDir.resolve(fileName)
        if (!Files.isRegularFile(modelFile)) {
            return ActionResult.fail("Модель не найдена: $fileName")
        }
        val bytes = try {
            Files.readAllBytes(modelFile)
        } catch (e: Exception) {
            return ActionResult.fail("Не удалось прочитать: ${e.message}")
        }

        return try {
            // MinecraftClientAccess.get().getNetHandler().setSkin(player, bytes, true)
            val accessClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess")
            val access = accessClass.getMethod("get").invoke(null)
            val netHandler = accessClass.getMethod("getNetHandler").invoke(access)

            val setSkinMethod = netHandler.javaClass.methods.firstOrNull {
                it.name == "setSkin" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[1] == ByteArray::class.java
            } ?: return ActionResult.fail("setSkin(byte[]) не найден в NetHandler — несовместимая версия CPM")

            setSkinMethod.invoke(netHandler, player, bytes, true)
            ShigusDream.LOGGER.info("CPM модель {} применена", fileName)
            ActionResult.ok()
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("CPM apply failed", e)
            ActionResult.fail("CPM ошибка: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ reset

    private fun resetModel(player: net.minecraft.client.player.LocalPlayer): ActionResult {
        return try {
            val accessClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess")
            val access = accessClass.getMethod("get").invoke(null)
            val netHandler = accessClass.getMethod("getNetHandler").invoke(access)

            // setSkin(String modelData, ...) с null/пустой строкой сбрасывает модель
            val setSkinMethod = netHandler.javaClass.methods.firstOrNull {
                it.name == "setSkin" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[1] == String::class.java
            }
            if (setSkinMethod != null) {
                setSkinMethod.invoke(netHandler, player, "", true, true)
                ShigusDream.LOGGER.info("CPM модель сброшена")
                ActionResult.ok()
            } else {
                ActionResult.fail("setSkin(String) не найден")
            }
        } catch (e: Exception) {
            ShigusDream.LOGGER.warn("CPM reset failed", e)
            ActionResult.fail("CPM ошибка: ${e.message}")
        }
    }
}
