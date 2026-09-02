package com.shigusdream.backend.repository.postgres

import com.shigusdream.backend.repository.Command
import com.shigusdream.backend.repository.CommandRepository
import com.shigusdream.backend.repository.LinkCode
import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.User
import com.shigusdream.backend.repository.UserRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PostgresUserRepository(private val db: Db) : UserRepository {

    override fun count(): Long = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM users").use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    override fun create(username: String, role: String, mcUuid: UUID?): User {
        val id = UUID.randomUUID()
        return db.withConnection { conn ->
            conn.prepareStatement("INSERT INTO users (id, username, role, mc_uuid) VALUES (?, ?, ?, ?)").use { st ->
                st.setObject(1, id)
                st.setString(2, username)
                st.setString(3, role)
                if (mcUuid == null) st.setNull(4, java.sql.Types.OTHER) else st.setObject(4, mcUuid)
                st.executeUpdate()
            }
            User(id, username, role, mcUuid, Instant.now())
        }
    }

    private fun userFrom(rs: ResultSet): User {
        val permissions = mutableSetOf<String>()
        val id = rs.uuid("id")!!
        // Права загружаются отдельным запросом в permissionsOf; здесь только базовые поля.
        return User(
            id = id,
            username = rs.getString("username"),
            role = rs.getString("role"),
            mcUuid = rs.uuid("mc_uuid"),
            createdAt = rs.instant("created_at") ?: Instant.now(),
            permissions = permissions,
        )
    }

    private fun queryOne(sql: String, bind: (java.sql.PreparedStatement) -> Unit): User? =
        db.withConnection { conn ->
            conn.prepareStatement(sql).use { st ->
                bind(st)
                st.executeQuery().use { rs -> if (rs.next()) userFrom(rs) else null }
            }
        }

    override fun byId(id: UUID): User? =
        queryOne("SELECT id, username, role, mc_uuid, created_at FROM users WHERE id = ?") {
            it.setObject(1, id)
        }

    override fun byUsername(username: String): User? =
        queryOne("SELECT id, username, role, mc_uuid, created_at FROM users WHERE username = ?") {
            it.setString(1, username)
        }

    override fun byMcUuid(uuid: UUID): User? =
        queryOne("SELECT id, username, role, mc_uuid, created_at FROM users WHERE mc_uuid = ?") {
            it.setObject(1, uuid)
        }

    override fun all(): List<User> = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, username, role, mc_uuid, created_at FROM users ORDER BY created_at").use { st ->
            st.executeQuery().use { rs ->
                val result = mutableListOf<User>()
                while (rs.next()) result += userFrom(rs)
                result
            }
        }
    }

    override fun bindMcUuid(user: User, uuid: UUID) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE users SET mc_uuid = ? WHERE id = ?").use { st ->
                st.setObject(1, uuid)
                st.setObject(2, user.id)
                st.executeUpdate()
            }
        }
        user.mcUuid = uuid
    }

    override fun setRole(user: User, role: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE users SET role = ? WHERE id = ?").use { st ->
                st.setString(1, role)
                st.setObject(2, user.id)
                st.executeUpdate()
            }
        }
        user.role = role
    }

    override fun addPermission(user: User, permission: String) {
        db.withConnection { conn ->
            conn.prepareStatement("INSERT INTO user_permissions (user_id, permission) VALUES (?, ?) ON CONFLICT DO NOTHING").use { st ->
                st.setObject(1, user.id)
                st.setString(2, permission)
                st.executeUpdate()
            }
        }
        user.permissions.add(permission)
    }

    override fun removePermission(user: User, permission: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM user_permissions WHERE user_id = ? AND permission = ?").use { st ->
                st.setObject(1, user.id)
                st.setString(2, permission)
                st.executeUpdate()
            }
        }
        user.permissions.remove(permission)
    }

    fun permissionsOf(userId: UUID): Set<String> = db.withConnection { conn ->
        conn.prepareStatement("SELECT permission FROM user_permissions WHERE user_id = ?").use { st ->
            st.setObject(1, userId)
            st.executeQuery().use { rs ->
                val result = mutableSetOf<String>()
                while (rs.next()) result += rs.getString(1)
                result
            }
        }
    }
}

class PostgresLinkCodeRepository(private val db: Db) : LinkCodeRepository {

