package com.shigusdream.backend.auth

import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.UserRepository
import java.security.SecureRandom
import java.util.UUID

/**
 * Одноразовые коды привязки Minecraft UUID к аккаунту backend.
 * Код показывается в клиенте, игрок вводит его на HTML-странице backend.
 * UUID используется как идентификатор, но не как секрет: секретом владения аккаунтом
 * выступает одноразовый код, переданный вне игровой сессии.
 */
class LinkCodeService(
    private val linkCodes: LinkCodeRepository,
    private val users: UserRepository,
    private val tokenService: TokenService,
) {
    private val random = SecureRandom()

    sealed interface ConfirmResult {
        data class Ok(val userId: UUID) : ConfirmResult
        data class Failed(val code: String, val message: String) : ConfirmResult
    }

    /** Создаёт одноразовый код для привязки UUID. */
    fun createCode(mcUuid: UUID, mcName: String): String {
        val code = generateCode()
        linkCodes.create(mcUuid, mcName, code, TokenService.LINK_CODE_TTL_SECONDS)
        return code
    }

    /**
     * Подтверждение кода на стороне web-страницы: связывает UUID с аккаунтом
     * (создаёт его при необходимости; самый первый аккаунт становится admin).
     * Возвращает id пользователя при успехе.
     */
    fun confirm(code: String, username: String): ConfirmResult {
        val link = linkCodes.byCode(code.trim().uppercase())
            ?: return ConfirmResult.Failed("unknown_link_code", "Код не найден")

        if (link.status == "confirmed") {
            return ConfirmResult.Failed("unknown_link_code", "Код уже использован")
        }
        if (link.isExpired) {
            link.status = "expired"
            linkCodes.save(link)
            return ConfirmResult.Failed("expired_link_code", "Код истёк, запросите новый в игре")
        }

        users.byMcUuid(link.mcUuid)?.let { existing ->
            return ConfirmResult.Failed(
                "uuid_already_linked",
                "Этот Minecraft UUID уже привязан к аккаунту '${existing.username}'",
            )
        }

        val user = users.byUsername(username) ?: run {
            val role = if (users.count() == 0L) "admin" else "user"
            users.create(username, role, null)
        }

        users.bindMcUuid(user, link.mcUuid)
        link.status = "confirmed"
        link.userId = user.id
        linkCodes.save(link)
        return ConfirmResult.Ok(user.id)
    }

    private fun generateCode(): String {
        // 6 символов без похожих символов (I, O, 0, 1).
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(6)
        repeat(6) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}
