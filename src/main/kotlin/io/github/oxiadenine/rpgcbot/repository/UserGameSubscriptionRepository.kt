package io.github.oxiadenine.rpgcbot.repository

import io.github.oxiadenine.rpgcbot.Database
import io.github.oxiadenine.rpgcbot.UserGameSubscriptionTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

data class UserGameSubscription(val userId: Long, val gameKey: String)

class UserGameSubscriptionRepository(private val database: Database) {
    suspend fun create(userGameSubscription: UserGameSubscription) = database.transaction {
        UserGameSubscriptionTable.insert { statement ->
            statement[userId] = userGameSubscription.userId
            statement[gameKey] = userGameSubscription.gameKey
        }
    }

    suspend fun read() = database.transaction {
        UserGameSubscriptionTable.selectAll().map { record ->
            UserGameSubscription(
                record[UserGameSubscriptionTable.userId],
                record[UserGameSubscriptionTable.gameKey]
            )
        }
    }

    suspend fun read(userId: Long, gameKey: String) = database.transaction {
        UserGameSubscriptionTable.selectAll().where {
            (UserGameSubscriptionTable.userId eq userId) and
                    (UserGameSubscriptionTable.gameKey eq gameKey)
        }.firstOrNull()?.let { record ->
            UserGameSubscription(
                record[UserGameSubscriptionTable.userId],
                record[UserGameSubscriptionTable.gameKey]
            )
        }
    }

    suspend fun delete(userId: Long, gameKey: String) = database.transaction {
        UserGameSubscriptionTable.deleteWhere {
            (UserGameSubscriptionTable.userId eq userId) and
                    (UserGameSubscriptionTable.gameKey eq gameKey)
        }
    }
}