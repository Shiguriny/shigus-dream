package com.shigusdream.backend.repository

import java.util.UUID

interface UserRepository {
    fun count(): Long
    fun create(username: String, role: String, mcUuid: UUID?): User
    fun byId(id: UUID): User?
    fun byUsername(username: String): User?
    fun byMcUuid(uuid: UUID): User?
    fun all(): List<User>
    fun bindMcUuid(user: User, uuid: UUID)
    fun setRole(user: User, role: String)
    fun addPermission(user: User, permission: String)
    fun removePermission(user: User, permission: String)
}

interface LinkCodeRepository {
    fun create(mcUuid: UUID, mcName: String, code: String, ttlSeconds: Long, isForce: Boolean = false): LinkCode
    fun byCode(code: String): LinkCode?
    fun save(link: LinkCode)
}

interface CommandRepository {
    /** Возвращает false, если команда с таким request_id уже существует (дедупликация). */
    fun insert(command: Command): Boolean
    fun byId(id: UUID): Command?
    fun byRequestId(requestId: String): Command?
    fun update(command: Command)
    fun pendingForTarget(targetId: UUID): List<Command>
}
