package io.github.oxiadenine.rpgc.bot.command

class Command private constructor(val name: String, val args: List<String>) {
    companion object {
        fun from(text: String): Command {
            if (!text.startsWith("/")) {
                throw IllegalArgumentException()
            }

            val data = text.split(" ")

            val name = data[0].substringAfter("/").lowercase()
            val args = data.drop(1)

            return Command(name, args)
        }
    }
}

sealed class CommandResult<out T> {
    data class Success<T>(val data: T) : CommandResult<T>()
    data class Failure(val exception: Exception? = null) : CommandResult<Nothing>()
}

abstract class CommandHandler {
    abstract suspend fun handle(command: Command): CommandResult<*>
}