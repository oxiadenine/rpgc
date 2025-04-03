package io.github.oxiadenine.rpgc.command

import com.github.kotlintelegrambot.entities.ChatId
import io.github.oxiadenine.rpgc.repository.User
import io.github.oxiadenine.rpgc.repository.UserRepository
import io.github.oxiadenine.rpgc.view.Language
import io.github.oxiadenine.rpgc.view.LanguageInlineKeyboardMarkup

class SetLanguageCommandHandler(
    context: CommandContext,
    private val userRepository: UserRepository
) : CommandHandler(context) {
    override suspend fun handle(message: CommandMessage?) {
        if (message == null) {
            if (userRepository.read(context.user.id) != null) {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.language.list.message"),
                    replyMarkup = LanguageInlineKeyboardMarkup.create(context.user, context.intl.languages.map { language ->
                        Language(context.intl.translate(id = "command.language.list.item.$language.message"), language)
                    })
                )
            } else {
                context.bot.sendMessage(
                    chatId = ChatId.fromId(context.user.id),
                    text = context.intl.translate(id = "command.setlang.warning.message")
                )
            }

            return
        }

        if (message.type != CommandMessage.Type.QUERY) return

        val language = if (message.text in context.intl.languages) {
            message.text
        } else return

        val user = userRepository.read(context.user.id)!!.let { user ->
            User(id = user.id, name = user.name, language = language)
        }

        userRepository.update(user)

        context.bot.sendMessage(
            chatId = ChatId.fromId(user.id),
            text = context.intl.translate(
                id = "command.setlang.success.message",
                value = "language" to context.intl.translate(
                    id = "command.language.list.item.${user.language}.message"
                )
            )
        )

        isHandled = true
    }
}