package io.github.oxiadenine.rpgc.api

import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgc.common.Database
import io.github.oxiadenine.rpgc.common.repository.UserRepository
import io.ktor.server.cio.*
import io.ktor.server.config.*
import io.ktor.server.engine.*

fun main() {
    val appConfig = ConfigFactory.load()

    val database = Database.create(appConfig.getConfig("database"))

    val userRepository = UserRepository(database)

    embeddedServer(
        factory = CIO,
        environment = applicationEnvironment { config = HoconApplicationConfig(appConfig) },
        configure = {
            connector {
                host = appConfig.getString("server.host")
                port = appConfig.getInt("server.port")
            }
        },
        module = { api(userRepository) }
    ).start(wait = true)
}