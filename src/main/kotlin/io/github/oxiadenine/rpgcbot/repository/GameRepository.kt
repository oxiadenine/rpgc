package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.GameTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

class Game(val id: UUID = UUID.randomUUID(), val name: Name = Name()) {
    class Name(name: String? = null) {
        class BlankError : Error()
        class LengthError : Error()
        class InvalidError : Error()
        class ExistsError : Error()

        val value: String = name?.let {
            if (name.isBlank()) {
                throw BlankError()
            }

            if (name.length > 64) {
                throw LengthError()
            }

            if (!name.matches("^(.+ ?)+$".toRegex())) {
                throw InvalidError()
            }

            name
        } ?: ""
    }

    var characters = emptyList<Character>()
}

class GameRepository(private val database: Database) {
    suspend fun create(game: Game) = database.transaction {
        GameTable.insert { statement ->
            statement[id] = game.id
            statement[name] = game.name.value
        }

        Unit
    }

    suspend fun read() = database.transaction {
        GameTable.selectAll().map { record ->
            Game(record[GameTable.id], Game.Name(record[GameTable.name]))
        }
    }

    suspend fun read(id: UUID) = database.transaction {
        GameTable.selectAll().where { GameTable.id eq id }.firstOrNull()?.let { record ->
            Game(record[GameTable.id], Game.Name(record[GameTable.name]))
        }
    }

    suspend fun update(game: Game) = database.transaction {
        GameTable.update({ GameTable.id eq game.id }) { statement ->
            statement[name] = game.name.value
        }

        Unit
    }

    suspend fun delete(id: UUID) = database.transaction {
        GameTable.deleteWhere { GameTable.id eq id }

        Unit
    }
}