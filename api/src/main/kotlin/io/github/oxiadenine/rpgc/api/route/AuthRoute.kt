package io.github.oxiadenine.rpgc.api.route

import io.github.oxiadenine.rpgc.tools.security.PasswordEncoder
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Session(val expiresAt: Long)

fun Route.authRoute(sessionKey: String, sessionTime: Long, passwordEncoder: PasswordEncoder) = route("/auth") {
    post("admin") {
        val body = call.receive<JsonObject>()

        body["password"]?.jsonPrimitive?.content?.let { password ->
            val (secretKey, salt) = sessionKey.split("$")

            if (secretKey == hex(passwordEncoder.hash(password, hex(salt)))) {
                val expiresAt = Clock.System.now().epochSeconds + sessionTime

                call.sessions.set("session", Session(expiresAt))

                call.respond(HttpStatusCode.OK)
            } else call.respond(HttpStatusCode.Forbidden)
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
}