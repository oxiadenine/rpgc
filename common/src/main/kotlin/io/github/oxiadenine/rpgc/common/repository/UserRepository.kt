package io.github.oxiadenine.rpgc.common.repository

import io.github.oxiadenine.rpgc.common.Database
import io.github.oxiadenine.rpgc.common.UserTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class User(val id: Long, val name: String, val role: Role = Role.NORMAL, val language: String = "") {
    enum class Role { ADMIN, EDITOR, NORMAL }
}

class UserRepository(private val database: Database) {
    suspend fun create(user: User) = database.transaction {
        UserTable.insert { statement ->
            statement[id] = user.id
            statement[name] = user.name
            statement[role] = user.role
            statement[language] = user.language
        }

        Unit
    }

    suspend fun read() = database.transaction {
        UserTable.selectAll().map { record ->
            User(record[UserTable.id], record[UserTable.name], record[UserTable.role], record[UserTable.language])
        }
    }

    suspend fun read(id: Long) = database.transaction {
        UserTable.selectAll().where { UserTable.id eq id }.firstOrNull()?.let { record ->
            User(record[UserTable.id], record[UserTable.name], record[UserTable.role], record[UserTable.language])
        }
    }

    suspend fun update(user: User) = database.transaction {
        UserTable.update({ UserTable.id eq user.id }) { statement ->
            statement[name] = user.name
            statement[language] = user.language
        }

        Unit
    }

    suspend fun delete(id: Long) = database.transaction {
        UserTable.deleteWhere { UserTable.id eq id }

        Unit
    }
}