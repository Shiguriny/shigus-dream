package com.shigusdream.backend

import com.shigusdream.backend.api.ErrorResponse
import com.shigusdream.backend.api.HttpRoutes
import com.shigusdream.backend.auth.LinkCodeService
import com.shigusdream.backend.auth.PermissionService
import com.shigusdream.backend.auth.TokenService
import com.shigusdream.backend.command.CommandService
import com.shigusdream.backend.config.AppConfig
import com.shigusdream.backend.repository.CommandRepository
import com.shigusdream.backend.repository.LinkCodeRepository
import com.shigusdream.backend.repository.UserRepository
import com.shigusdream.backend.repository.memory.InMemoryCommandRepository
import com.shigusdream.backend.repository.memory.InMemoryLinkCodeRepository
import com.shigusdream.backend.repository.memory.InMemoryUserRepository
import com.shigusdream.backend.repository.postgres.Db
import com.shigusdream.backend.repository.postgres.PostgresCommandRepository
import com.shigusdream.backend.repository.postgres.PostgresLinkCodeRepository
import com.shigusdream.backend.repository.postgres.PostgresUserRepository
import com.shigusdream.backend.websocket.ClientSession
import com.shigusdream.backend.websocket.WsManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import org.slf4j.LoggerFactory

class AppContext(
    val config: AppConfig,
    val users: UserRepository,
    val linkCodes: LinkCodeRepository,
    val commands: CommandRepository,
    val tokenService: TokenService,
    val permissions: PermissionService,
    val linkCodeService: LinkCodeService,
    val commandService: CommandService,
    val wsManager: WsManager,
    private val closable: AutoCloseable?,
) : AutoCloseable {
    override fun close() {
        closable?.close()
    }
}

fun buildContext(config: AppConfig): AppContext {
    val db: Db? = when (config.storage) {
        AppConfig.Storage.POSTGRES -> {
            val database = Db.create(config.jdbcUrl, config.dbUser, config.dbPassword)
            Db.initSchema(database)
            database
        }
        AppConfig.Storage.MEMORY -> null
    }

    val users: UserRepository = db?.let { PostgresUserRepository(it) } ?: InMemoryUserRepository()
    val linkCodes: LinkCodeRepository = db?.let { PostgresLinkCodeRepository(it) } ?: InMemoryLinkCodeRepository()
    val commands: CommandRepository = db?.let { PostgresCommandRepository(it) } ?: InMemoryCommandRepository()

    val tokenService = TokenService(config.jwtSecret)
    val pgUsers = users as? PostgresUserRepository
    val permissions = PermissionService { userId ->
        pgUsers?.permissionsOf(userId) ?: users.byId(userId)?.permissions ?: emptySet()
    }
    val linkCodeService = LinkCodeService(linkCodes, users, tokenService)
    val commandService = CommandService(users, commands, permissions)
    val wsManager = WsManager(users, linkCodes, tokenService, linkCodeService, permissions, commandService)

    return AppContext(config, users, linkCodes, commands, tokenService, permissions, linkCodeService, commandService, wsManager, db)
}

fun Application.module(ctx: AppContext) {
    val log = LoggerFactory.getLogger("com.shigusdream.backend")

    install(WebSockets)
    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("unhandled error on {}", call.request.local.uri, cause)
            call.respondText(
                """{"error":"internal_error","message":"Внутренняя ошибка"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        HttpRoutes(
            ctx.users, ctx.linkCodes, ctx.tokenService, ctx.permissions,
            ctx.linkCodeService, ctx.commandService, ctx.wsManager,
        ).register(this)

        webSocket("/ws") {
            ctx.wsManager.handleConnection(ClientSession(this))
        }
    }
}

fun main() {
    val log = LoggerFactory.getLogger("com.shigusdream.backend")
    val config = AppConfig.fromEnv()
    val ctx = buildContext(config)

    log.info(
        "Shigu's Dream backend starting: port={} storage={}",
        config.port,
        if (config.storage == AppConfig.Storage.POSTGRES) "postgres" else "memory",
    )

    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(ctx)
    }
    Runtime.getRuntime().addShutdownHook(Thread { ctx.close() })
    server.start(wait = true)
}
