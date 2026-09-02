package com.shigusdream.backend.repository.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class Db(private val dataSource: HikariDataSource) : AutoCloseable {
    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { conn -> block(conn) }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun create(jdbcUrl: String, user: String?, password: String?): Db {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                maximumPoolSize = 5
                poolName = "shigu-pool"
                // Учётные данные задаются только если их нет внутри jdbcUrl (формат DATABASE_URL).
                if (user != null) this.username = user
                if (password != null) this.password = password
            }
            return Db(HikariDataSource(config))
        }

        /**
         * Автосоздание схемы на внешнем/локальном PostgreSQL (идемпотентно: CREATE TABLE IF NOT EXISTS).
         * Позволяет подключаться к управляемым БД (Neon/Render/Supabase) без ручного применения init.sql.
         */
        fun initSchema(db: Db) {
            val sql = Db::class.java.classLoader.getResourceAsStream("schema.sql")
                ?.bufferedReader(Charsets.UTF_8)?.readText()
                ?: throw IllegalStateException("schema.sql не найден в ресурсах")
            db.withConnection { conn ->
                conn.createStatement().use { st -> st.execute(sql) }
            }
        }
    }
}

fun ResultSet.uuid(name: String): UUID? {
    val value = getString(name) ?: return null
    return UUID.fromString(value)
}

fun ResultSet.instant(name: String): Instant? {
    val value = getTimestamp(name) ?: return null
    return value.toInstant()
}

fun ResultSet.instantOrNull(name: String): Instant? = instant(name)
