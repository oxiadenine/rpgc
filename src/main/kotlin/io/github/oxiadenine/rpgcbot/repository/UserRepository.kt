package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.UserTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

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
        UserTable.selectAll().map { record ->
            User(record[UserTable.id], record[UserTable.name])
        }
    }
}