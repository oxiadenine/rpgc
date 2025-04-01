package io.github.oxiadenine.rpgc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oxiadenine.rpgc.repository.User
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object UserTable : Table("user") {
    val id = long("id").uniqueIndex()
    val name = varchar("name", 64).index()
    val role = enumeration<User.Role>("role")
    val language = varchar("language", 4)

    override val primaryKey = PrimaryKey(id)
}

object GameTable : Table("game") {
    val id = uuid("id").uniqueIndex()
    val name = varchar("name", 64).index()

    override val primaryKey = PrimaryKey(id)
}

object UserGameSubscriptionTable : IntIdTable("user_game_subscription") {
    val userId = (long("user_id") references UserTable.id).index()
    val gameId = (uuid("game_id") references GameTable.id).index()
}

object CharacterTable : Table("character") {
    val id = uuid("id").uniqueIndex()
    val name = varchar("name", 64).index()
    val content = text("content")
    val isRanking = bool("is_ranking")

    val gameId = (uuid("game_id") references GameTable.id).index()

    override val primaryKey = PrimaryKey(id)
}

object CharacterImageTable : Table("character_image") {
    val id = text("id").uniqueIndex()
    val name = varchar("name", 64).index()
    val binary = blob("binary")
    val type = varchar("type", 4)

    val characterId = (uuid("character_id") references CharacterTable.id).index()

    override val primaryKey = PrimaryKey(id)
}

class Database private constructor(private val connection: Database) {
    companion object {
        fun create(config: ApplicationConfig): io.github.oxiadenine.rpgc.Database {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.property("url").getString()
                driverClassName = config.property("driver").getString()
                username = config.property("username").getString()
                password = config.property("password").getString()
                isAutoCommit = true
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ.toString()

                validate()
            }

            val connection = Database.connect(datasource = HikariDataSource(hikariConfig))

            return Database(connection)
        }
    }

    init {
        transaction(connection) {
            SchemaUtils.create(UserTable, GameTable, UserGameSubscriptionTable, CharacterTable, CharacterImageTable)
        }
    }

    suspend fun <T> transaction(statement: suspend Transaction.() -> T) = newSuspendedTransaction(
        context = Dispatchers.IO,
        db = connection,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        statement = statement
    )
}