package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.CharacterPageTable
import io.github.oxiadenine.rpgcbot.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.update

class CharacterPage(
    val path: String = "",
    val title: Title = Title(),
    val content: Content = Content(),
    val url: String = "",
    val isRanking: Boolean = false,
    val image: ByteArray? = null,
    val gameKey: String = ""
) {
    enum class Paths { RANKING }

    class Title(title: String? = null) {
        class BlankError : Error()
        class LengthError : Error()
        class InvalidError : Error()
        class ExistsError : Error()

        val value: String = title?.let {
            if (title.isBlank()) {
                throw BlankError()
            }

            if (title.length > 64) {
                throw LengthError()
            }

            if (!title.matches("^([A-Za-zÀ-ÖØ-öø-ÿ0-9.]+\\s?)+$".toRegex())) {
                throw InvalidError()
            }

            title
        } ?: ""
    }

    class Content(content: String? = null) {
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

    data class Image(val src: String, val bytes: ByteArray)
}

class CharacterPageRepository(private val database: Database) {
    suspend fun create(characterPage: CharacterPage) = database.transaction {
        CharacterPageTable.insert { statement ->
            statement[path] = characterPage.path
            statement[title] = characterPage.title.value
            statement[content] = characterPage.content.value
            statement[url] = characterPage.url
            statement[isRanking] = characterPage.isRanking
            if (characterPage.image != null) {
                statement[image] = ExposedBlob(characterPage.image)
            }
            statement[gameKey] = characterPage.gameKey
        }

        Unit
    }

    suspend fun read() = database.transaction {
        CharacterPageTable.selectAll().map { record ->
            CharacterPage(
                record[CharacterPageTable.path],
                CharacterPage.Title(record[CharacterPageTable.title]),
                CharacterPage.Content(record[CharacterPageTable.content]),
                record[CharacterPageTable.url],
                record[CharacterPageTable.isRanking],
                record[CharacterPageTable.image]?.bytes,
                record[CharacterPageTable.gameKey]
            )
        }
    }

    suspend fun read(game: Game) = database.transaction {
        CharacterPageTable.selectAll().where { CharacterPageTable.gameKey eq game.key }.map { record ->
            CharacterPage(
                record[CharacterPageTable.path],
                CharacterPage.Title(record[CharacterPageTable.title]),
                CharacterPage.Content(record[CharacterPageTable.content]),
                record[CharacterPageTable.url],
                record[CharacterPageTable.isRanking],
                record[CharacterPageTable.image]?.bytes,
                record[CharacterPageTable.gameKey]
            )
        }
    }

    suspend fun read(path: String) = database.transaction {
        CharacterPageTable.selectAll().where { CharacterPageTable.path eq path }.firstOrNull()?.let { record ->
            CharacterPage(
                record[CharacterPageTable.path],
                CharacterPage.Title(record[CharacterPageTable.title]),
                CharacterPage.Content(record[CharacterPageTable.content]),
                record[CharacterPageTable.url],
                record[CharacterPageTable.isRanking],
                record[CharacterPageTable.image]?.bytes,
                record[CharacterPageTable.gameKey]
            )
        }
    }

    suspend fun update(characterPage: CharacterPage) = database.transaction {
        CharacterPageTable.update({ CharacterPageTable.path eq characterPage.path }) { statement ->
            statement[content] = characterPage.content.value
        }

        Unit
    }
}