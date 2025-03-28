package io.github.oxiadenine.rpgc.repository

import io.github.oxiadenine.rpgc.CharacterTable
import io.github.oxiadenine.rpgc.Database
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

    class Content(content: String? = null, val imageFilePath: String? = null) {
        class BlankError : Error()
        class LengthError : Error()

        val value = content?.let {
            if (content.isBlank()) {
                throw BlankError()
            }

            if (content.length > 64000) {
                throw LengthError()
            }

            content
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