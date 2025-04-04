package io.github.oxiadenine.rpgc.api

import io.github.oxiadenine.rpgc.api.route.userRoute
import io.github.oxiadenine.rpgc.common.repository.UserRepository
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*

fun Application.api(userRepository: UserRepository) {
    install(ContentNegotiation) { json() }

    routing {
        userRoute(userRepository)
    }
}