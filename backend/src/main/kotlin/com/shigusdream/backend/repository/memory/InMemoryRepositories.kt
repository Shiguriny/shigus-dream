package com.shigusdream.backend.repository.memory

import com.shigusdream.backend.repository.Command
import com.shigusdream.backend.repository.CommandRepository
import com.shigusdream.backend.repository.LinkCode
import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.User
import com.shigusdream.backend.repository.UserRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryUserRepository : UserRepository {
    private val byId = ConcurrentHashMap<UUID, User>()
    private val byUsername = ConcurrentHashMap<String, UUID>()
    private val byMcUuid = ConcurrentHashMap<UUID, UUID>()

    override fun count(): Long = byId.size.toLong()

    override fun create(username: String, role: String, mcUuid: UUID?): User {
        val user = User(UUID.randomUUID(), username, role, mcUuid, Instant.now())
        byId[user.id] = user
        byUsername[username] = user.id
        if (mcUuid != null) byMcUuid[mcUuid] = user.id
        return user
    }

    override fun byId(id: UUID): User? = byId[id]

    override fun byUsername(username: String): User? =
        byUsername[username]?.let { byId[it] }

    override fun byMcUuid(uuid: UUID): User? =
        byMcUuid[uuid]?.let { byId[it] }

    override fun all(): List<User> = byId.values.sortedBy { it.createdAt }

    override fun bindMcUuid(user: User, uuid: UUID) {
        user.mcUuid = uuid
        byId[user.id] = user
        byMcUuid[uuid] = user.id
    }

    override fun setRole(user: User, role: String) {
        user.role = role
        byId[user.id] = user
    }

    override fun addPermission(user: User, permission: String) {
        user.permissions.add(permission)
    }

    override fun removePermission(user: User, permission: String) {
        user.permissions.remove(permission)
    }
}

class InMemoryLinkCodeRepository : LinkCodeRepository {
    private val byCode = ConcurrentHashMap<String, LinkCode>()

    override fun create(mcUuid: UUID, mcName: String, code: String, ttlSeconds: Long): LinkCode {
        val link = LinkCode(
            code = code,
            mcUuid = mcUuid,
            mcName = mcName,
            status = "pending",
            userId = null,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(ttlSeconds),
        )
        byCode[code] = link
        purgeExpired()
        return link
    }

    override fun byCode(code: String): LinkCode? = byCode[code]

    override fun save(link: LinkCode) {
        byCode[link.code] = link
    }

    private fun purgeExpired() {
        byCode.values.removeIf { it.isExpired && it.status != "confirmed" }
    }
}

class InMemoryCommandRepository : CommandRepository {
    private val byId = ConcurrentHashMap<UUID, Command>()
    private val byRequestId = ConcurrentHashMap<String, UUID>()

    override fun insert(command: Command): Boolean {
        if (byRequestId.putIfAbsent(command.requestId, command.id) != null) return false
        byId[command.id] = command
        return true
    }

    override fun byId(id: UUID): Command? = byId[id]

    override fun byRequestId(requestId: String): Command? =
        byRequestId[requestId]?.let { byId[it] }

    override fun update(command: Command) {
        byId[command.id] = command
    }

    override fun pendingForTarget(targetId: UUID): List<Command> =
        byId.values
            .filter { it.targetId == targetId && it.status == "pending" && it.mode == "queued" }
            .sortedBy { it.createdAt }
}
