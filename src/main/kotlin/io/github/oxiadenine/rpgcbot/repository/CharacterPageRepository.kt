package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.CharacterPageTable
import io.github.oxiadenine.rpgcbot.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.update

data class CharacterPageEntity(
    val path: String,
    val title: String,
    val content: String,
    val url: String,
    val isRanking: Boolean,
    val image: ByteArray? = null
)

class CharacterPageRepository(private val database: Database) {
    suspend fun create(characterPageEntity: CharacterPageEntity) = database.transaction {
        CharacterPageTable.insert { statement ->
            statement[path] = characterPageEntity.path
            statement[title] = characterPageEntity.title
            statement[content] = characterPageEntity.content
            statement[url] = characterPageEntity.url
            statement[isRanking] = characterPageEntity.isRanking
            if (characterPageEntity.image != null) {
                statement[image] = ExposedBlob(characterPageEntity.image)
            }
        }

        Unit
    }

    suspend fun read() = database.transaction {
        CharacterPageTable.selectAll().map { record ->
            CharacterPageEntity(
                record[CharacterPageTable.path],
                record[CharacterPageTable.title],
                record[CharacterPageTable.content],
                record[CharacterPageTable.url],
                record[CharacterPageTable.isRanking]
            )
        }
    }

    suspend fun update(characterPageEntity: CharacterPageEntity) = database.transaction {
        CharacterPageTable.update({ CharacterPageTable.path eq characterPageEntity.path }) { statement ->
            statement[content] = characterPageEntity.content
        }

        Unit
    }
}