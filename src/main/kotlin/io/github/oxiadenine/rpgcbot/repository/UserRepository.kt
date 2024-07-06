package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.UserTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

data class User(val id: Long, val name: String)

class UserRepository(private val database: Database) {
    suspend fun create(user: User) = database.transaction {
        UserTable.insert { statement ->
            statement[id] = user.id
            statement[name] = user.name
        }

        Unit
    }

    suspend fun read() = database.transaction {
        UserTable.selectAll().map { record -> User(record[UserTable.id], record[UserTable.name]) }
    }

    suspend fun read(id: Long) = database.transaction {
        UserTable.selectAll().where { UserTable.id eq id }.firstOrNull()?.let { record ->
            User(record[UserTable.id], record[UserTable.name])
        }
    }

    suspend fun update(user: User) = database.transaction {
        UserTable.update({ UserTable.id eq user.id }) { statement -> statement[name] = user.name }

        Unit
    }
}