    override fun create(mcUuid: UUID, mcName: String, code: String, ttlSeconds: Long, isForce: Boolean): LinkCode {
        val link = LinkCode(
            code = code,
            mcUuid = mcUuid,
            mcName = mcName,
            status = "pending",
            userId = null,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(ttlSeconds),
            isForce = isForce,
        )
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO link_codes (code, mc_uuid, mc_name, status, is_force, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, link.code)
                st.setObject(2, link.mcUuid)
                st.setString(3, link.mcName)
                st.setString(4, link.status)
                st.setBoolean(5, link.isForce)
                st.setTimestamp(6, Timestamp.from(link.createdAt))
                st.setTimestamp(7, Timestamp.from(link.expiresAt))
                st.executeUpdate()
            }
        }
        return link
    }

    override fun byCode(code: String): LinkCode? = db.withConnection { conn ->
        conn.prepareStatement("SELECT code, mc_uuid, mc_name, status, user_id, is_force, created_at, expires_at FROM link_codes WHERE code = ?").use { st ->
            st.setString(1, code)
            st.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                LinkCode(
                    code = rs.getString("code"),
                    mcUuid = rs.uuid("mc_uuid")!!,
                    mcName = rs.getString("mc_name"),
                    status = rs.getString("status"),
                    userId = rs.uuid("user_id"),
                    createdAt = rs.instant("created_at") ?: Instant.now(),
                    expiresAt = rs.instant("expires_at") ?: Instant.now(),
                    isForce = rs.getBoolean("is_force"),
                )
            }
        }
    }

    override fun save(link: LinkCode) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE link_codes SET status = ?, user_id = ? WHERE code = ?").use { st ->
                st.setString(1, link.status)
                if (link.userId == null) st.setNull(2, java.sql.Types.OTHER) else st.setObject(2, link.userId)
                st.setString(3, link.code)
                st.executeUpdate()
            }
        }
    }
}

class PostgresCommandRepository(private val db: Db) : CommandRepository {

    override fun insert(command: Command): Boolean {
        if (byRequestId(command.requestId) != null) return false
        return db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO commands (id, request_id, sender_id, target_id, action_id, payload, mode, status, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
            ).use { st ->
                st.setObject(1, command.id)
                st.setString(2, command.requestId)
                st.setObject(3, command.senderId)
                st.setObject(4, command.targetId)
                st.setString(5, command.actionId)
                st.setString(6, command.payload)
                st.setString(7, command.mode)
                st.setString(8, command.status)
                st.setTimestamp(9, Timestamp.from(command.createdAt))
                if (command.expiresAt == null) st.setNull(10, java.sql.Types.TIMESTAMP) else st.setTimestamp(10, Timestamp.from(command.expiresAt))
                st.executeUpdate() > 0
            }
        }
    }

    private fun commandFrom(rs: ResultSet): Command = Command(
        id = rs.uuid("id")!!,
        requestId = rs.getString("request_id"),
        senderId = rs.uuid("sender_id")!!,
        targetId = rs.uuid("target_id")!!,
        actionId = rs.getString("action_id"),
        payload = rs.getString("payload"),
        mode = rs.getString("mode"),
        status = rs.getString("status"),
        error = rs.getString("error"),
        createdAt = rs.instant("created_at") ?: Instant.now(),
        expiresAt = rs.instant("expires_at"),
        executedAt = rs.instant("executed_at"),
    )

    override fun byId(id: UUID): Command? = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, request_id, sender_id, target_id, action_id, payload::text AS payload, mode, status, error, created_at, expires_at, executed_at FROM commands WHERE id = ?").use { st ->
            st.setObject(1, id)
            st.executeQuery().use { rs -> if (rs.next()) commandFrom(rs) else null }
        }
    }

    override fun byRequestId(requestId: String): Command? = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, request_id, sender_id, target_id, action_id, payload::text AS payload, mode, status, error, created_at, expires_at, executed_at FROM commands WHERE request_id = ?").use { st ->
            st.setString(1, requestId)
            st.executeQuery().use { rs -> if (rs.next()) commandFrom(rs) else null }
        }
    }

    override fun update(command: Command) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE commands SET status = ?, error = ?, executed_at = ? WHERE id = ?").use { st ->
                st.setString(1, command.status)
                st.setString(2, command.error)
                if (command.executedAt == null) st.setNull(3, java.sql.Types.TIMESTAMP) else st.setTimestamp(3, Timestamp.from(command.executedAt))
                st.setObject(4, command.id)
                st.executeUpdate()
            }
        }
    }

    override fun pendingForTarget(targetId: UUID): List<Command> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT id, request_id, sender_id, target_id, action_id, payload::text AS payload, mode, status, error, created_at, expires_at, executed_at " +
                "FROM commands WHERE target_id = ? AND status = 'pending' AND mode = 'queued' ORDER BY created_at",
        ).use { st ->
            st.setObject(1, targetId)
            st.executeQuery().use { rs ->
                val result = mutableListOf<Command>()
                while (rs.next()) result += commandFrom(rs)
                result
            }
        }
    }
}
