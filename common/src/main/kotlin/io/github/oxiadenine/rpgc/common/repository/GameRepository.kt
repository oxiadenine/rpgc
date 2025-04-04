package io.github.oxiadenine.rpgc.common.repository

import io.github.oxiadenine.rpgc.common.Database
import io.github.oxiadenine.rpgc.common.GameTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

class Game(val id: UUID = UUID.randomUUID(), val name: Name = Name()) {
    sealed class NameException : Exception() {
        class Blank : NameException()
        class Length : NameException()
        class Invalid : NameException()
    }

    class Name(name: String? = null) {
        val value: String = name?.run {
            if (name.isBlank()) {
                throw NameException.Blank()
            }

            if (name.length > 64) {
                throw NameException.Length()
            }

            if (!name.matches("^(.+ ?)+$".toRegex())) {
                throw NameException.Invalid()
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

    suspend fun delete(id: UUID) = database.transaction {
        GameTable.deleteWhere { GameTable.id eq id }

        Unit
    }
}