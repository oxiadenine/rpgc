package io.github.oxiadenine.rpgc.repository

import io.github.oxiadenine.rpgc.Database
import io.github.oxiadenine.rpgc.GameTable
import io.github.oxiadenine.rpgc.normalize
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

fun Game.Name.toCommandName() = this.value
    .normalize()
    .replace("[^a-zA-Z0-9 ]".toRegex(), "")
    .split(" ")
    .joinToString("") { it[0].lowercase() }

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