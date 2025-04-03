package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Message
import io.github.oxiadenine.rpgc.Intl
import io.github.oxiadenine.rpgc.repository.User

class Command(text: String) {
    val name: String
    val args: List<String>

    init {
        val data = text.split(" ")

        name = data[0].substringAfter("/").lowercase()
        args = data.drop(1)
    }

    enum class Name(val text: String) {
        START("start"),
        SET_LANGUAGE("setlang"),
        NEW_GAME("newgame"),
        DELETE_GAME("deletegame"),
        SET_GAME_SUBSCRIPTION("setgamesub"),
        NEW_CHARACTER("newchar"),
        NEW_CHARACTER_RANKING("newcharrank"),
        EDIT_CHARACTER("editchar"),
        EDIT_CHARACTER_RANKING("editcharrank"),
        CANCEL("cancel")
    }
}

class CommandContext(val bot: Bot, message: Message) {
    var user: User = User(
        id = message.chat.id,
        name = message.from?.username ?: message.chat.id.toString(),
        language = message.from?.languageCode ?: Intl.DEFAULT_LANGUAGE
    )

    val intl: Intl
        get() = Intl(user.language)

    val command: Command = Command(message.text!!)
}

data class CommandMessage(val type: Type, val text: String) {
    enum class Type { TEXT, IMAGE, QUERY }
}

abstract class CommandHandler(val context: CommandContext, var isHandled: Boolean = false) {
    abstract suspend fun handle(message: CommandMessage? = null)
}