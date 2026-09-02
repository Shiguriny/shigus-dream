package com.shigusdream.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.shigusdream.backend.repository.User
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Access token: короткоживущий (15 минут). Refresh token: долгоживущий (30 дней).
 * Minecraft UUID используется как идентификатор, но не как секрет — доступ только по JWT.
 */
class TokenService(secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)

    fun issueAccessToken(user: User): String =
        JWT.create()
            .withSubject(user.id.toString())
            .withClaim("typ", "access")
            .withClaim("username", user.username)
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(ACCESS_TTL)))
            .sign(algorithm)

    fun issueRefreshToken(user: User): String =
        JWT.create()
            .withSubject(user.id.toString())
            .withClaim("typ", "refresh")
            .withClaim("username", user.username)
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(REFRESH_TTL)))
            .sign(algorithm)

    /** Проверяет подпись, срок действия и тип токена. Возвращает userId или null. */
    fun validate(token: String, expectedType: String): UUID? = try {
        val decoded = JWT.require(algorithm).build().verify(token)
        if (decoded.getClaim("typ").asString() != expectedType) {
            null
        } else {
            decoded.subject.let { UUID.fromString(it) }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        val ACCESS_TTL: Duration = Duration.ofMinutes(15)
        val REFRESH_TTL: Duration = Duration.ofDays(30)
        const val LINK_CODE_TTL_SECONDS: Long = 15 * 60
    }
}
