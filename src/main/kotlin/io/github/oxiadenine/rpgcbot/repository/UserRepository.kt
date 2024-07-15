package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.UserTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class User(val id: Long, val name: String, val role: Role = Role.EDITOR) {
    enum class Role { ADMIN, EDITOR }

    class UnauthorizedError : Error()
}

class UserRepository(private val database: Database) {
    suspend fun create(user: User) = database.transaction {
        UserTable.insert { statement ->
            statement[id] = user.id
            statement[name] = user.name
            statement[role] = user.role
        }

        Unit
    }

    suspend fun read(id: Long) = database.transaction {
        UserTable.selectAll().where { UserTable.id eq id }.firstOrNull()?.let { record ->
            User(record[UserTable.id], record[UserTable.name], record[UserTable.role])
        }
    }

    suspend fun update(user: User) = database.transaction {
        UserTable.update({ UserTable.id eq user.id }) { statement -> statement[name] = user.name }

        Unit
    }
}