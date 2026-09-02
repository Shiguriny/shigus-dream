package com.shigusdream.backend.repository

import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val username: String,
    var role: String,
    var mcUuid: UUID?,
    val createdAt: Instant,
    val permissions: MutableSet<String> = mutableSetOf(),
) {
    val isAdmin: Boolean get() = role == "admin"
}

data class LinkCode(
    val code: String,
    val mcUuid: UUID,
    val mcName: String,
    var status: String, // pending | confirmed | expired
    var userId: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    val isExpired: Boolean get() = Instant.now().isAfter(expiresAt)
    val isPending: Boolean get() = status == "pending" && !isExpired
}

data class Command(
    val id: UUID,
    val requestId: String,
    val senderId: UUID,
    val targetId: UUID,
    val actionId: String,
    val payload: String, // JSON
    val mode: String, // immediate | queued
    var status: String, // pending | delivered | executed | failed | expired | cancelled
    var error: String? = null,
    val createdAt: Instant,
    val expiresAt: Instant?,
    var executedAt: Instant? = null,
)
