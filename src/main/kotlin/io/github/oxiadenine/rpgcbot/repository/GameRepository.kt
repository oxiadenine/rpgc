package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.GameTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.text.Normalizer

class Game(val key: String = "", val name: Name = Name()) {
    class ExistsError : Error()

    class Name(name: String? = null) {
        class BlankError : Error()
        class LengthError : Error()
        class InvalidError : Error()

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

        fun normalize() = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace("\\p{M}".toRegex(), "")
            .replace("[^a-zA-Z0-9 ]".toRegex(), "")
    }

    var characterPages: List<CharacterPage> = emptyList()
}

class GameRepository(private val database: Database) {
    suspend fun create(game: Game) = database.transaction {
        GameTable.insert { statement ->
            statement[key] = game.key
            statement[name] = game.name.value
        }

        Unit
    }

    suspend fun read() = database.transaction {
        GameTable.selectAll().map { record ->
            Game(record[GameTable.key], Game.Name(record[GameTable.name]))
        }
    }

    suspend fun read(key: String) = database.transaction {
        GameTable.selectAll().where { GameTable.key eq key }.firstOrNull()?.let { record ->
            Game(record[GameTable.key], Game.Name(record[GameTable.name]))
        }
    }

    suspend fun update(game: Game) = database.transaction {
        GameTable.update({ GameTable.key eq game.key }) { statement ->
            statement[name] = game.name.value
        }

        Unit
    }

    suspend fun delete(key: String) = database.transaction {
        GameTable.deleteWhere { GameTable.key eq key }

        Unit
    }
}