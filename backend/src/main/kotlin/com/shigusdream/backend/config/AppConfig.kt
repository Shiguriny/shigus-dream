package com.shigusdream.backend.config

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class AppConfig(
    val port: Int,
    val storage: Storage,
    val jdbcUrl: String,
    /** Учётные данные передаются в Hikari отдельно от URL (pgjdbc не парсит user:pass@ в JDBC-URL). */
    val dbUser: String?,
    val dbPassword: String?,
    val jwtSecret: String,
) {
    enum class Storage { MEMORY, POSTGRES }

    companion object {
        fun fromEnv(): AppConfig {
            val (jdbcUrl, user, password) = resolveJdbcUrl()
            return AppConfig(
                port = System.getenv("SHIGU_PORT")?.toIntOrNull()
                    ?: System.getenv("PORT")?.toIntOrNull()
                    ?: 8080,
                storage = when (System.getenv("SHIGU_STORAGE")?.lowercase()) {
                    "postgres" -> Storage.POSTGRES
                    null, "", "memory" -> Storage.MEMORY
                    else -> {
                        System.err.println("Unknown SHIGU_STORAGE value, falling back to memory")
                        Storage.MEMORY
                    }
                },
                jdbcUrl = jdbcUrl,
                dbUser = user,
                dbPassword = password,
                jwtSecret = System.getenv("SHIGU_JWT_SECRET") ?: "dev-insecure-secret-change-me",
            )
        }

        /**
         * Поддержка управляемых облачных БД (Render/Railway/Neon/Supabase):
         * SHIGU_DB_JDBC (jdbc:postgresql://...) имеет приоритет, иначе
         * SHIGU_DATABASE_URL / DATABASE_URL в формате postgres://user:pass@host:port/db.
         * Учётные данные извлекаются в Hikari-свойства, а не в URL.
         */
        fun resolveJdbcUrl(): Triple<String, String?, String?> {
            System.getenv("SHIGU_DB_JDBC")?.let {
                return Triple(it, System.getenv("SHIGU_DB_USER"), System.getenv("SHIGU_DB_PASSWORD"))
            }
            val raw = System.getenv("SHIGU_DATABASE_URL")
                ?: System.getenv("DATABASE_URL")
                ?: return Triple("jdbc:postgresql://localhost:5432/shigusdream", "shigu", "shigu")
            return parsePostgresUrl(raw)
        }

        /** postgres://user:pass@host:port/db -> (jdbcUrl, user, pass); sslmode по умолчанию require. */
        fun parsePostgresUrl(raw: String): Triple<String, String?, String?> {
            if (raw.startsWith("jdbc:")) {
                return Triple(raw, System.getenv("SHIGU_DB_USER"), System.getenv("SHIGU_DB_PASSWORD"))
            }
            val uri = URI(raw.trim())
            val userInfo = uri.userInfo ?: ""
            val sep = userInfo.indexOf(':')
            val user = if (sep >= 0) URLDecoder.decode(userInfo.substring(0, sep), StandardCharsets.UTF_8) else userInfo.takeIf { it.isNotEmpty() }
            val pass = if (sep >= 0) URLDecoder.decode(userInfo.substring(sep + 1), StandardCharsets.UTF_8) else null
            val host = uri.host ?: throw IllegalArgumentException("Нет хоста в DATABASE_URL: $raw")
            val port = if (uri.port > 0) uri.port else 5432
            val database = uri.path?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: "shigusdream"
            val existing = uri.query ?: ""
            val sslMode = System.getenv("SHIGU_DB_SSLMODE") ?: "require"
            val params = when {
                existing.contains("sslmode") || existing.contains("ssl=") -> existing
                existing.isEmpty() -> "sslmode=$sslMode"
                else -> "$existing&sslmode=$sslMode"
            }
            return Triple("jdbc:postgresql://$host:$port/$database?$params", user, pass)
        }
    }
}
