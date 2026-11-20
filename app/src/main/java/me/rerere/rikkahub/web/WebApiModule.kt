package me.rerere.rikkahub.web

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.WebAuthTokenRequest
import me.rerere.rikkahub.web.dto.WebAuthTokenResponse
import me.rerere.rikkahub.web.routes.aiIconRoutes
import me.rerere.rikkahub.web.routes.assetsRoutes
import me.rerere.rikkahub.web.routes.conversationRoutes
import me.rerere.rikkahub.web.routes.eventsRoutes
import me.rerere.rikkahub.web.routes.filesRoutes
import me.rerere.rikkahub.web.routes.folderRoutes
import me.rerere.rikkahub.web.routes.settingsRoutes
import java.security.MessageDigest
import java.util.Date

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, folderRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager,
) {
    install(ContentNegotiation) {
        json(JsonInstant)
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    val webAccess = createRouteScopedPlugin("WebAccess") {
        onCall { call ->
            call.webAccessError(settingsStore)?.let { throw it }
        }
    }

    routing {
        route("/api") {
            post("/auth/token") {
                val access = settingsStore.readWebAccess()
                if (!access.enabled) {
                    throw BadRequestException("JWT auth is disabled")
                }

                val accessPassword = access.password
                if (accessPassword.isBlank()) {
                    throw BadRequestException("Access password is not configured")
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, accessPassword)) {
                    throw UnauthorizedException("Invalid password")
                }

                val (token, expiresAt) = createWebJwt(accessPassword)
                call.respond(
                    HttpStatusCode.OK,
                    WebAuthTokenResponse(
                        token = token,
                        expiresAt = expiresAt
                    )
                )
            }

            aiIconRoutes(context)

            createChild(object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                    RouteSelectorEvaluation.Transparent
            }).apply {
                // Every protected request uses the current persisted policy, including while
                // an already running server switches from open access to JWT.
                install(webAccess)
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }
    }
}

internal suspend fun ApplicationCall.webAccessError(settingsStore: SettingsStore): ApiException? {
    val access = settingsStore.readWebAccess()
    if (!access.enabled) return null
    if (access.password.isBlank()) return ForbiddenException("Access password is not configured")
    val token = extractAccessToken(
        authorizationHeader = request.headers[HttpHeaders.Authorization],
        queryToken = request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY],
    ) ?: return UnauthorizedException("Unauthorized")
    return try {
        buildWebJwtVerifier(access.password).verify(token)
        null
    } catch (_: JWTVerificationException) {
        UnauthorizedException("Unauthorized")
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = JWT.create()
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return token to expiresAt
}

private fun buildWebJwtVerifier(secret: String): JWTVerifier {
    return JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .build()
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
