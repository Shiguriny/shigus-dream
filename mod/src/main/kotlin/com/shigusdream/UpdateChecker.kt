package com.shigusdream

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Проверка обновлений: сравнивает версию мода с артефактом на backend.
 * При наличии новой версии скачивает jar в mods, удаляет старые и просит перезапуск.
 */
object UpdateChecker {

    @Volatile
    private var checkedThisSession = false

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun checkAndDownload(baseUrl: String, modsDir: Path, currentVersion: String, notify: (String) -> Unit) {
        if (checkedThisSession) return
        checkedThisSession = true
        Thread {
            try {
                val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/mod/latest"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return@Thread // артефакт не загружен — тихо выходим

                val json = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
                val latest = json.get("version").asString
                if (compareVersions(latest, currentVersion) <= 0) return@Thread

                notify("§e[Shigu's Dream]§7 Доступно обновление: §fv$latest§7 (у вас v$currentVersion). Скачиваю...")
                val filename = json.get("filename").asString
                val sha256 = json.get("sha256").asString

                val download = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/mod/download"))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build()
                val jarResponse = http.send(download, HttpResponse.BodyHandlers.ofByteArray())
                if (jarResponse.statusCode() != 200) {
                    notify("§c[Shigu's Dream]§7 Не удалось скачать обновление (HTTP ${jarResponse.statusCode()})")
                    return@Thread
                }
                val bytes = jarResponse.body()
                val actualSha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(sha256, ignoreCase = true)) {
                    notify("§c[Shigu's Dream]§7 Контрольная сумма не совпала — обновление отменено")
                    return@Thread
                }

                Files.createDirectories(modsDir)
                val newJar = modsDir.resolve(filename)
                Files.write(newJar, bytes)

                // Удаляем все старые jar мода, кроме freshly скачанного.
                Files.list(modsDir).use { stream ->
                    stream.filter { p ->
                        val name = p.fileName.toString()
                        name.startsWith("shigusdream-") && name.endsWith(".jar") && p != newJar
                    }.forEach { p ->
                        runCatching { Files.deleteIfExists(p) }
                    }
                }

                notify("§a[Shigu's Dream]§7 Обновлено до v$latest. §eПерезапустите игру!")
                ShigusDream.LOGGER.info("Мод обновлён до {} -> {}", currentVersion, filename)
            } catch (e: Exception) {
                ShigusDream.LOGGER.warn("Проверка обновлений не удалась: {}", e.message)
            }
        }.apply { isDaemon = true; name = "shigusdream-update" }.start()
    }

    /** Сравнение версий вида "0.4.1": -1/0/1. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }
}
