package io.github.oxiadenine.rpgc

import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgc.repository.*
import io.ktor.server.config.*
import io.ktor.server.engine.*
import java.text.Normalizer

fun main() {
    val appConfig = HoconApplicationConfig(ConfigFactory.load())

    val database = Database.create(appConfig.config("database"))

    val userRepository = UserRepository(database)
    val gameRepository = GameRepository(database)
    val userGameSubscriptionRepository = UserGameSubscriptionRepository(database)
    val characterRepository = CharacterRepository(database)
    val characterImageRepository = CharacterImageRepository(database)

    embeddedServer(
        factory = io.ktor.server.cio.CIO,
        environment = applicationEnvironment { config = appConfig },
        configure = {
            connector {
                host = appConfig.property("server.host").getString()
                port = appConfig.property("server.port").getString().toInt()
            }
        },
        module = {
            bot(
                userRepository,
                gameRepository,
                userGameSubscriptionRepository,
                characterRepository,
                characterImageRepository
            )
            api(userRepository)
        }
    ).start(wait = true)
}

fun String.normalize() = Normalizer.normalize(this, Normalizer.Form.NFKD).replace("\\p{M}".toRegex(), "")