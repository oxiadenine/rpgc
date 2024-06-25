package io.github.oxiadenine.rpgcbot

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object CharacterPageTable : Table("character_page") {
    val path = varchar("path", 128).uniqueIndex()
    val title = varchar("title", 64).index()
    val content = text("content")
    val url = varchar("url", 128)
    val isRanking = bool("is_ranking")
    val image = blob("image").nullable()

    override val primaryKey = PrimaryKey(path)
}

class Database private constructor(private val connection: Database) {
    companion object {
        fun create(config: Config): io.github.oxiadenine.rpgcbot.Database {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.getString("url")
                driverClassName = config.getString("driver")
                username = config.getString("username")
                password = config.getString("password")
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
            SchemaUtils.create(CharacterPageTable)
        }
    }

    suspend fun <T> transaction(statement: suspend Transaction.() -> T) = newSuspendedTransaction(
        context = Dispatchers.IO,
        db = connection,
        transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
        statement = statement
    )
}