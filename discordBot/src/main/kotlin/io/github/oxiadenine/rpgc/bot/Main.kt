package io.github.oxiadenine.rpgc.bot

import com.typesafe.config.ConfigFactory
import io.github.oxiadenine.rpgc.common.Database
import io.github.oxiadenine.rpgc.common.repository.*

fun main() {
    val appConfig = ConfigFactory.load()

    val database = Database.create(appConfig.getConfig("database"))

    val gameRepository = GameRepository(database)
    val characterRepository = CharacterRepository(database)
    val characterImageRepository = CharacterImageRepository(database)

    Application.create(appConfig) {
        bot(gameRepository, characterRepository, characterImageRepository)
    }
}