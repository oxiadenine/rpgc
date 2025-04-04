package io.github.oxiadenine.rpgc.common.repository

import io.github.oxiadenine.rpgc.common.CharacterTable
import io.github.oxiadenine.rpgc.common.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

class Character(
    val id: UUID = UUID.randomUUID(),
    val name: Name = Name(),
    val content: Content = Content(),
    val isRanking: Boolean = false,
    val game: Game? = null
) {
    sealed class NameException : Exception() {
        class Blank : NameException()
        class Length : NameException()
        class Invalid : NameException()
    }

    class Name(value: String? = null) {
        val value: String = value?.run {
            if (value.isBlank()) {
                throw NameException.Blank()
            }

            if (value.length > 64) {
                throw NameException.Length()
            }

            if (!value.matches("^(.+ ?)+$".toRegex())) {
                throw NameException.Invalid()
            }

            value
        } ?: ""
    }

    sealed class ContentException : Exception() {
        class Blank : ContentException()
        class Length : ContentException()
    }

    class Content(value: String? = null, val imageFilePath: String? = null) {
        val value = value?.run {
            if (value.isBlank()) {
                throw ContentException.Blank()
            }

            if (value.length > 64000) {
                throw ContentException.Length()
            }

            value
        } ?: ""
    }
}

class CharacterRepository(private val database: Database) {
    suspend fun create(character: Character) = database.transaction {
        CharacterTable.insert { statement ->
            statement[id] = character.id
            statement[name] = character.name.value
            statement[content] = character.content.value
            statement[isRanking] = character.isRanking
            statement[gameId] = character.game!!.id
        }

        Unit
    }

    suspend fun read() = database.transaction {
        CharacterTable.selectAll().map { record ->
            Character(
                record[CharacterTable.id],
                Character.Name(record[CharacterTable.name]),
                Character.Content(record[CharacterTable.content]),
                record[CharacterTable.isRanking],
                Game(record[CharacterTable.gameId])
            )
        }
    }

    suspend fun read(gameId: UUID) = database.transaction {
        CharacterTable.selectAll().where { CharacterTable.gameId eq gameId }.map { record ->
            Character(
                record[CharacterTable.id],
                Character.Name(record[CharacterTable.name]),
                Character.Content(record[CharacterTable.content]),
                record[CharacterTable.isRanking],
                Game(record[CharacterTable.gameId])
            )
        }
    }

    suspend fun update(character: Character) = database.transaction {
        CharacterTable.update({ CharacterTable.id eq character.id }) { statement ->
            statement[content] = character.content.value
        }

        Unit
    }
}