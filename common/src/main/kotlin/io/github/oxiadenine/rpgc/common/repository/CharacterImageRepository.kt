package io.github.oxiadenine.rpgc.common.repository

import io.github.oxiadenine.rpgc.common.CharacterImageTable
import io.github.oxiadenine.rpgc.common.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.util.UUID

class CharacterImage(
    val name: String = "",
    val bytes: ByteArray = byteArrayOf(),
    val type: String = "png",
    val characterId: UUID
) {
    var id: String = ""
}

class CharacterImageRepository(private val database: Database) {
    suspend fun create(characterImage: CharacterImage) = database.transaction {
        CharacterImageTable.insert { statement ->
            statement[id] = characterImage.id
            statement[name] = characterImage.name
            statement[binary] = ExposedBlob(characterImage.bytes)
            statement[type] = characterImage.type
            statement[characterId] = characterImage.characterId
        }

        Unit
    }

    suspend fun read(characterId: UUID) = database.transaction {
        CharacterImageTable.selectAll().where {
            CharacterImageTable.characterId eq characterId
        }.firstOrNull()?.let { record ->
            CharacterImage(
                record[CharacterImageTable.name],
                record[CharacterImageTable.binary].bytes,
                record[CharacterImageTable.type],
                record[CharacterImageTable.characterId]
            ).apply { id = record[CharacterImageTable.id] }
        }
    }

    suspend fun delete(id: String) = database.transaction {
        CharacterImageTable.deleteWhere { CharacterImageTable.id eq id }

        Unit
    }
}