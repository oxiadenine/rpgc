package io.github.oxiadenine.rpgc.repository

import io.github.oxiadenine.rpgc.Database
import io.github.oxiadenine.rpgc.UserGameSubscriptionTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

data class UserGameSubscription(val userId: Long, val gameId: UUID)

class UserGameSubscriptionRepository(private val database: Database) {
    suspend fun create(userGameSubscription: UserGameSubscription) = database.transaction {
        UserGameSubscriptionTable.insert { statement ->
            statement[userId] = userGameSubscription.userId
            statement[gameId] = userGameSubscription.gameId
        }

        Unit
    }

    suspend fun read() = database.transaction {
        UserGameSubscriptionTable.selectAll().map { record ->
            UserGameSubscription(record[UserGameSubscriptionTable.userId], record[UserGameSubscriptionTable.gameId])
        }
    }

    suspend fun read(gameId: UUID) = database.transaction {
        UserGameSubscriptionTable.selectAll().where { UserGameSubscriptionTable.gameId eq gameId }.map { record ->
            UserGameSubscription(record[UserGameSubscriptionTable.userId], record[UserGameSubscriptionTable.gameId])
        }
    }

    suspend fun read(userId: Long, gameId: UUID) = database.transaction {
        UserGameSubscriptionTable.selectAll().where {
            (UserGameSubscriptionTable.userId eq userId) and (UserGameSubscriptionTable.gameId eq gameId)
        }.firstOrNull()?.let { record ->
            UserGameSubscription(record[UserGameSubscriptionTable.userId], record[UserGameSubscriptionTable.gameId])
        }
    }

    suspend fun delete(userId: Long, gameId: UUID) = database.transaction {
        UserGameSubscriptionTable.deleteWhere {
            (UserGameSubscriptionTable.userId eq userId) and (UserGameSubscriptionTable.gameId eq gameId)
        }

        Unit
    }

    suspend fun delete(gameId: UUID) = database.transaction {
        UserGameSubscriptionTable.deleteWhere { UserGameSubscriptionTable.gameId eq gameId }
    }
}