package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.GameTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

data class Game(
    val key: String,
    val name: String,
    var characterPages: List<CharacterPage> = emptyList()
)

class GameRepository(private val database: Database) {
    suspend fun create(game: Game) = database.transaction {
        GameTable.insert { statement ->
            statement[key] = game.key
            statement[name] = game.name
        }

        Unit
    }

    suspend fun read() = database.transaction {
        GameTable.selectAll().map { record ->
            Game(record[GameTable.key], record[GameTable.name])
        }
    }

    suspend fun read(key: String) = database.transaction {
        GameTable.selectAll().where { GameTable.key eq key }.firstOrNull()?.let { record ->
            Game(record[GameTable.key], record[GameTable.name])
        }
    }

    suspend fun update(game: Game) = database.transaction {
        GameTable.update({ GameTable.key eq game.key }) { statement ->
            statement[name] = game.name
        }

        Unit
    }
}