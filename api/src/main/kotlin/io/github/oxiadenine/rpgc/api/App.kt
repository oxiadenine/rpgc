package io.github.oxiadenine.rpgc.api

import io.github.oxiadenine.rpgc.api.route.Session
import io.github.oxiadenine.rpgc.api.route.authRoute
import io.github.oxiadenine.rpgc.api.route.userRoute
import io.github.oxiadenine.rpgc.api.security.SessionTransportTransformerCipher
import io.github.oxiadenine.rpgc.common.repository.UserRepository
import io.github.oxiadenine.rpgc.tools.security.PasswordEncoder
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.datetime.Clock

fun Application.api(userRepository: UserRepository) {
    val sessionKey = environment.config.property("api.auth.sessionKey").getString()
    val sessionTime = environment.config.property("api.auth.sessionTime").getString().toLong()

    val passwordEncoder = PasswordEncoder()

    install(ContentNegotiation) { json() }
    install(Sessions) {
        header<Session>("session", SessionStorageMemory()) {
            transform(SessionTransportTransformerCipher(sessionKey, passwordEncoder))
        }
    }
    install(Authentication) {
        session<Session> {
            validate { session ->
                if (Clock.System.now().epochSeconds < session.expiresAt) {
                    session
                } else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }

    routing {
        authRoute(sessionKey, sessionTime, passwordEncoder)

        authenticate {
            userRoute(userRepository)
        }
    }
}